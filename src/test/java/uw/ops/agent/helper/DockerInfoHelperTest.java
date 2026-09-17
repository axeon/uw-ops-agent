package uw.ops.agent.helper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.ops.agent.helper.DockerInfoHelper.ContainerNotFoundException;
import uw.ops.agent.helper.DockerInfoHelper.DockerApiException;

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DockerInfoHelper UDS reader 单测。
 * 覆盖：Content-Length/chunked 响应、非 200、404、非法 JSON、超时、超长响应、批量并发跳过失败项。
 */
class DockerInfoHelperTest {

    /**
     * 测试用 UDS 服务器：按请求返回预置响应。
     */
    private ServerSocketChannel serverChannel;
    private Path socketFile;
    private Thread serverThread;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile Responder responder = request -> "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n";

    @FunctionalInterface
    private interface Responder {

        /**
         * 按请求行生成响应原文。
         *
         * @param requestLine 完整请求文本
         * @return 响应原文(HTTP 报文)
         */
        String respond(String requestLine);
    }

    @BeforeEach
    void startServer() throws IOException {
        socketFile = Files.createTempFile("docker-api-test", ".sock");
        Files.deleteIfExists(socketFile);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketFile.toString()));
        serverThread = new Thread(() -> {
            while (serverChannel.isOpen()) {
                try (SocketChannel client = serverChannel.accept()) {
                    String request = readRequest(client);
                    lastRequest.set(request);
                    byte[] response = responder.respond(request).getBytes(StandardCharsets.UTF_8);
                    client.write(ByteBuffer.wrap(response));
                    //Connection: close 语义：写完即关。
                } catch (Exception e) {
                    //服务器关闭。
                }
            }
        }, "test-uds-server");
        serverThread.setDaemon(true);
        serverThread.start();
        //静态客户端注入本用例的临时 socket 路径与 2 秒短超时。
        injectClient();
    }

    @AfterEach
    void stopServer() throws IOException {
        serverChannel.close();
        Files.deleteIfExists(socketFile);
    }

    /**
     * 读完整请求头（到 \r\n\r\n）。
     */
    private String readRequest(SocketChannel client) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = out.toByteArray();
        while (!endsWithHeaderEnd(data)) {
            int n = client.read(buffer);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                out.write(buffer.array(), 0, n);
                buffer.clear();
                data = out.toByteArray();
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private boolean endsWithHeaderEnd(byte[] data) {
        for (int i = Math.max(0, data.length - 4); i <= data.length - 4; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return true;
            }
        }
        return false;
    }

    /** 每用例注入本用例的临时 socket 路径与 2 秒短超时。 */
    private void injectClient() {
        DockerInfoHelper.initForTest(socketFile.toString(), 2000);
    }

    @Test
    void contentLengthJsonParsed() {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 27\r\n\r\n{\"ApiVersion\":\"1.46\",\"x\":1}";

        DockerInfoHelper.negotiateApiVersion();
        assertNotNull(lastRequest.get());
        assertTrue(lastRequest.get().startsWith("GET /version"));
    }

    @Test
    void versionNegotiateSetsPrefix() throws DockerApiException {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: 21\r\n\r\n{\"ApiVersion\":\"1.46\"}";

        DockerInfoHelper.negotiateApiVersion();
        responder = req -> {
            assertTrue(req.startsWith("GET /v1.46/containers/json"), "request should carry version prefix: " + req);
            return "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]";
        };
        var list = DockerInfoHelper.listAllContainers();
        assertNotNull(list);
        assertEquals(0, list.size());
    }

    @Test
    void inventoryParsedToVo() throws DockerApiException {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: 291\r\n\r\n"
                + "[{\"Id\":\"abc123\",\"Names\":[\"/app-1\"],\"Image\":\"img:1\",\"Command\":\"java -jar app.jar\",\"Created\":1700000000,"
                + "\"State\":\"running\",\"Status\":\"Up 2 hours\",\"HostConfig\":{\"NetworkMode\":\"bridge\"}},"
                + "{\"Id\":\"raw999\",\"Names\":[\"/hand-run\"],\"State\":\"running\",\"Status\":\"Up\"},"
                + "{\"Names\":[\"/broken\"],\"State\":\"created\"}]";
        var list = DockerInfoHelper.listAllContainers();
        //Id 为空的条目跳过。
        assertEquals(2, list.size());
        //保持原始 vo 形状（协议转换由 HostStatsCollector 测试覆盖）。
        var summary = list.get(0);
        assertEquals("abc123", summary.getId());
        assertEquals("/app-1", summary.getNames().get(0));
        assertEquals("img:1", summary.getImage());
        assertEquals("running", summary.getState());
        assertEquals("Up 2 hours", summary.getStatus());
        assertEquals("bridge", summary.getHostConfig().getNetworkMode());
    }

    @Test
    void inventoryIllegalJsonShapeFails() {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}";
        //响应是对象而非数组：非法 inventory 形态。
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.listAllContainers());
    }

    @Test
    void chunkedResponseDecoded() throws DockerApiException {
        //chunked 单块 body = {"cpu_stats":{"cpu_usage":{"total_usage":123}}}（47 字节，chunk size 十六进制 2f），解码后绑定 vo。
        responder = request -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2f\r\n"
                + "{\"cpu_stats\":{\"cpu_usage\":{\"total_usage\":123}}}\r\n0\r\n\r\n";
        var stats = DockerInfoHelper.containerStats("abc123");
        assertEquals(123L, stats.getCpuStats().getCpuUsage().getTotalUsage());
    }

    @Test
    void non200Fails() {
        responder = request -> "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n";
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.containerStats("abc123"));
    }

    @Test
    void notFoundMappedToContainerNotFound() {
        responder = request -> "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 27\r\n\r\n{\"message\":\"No such container\"}";
        assertThrows(ContainerNotFoundException.class, () -> DockerInfoHelper.containerInspect("gone01"));
    }

    @Test
    void illegalJsonFails() {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nnot json";
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.containerStats("abc123"));
    }

    @Test
    void oversizeResponseRejected() {
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: " + (5 * 1024 * 1024) + "\r\n\r\n";
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.containerStats("abc123"));
    }

    @Test
    void readTimeoutRejected() {
        responder = request -> {
            try {
                //挂起超过客户端超时(2s)。
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}";
        };
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.containerStats("abc123"));
    }

    @Test
    void batchSkipsFailedItems() {
        responder = request -> {
            if (request.contains("bad")) {
                return "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
            }
            return "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}";
        };
        var result = DockerInfoHelper.containerStatsBatch(List.of("good1", "bad1", "good2"));
        assertEquals(2, result.size());
        assertTrue(result.containsKey("good1") && result.containsKey("good2"));
    }

    @Test
    void connectsFailFastWhenSocketMissing() {
        DockerInfoHelper.initForTest("/nonexistent/docker-test.sock", 500);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(DockerApiException.class, DockerInfoHelper::listAllContainers));
    }

    @Test
    void containerNotFoundIsDockerApiException() {
        ContainerNotFoundException e = new ContainerNotFoundException("x");
        assertInstanceOf(DockerApiException.class, e);
    }

    @Test
    void eventsNdjsonParsedWithPascalCaseFields() throws DockerApiException {
        //真实 /events 响应形态：NDJSON 逐行，Type/Action/Actor/Actor.ID 为 PascalCase（moby 无 json tag 原样输出），
        //status/timeNano 为小写；坏行跳过不拖垮整窗。
        String body = "{\"status\":\"oom\",\"id\":\"cid001\",\"from\":\"img:1\",\"Type\":\"container\",\"Action\":\"oom\","
                + "\"Actor\":{\"ID\":\"cid001\",\"Attributes\":{\"image\":\"img:1\",\"name\":\"app-1\"}},\"scope\":\"local\","
                + "\"time\":1700000001,\"timeNano\":1700000001123456789}\n"
                + "{\"Type\":\"container\",\"Action\":\"restart\",\"Actor\":{\"ID\":\"cid002\"},\"timeNano\":1700000002123456789}\n"
                + "{broken line\n"
                + "\n";
        responder = request -> "HTTP/1.1 200 OK\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        var events = DockerInfoHelper.containerEvents(1_700_000_000_000_000_000L, 1_700_000_060_000_000_000L);
        assertEquals(2, events.size());
        assertEquals("container", events.get(0).getType());
        assertEquals("oom", events.get(0).getAction());
        assertEquals("cid001", events.get(0).getActorId());
        assertEquals(1700000001123456789L, events.get(0).getTimeNano());
        //全量映射：兼容字段/scope/time 秒戳/Actor.Attributes。
        assertEquals("oom", events.get(0).getStatus());
        assertEquals("cid001", events.get(0).getId());
        assertEquals("img:1", events.get(0).getFrom());
        assertEquals("local", events.get(0).getScope());
        assertEquals(1700000001L, events.get(0).getTime());
        assertEquals("app-1", events.get(0).getActor().getAttributes().get("name"));
        assertEquals("cid002", events.get(1).getActorId());
        //窗口参数：sec.nanos 格式（纳秒精度）+ type=container 过滤器。
        assertTrue(lastRequest.get().contains("since=1700000000.000000000"), "since format: " + lastRequest.get());
        assertTrue(lastRequest.get().contains("until=1700000060.000000000"), "until format: " + lastRequest.get());
        assertTrue(lastRequest.get().contains("type"), "filters present: " + lastRequest.get());
    }
}
