package uw.ops.agent.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用 Docker Engine API 假 daemon：自环 UDS 服务器，按请求返回预置响应，
 * 供 StructuredTaskExecutor/MainService 分发测试共用。
 * 记录全部请求原文（含请求体）供断言请求序列与请求体字段。
 */
public final class FakeDockerDaemon implements AutoCloseable {

    /**
     * 按请求原文生成响应原文。
     */
    @FunctionalInterface
    public interface Responder {

        /**
         * 按请求生成响应。
         *
         * @param request 完整请求文本（请求行+头+体）
         * @return 响应原文(HTTP 报文)
         */
        String respond(String request);
    }

    private ServerSocketChannel serverChannel;
    private Path socketFile;
    private Thread serverThread;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Responder responder = request -> "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n";

    /**
     * 本地镜像默认存在（ensureImage 的 GET /images/{ref}/json 探测默认返回 200，跳过拉取）——
     * 既有执行链路测试不感知镜像准备步骤；镜像拉取链路测试置 false 后由 responder 自行分发。
     */
    private volatile boolean imagesExistByDefault = true;

    /**
     * 启动假 daemon 并把静态客户端注入到本实例的临时 socket（2 秒短超时）。
     *
     * @throws IOException socket 创建失败
     */
    public void start() throws IOException {
        socketFile = Files.createTempFile("docker-exec-test", ".sock");
        Files.deleteIfExists(socketFile);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketFile.toString()));
        serverThread = new Thread(() -> {
            while (serverChannel.isOpen()) {
                try (SocketChannel client = serverChannel.accept()) {
                    String request = readRequest(client);
                    requests.add(request);
                    String response;
                    if (imagesExistByDefault && request.startsWith("GET /images/") && request.contains("/json")) {
                        //默认拦截：镜像存在（ensureImage 探测短路，不进 responder）。
                        response = jsonResponse(200, "{\"Id\":\"sha256:fakeimage\",\"RepoTags\":[]}");
                    } else {
                        response = responder.respond(request);
                    }
                    client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                    //Connection: close 语义：写完即关。
                } catch (Exception e) {
                    //服务器关闭。
                }
            }
        }, "fake-docker-daemon");
        serverThread.setDaemon(true);
        serverThread.start();
        DockerInfoHelper.initForTest(socketFile.toString(), 2000);
    }

    /**
     * 关闭"本地镜像默认存在"拦截（镜像拉取链路测试用：images 探测交由 responder 分发）。
     *
     * @return 本实例（链式）
     */
    public FakeDockerDaemon imagesNotExistByDefault() {
        this.imagesExistByDefault = false;
        return this;
    }

    /**
     * 设置响应函数。
     *
     * @param responder 响应函数
     */
    public void setResponder(Responder responder) {
        this.responder = responder;
    }

    /**
     * 全部请求原文（时序即调用序列）。
     *
     * @return 请求列表
     */
    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() throws IOException {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (socketFile != null) {
            Files.deleteIfExists(socketFile);
        }
    }

    /**
     * 读完整请求：先读到头结束符，再按 Content-Length 读满请求体
     * （写操作 POST 带 JSON 体，只读头会导致客户端写体时服务端已关闭）。
     */
    private String readRequest(SocketChannel client) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int headerEnd = -1;
        while (headerEnd < 0) {
            int n = client.read(buffer);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                out.write(buffer.array(), 0, n);
                buffer.clear();
                headerEnd = indexOfHeaderEnd(out.toByteArray());
            }
        }
        if (headerEnd < 0) {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        int contentLength = contentLengthOf(out.toByteArray(), headerEnd);
        while (out.size() < headerEnd + 4 + contentLength) {
            int n = client.read(buffer);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                out.write(buffer.array(), 0, n);
                buffer.clear();
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 定位头结束符位置（全量扫描，非仅尾部）。
     */
    private int indexOfHeaderEnd(byte[] data) {
        for (int i = 0; i <= data.length - 4; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析请求头内的 Content-Length（无声明返回 0）。
     */
    private int contentLengthOf(byte[] data, int headerEnd) {
        String header = new String(data, 0, headerEnd, StandardCharsets.UTF_8);
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /**
     * 构造 JSON 响应报文（Content-Length 定长）。
     *
     * @param status 状态码
     * @param body   JSON 响应体
     * @return 响应原文
     */
    public static String jsonResponse(int status, String body) {
        return "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    /**
     * 构造无体响应报文（204/304 等成功码）。
     *
     * @param status 状态码
     * @return 响应原文
     */
    public static String emptyResponse(int status) {
        return "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\n\r\n";
    }

    /**
     * 构造 inspect 响应体（幂等判定的比对源：State.Running/ExitCode/Config.Image/Labels）。
     *
     * @param id       容器 Id
     * @param name     容器名（带前导斜杠）
     * @param image    镜像引用
     * @param running  是否运行中
     * @param exitCode 退出码（running 时忽略）
     * @param labels   容器 labels（可 null）
     * @return inspect JSON
     */
    public static String inspectBody(String id, String name, String image, boolean running, Long exitCode, Map<String, String> labels) {
        String labelJson = labels == null || labels.isEmpty() ? "{}"
                : labels.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "{" + s + "}")
                .orElse("{}");
        return "{\"Id\":\"" + id + "\",\"Name\":\"" + name + "\",\"State\":{\"Status\":\""
                + (running ? "running" : "exited") + "\",\"Running\":" + running
                + ",\"ExitCode\":" + (running ? 0 : exitCode)
                + "},\"Config\":{\"Image\":\"" + image + "\",\"Labels\":" + labelJson + "}}";
    }
}
