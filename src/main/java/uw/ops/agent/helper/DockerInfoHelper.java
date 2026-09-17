package uw.ops.agent.helper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uw.common.util.JsonUtils;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.helper.vo.ContainerEventRaw;
import uw.ops.agent.helper.vo.ContainerInspectRaw;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Docker Engine API 客户端（本机 UDS 直连）。
 * <p>
 * 自实现有限 HTTP/1.1 reader（JDK16+ UDS SocketChannel），不引 docker-java（native-image 构建负担）：
 * <ul>
 *   <li>每请求独立连接（Connection: close），避免 keep-alive 状态机；</li>
 *   <li>支持 Content-Length 与 chunked 两种响应体；</li>
 *   <li>单请求总超时（默认 5 秒）与响应体大小上限（默认 8MB）；</li>
 *   <li>非 200 抛 {@link DockerApiException}，404 以 {@link ContainerNotFoundException} 单独标识
 *       （容器消失是正常业务事件，调用方据此清理采样基线而非报错）；</li>
 *   <li>非法 JSON 抛异常，由调用方按“该项指标 NULL”降级。</li>
 * </ul>
 * 端点（只经本机 UDS 访问，严禁 docker CLI / docker-java / 暴露 TCP API）：
 * <ul>
 *   <li>GET /version：启动时协商 API 版本，决定路径前缀（失败回退无前缀）；</li>
 *   <li>GET /containers/json?all=1：inventory 全量容器（含手工/第三方与 exited 容器），返回原始 vo，
 *       上报协议转换（ContainerInfo）由 HostStatsCollector 承担；</li>
 *   <li>GET /containers/{id}/stats?stream=false&amp;one-shot=1：性能计数（one-shot 跳过 daemon 预读）；</li>
 *   <li>GET /events?since=&amp;until=&amp;filters：容器事件窗口查询（10 秒一轮，带 until 请求自然关闭，非常驻流）；</li>
 *   <li>GET /containers/{id}/json：inspect（当前无调用方，方法保留备用——配额/SizeRw 等独家字段未来接入）。</li>
 * </ul>
 */
public class DockerInfoHelper {

    private static final Logger log = LoggerFactory.getLogger(DockerInfoHelper.class);

    /**
     * 容器运行状态值（/containers/json 的 State 字段）：仅 running 容器有 stats 端点数据。
     */
    public static final String STATE_RUNNING = "running";

    /**
     * stats 批量整轮预算(ms)：主轮 60s 周期内留出 inventory/边界采集/上报与调度余量，超时容器本轮置 NULL。
     */
    private static final long STATS_BATCH_BUDGET_MILLIS = 12_000L;

    /**
     * inspect 批量整轮预算(ms)：inspect 任务 60s 周期内 daemon 繁忙时的兜底上限。
     */
    private static final long INSPECT_BATCH_BUDGET_MILLIS = 30_000L;

    /**
     * 响应体大小上限(8MB)：千级容器规模的 inventory（含 exited/非受管全量）约 0.5-2MB，
     * 单容器 stats/inspect 远小于此，8MB 留足余量（本地 UDS，缓冲为瞬时内存）。
     */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /**
     * 行结束符（HTTP 分隔，静态复用避免每次查找重新编码）。
     */
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * 头结束符。
     */
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * 单请求总超时(毫秒)，默认 5 秒/轮（测试可注入覆盖）。
     */
    private static volatile int timeoutMillis = 5000;

    /**
     * UDS 目标地址：初始由 {@link Constants#getOpsDockerSock()} 解析（环境变量优先，默认值兜底）；
     * {@link #negotiateApiVersion()} 启动时按候选序探测（docker/podman 自动判定），
     * 探测成功的地址锁定复用；测试经 {@link #initForTest(String, int)} 注入临时 socket 路径（候选第一位优先）。
     */
    private static volatile UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Constants.getOpsDockerSock());

    /**
     * 自动探测的候选 socket 路径（docker 之外含 podman 的 Docker 兼容服务）：
     * 当前地址（env 解析/测试注入）永远第一优先，以下为回退候选，按序去重追加。
     */
    private static final List<String> FALLBACK_SOCKETS = List.of(
            "/var/run/docker.sock",
            "/run/docker.sock",
            "/run/podman/podman.sock");

    /**
     * API 版本前缀(如 /v1.46)，协商失败为空串(无前缀路径，daemon 兼容)。
     */
    private static volatile String apiPrefix = "";

    /**
     * stats/inspect 批量请求共享的固定并发线程池(并发 8，千级容器规模下单轮数百毫秒)。
     */
    private static final ExecutorService workerPool = createWorkerPool();

    private DockerInfoHelper() {
    }

    /**
     * 并发 8：千级容器单轮约 125 批次，本地 UDS 单请求毫秒级，单轮压在数百毫秒内，不挤占主轮预算。
     */
    private static ExecutorService createWorkerPool() {
        AtomicInteger counter = new AtomicInteger(0);
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("docker-api-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 注入 socket 路径与超时并重置版本前缀（仅测试用：临时 UDS 文件、短超时、
     * 用例间隔离——静态单例下 apiPrefix 等状态不重置会跨用例残留）。
     *
     * @param socketPath    Docker UDS socket 路径
     * @param timeoutMillis 单请求总超时(毫秒)
     */
    static void initForTest(String socketPath, int timeoutMillis) {
        DockerInfoHelper.address = UnixDomainSocketAddress.of(socketPath);
        DockerInfoHelper.timeoutMillis = timeoutMillis;
        DockerInfoHelper.apiPrefix = "";
    }

    /**
     * 启动时协商 API 版本并探测可用 socket（docker/podman 自动判定）：按候选序逐个尝试
     * GET /version——成功的地址锁定为后续请求目标（兼容 Podman 的 Docker 兼容 API）。
     * socket 文件不存在时 connect 立即失败（不耗超时），正常环境探测毫秒级。
     * 全部失败回退默认路径（保持无前缀），只记日志，后续请求照常尝试。
     */
    public static void negotiateApiVersion() {
        for (String candidate : candidateSocketPaths()) {
            if (!Files.exists(Paths.get(candidate))) {
                continue;
            }
            address = UnixDomainSocketAddress.of(candidate);
            try {
                byte[] body = execute("/version");
                JsonNode version = JsonUtils.getJsonMapper().readTree(new String(body, StandardCharsets.UTF_8));
                String apiVersion = version.path("ApiVersion").asString("");
                if (!apiVersion.isEmpty()) {
                    apiPrefix = "/v" + apiVersion;
                }
                log.info("docker-compatible api detected at {} (apiVersion={})", candidate, apiVersion);
                return;
            } catch (Exception e) {
                log.debug("socket {} not usable: {}", candidate, e.getMessage());
            }
        }
        //全部失败：回退默认路径，保留后续请求失败告警语义。
        address = UnixDomainSocketAddress.of(Constants.getOpsDockerSock());
        apiPrefix = "";
        log.warn("no usable docker-compatible socket (tried {}), fallback to {}", candidateSocketPaths(), Constants.getOpsDockerSock());
    }

    /**
     * 候选 socket 路径（去重）：当前地址（env 解析/测试注入）第一优先，
     * 回退候选含 docker FHS 路径与 podman rootful/rootless 的 Docker 兼容服务。
     */
    private static List<String> candidateSocketPaths() {
        List<String> candidates = new ArrayList<>();
        candidates.add(address.getPath().toString());
        for (String fallback : FALLBACK_SOCKETS) {
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) {
            String rootless = xdg.trim() + "/podman/podman.sock";
            if (!candidates.contains(rootless)) {
                candidates.add(rootless);
            }
        }
        return candidates;
    }

    /**
     * 拉取全量容器 inventory（含非受管与 exited 容器，不过滤 label——统计上报所有容器；
     * 配额核对所需的受管判定由 Center 端台账 planId 承担，不在 Agent 侧）。
     * JSON 一次性绑定到输入 vo，Id 为空的条目跳过；上报协议转换（ContainerInfo）由 HostStatsCollector 承担。
     *
     * @return 容器原始 vo 列表；请求失败或响应非法抛异常（本轮 inventory 失败由调用方处理）
     */
    public static List<ContainerSummaryRaw> listAllContainers() throws DockerApiException {
        byte[] body = execute("/containers/json?all=1");
        ContainerSummaryRaw[] summaries = parseVo(body, ContainerSummaryRaw[].class);
        List<ContainerSummaryRaw> list = new ArrayList<>(summaries.length);
        for (ContainerSummaryRaw summary : summaries) {
            if (summary == null || summary.getId() == null || summary.getId().isEmpty()) {
                continue;
            }
            list.add(summary);
        }
        return list;
    }

    /**
     * 拉取单容器 stats（one-shot：无 precpu 预读，配对一律用 Agent 自己的单调时钟）。
     * JSON 一次性绑定到输入 vo。
     *
     * @param containerId 容器完整 Id
     * @return stats 响应 vo
     */
    public static ContainerStatsRaw containerStats(String containerId) throws DockerApiException {
        byte[] body = execute("/containers/" + containerId + "/stats?stream=false&one-shot=1");
        return parseVo(body, ContainerStatsRaw.class);
    }

    /**
     * 拉取单容器 inspect（RestartCount/OOMKilled/HostConfig 配额核对）。
     * JSON 一次性绑定到输入 vo。
     *
     * @param containerId 容器完整 Id
     * @return inspect 响应 vo
     */
    public static ContainerInspectRaw containerInspect(String containerId) throws DockerApiException {
        byte[] body = execute("/containers/" + containerId + "/json");
        return parseVo(body, ContainerInspectRaw.class);
    }

    /**
     * 拉取时间窗口内的容器事件（OOM/restart 等生命周期事件；单请求替代逐容器 inspect）。
     * <p>
     * 带 until 的 /events 请求在窗口结束后由 daemon 关闭流（Connection: close 下读到 EOF 即完整响应），
     * 是普通轮询而非常驻订阅；since 用上次窗口上界做无缝衔接，重叠事件由调用方按 timeNano 去重。
     * daemon 仅在内存环形缓冲中保留近期事件，海量容器同窗滚动时早期事件可能被冲掉（低概率，
     * 重建型重启另有 Center 侧 containerInfoList 的容器 ID/Created 差值兜底）。
     *
     * @param sinceNano 窗口起点（纳秒时间戳，含）
     * @param untilNano 窗口终点（纳秒时间戳，含）
     * @return 事件列表（窗口内无事件时为空表）
     * @throws DockerApiException 请求失败
     */
    public static List<ContainerEventRaw> containerEvents(long sinceNano, long untilNano) throws DockerApiException {
        String filters = urlEncode("{\"type\":{\"container\":true}}");
        byte[] body = execute("/events?since=" + dockerTimestamp(sinceNano) + "&until=" + dockerTimestamp(untilNano) + "&filters=" + filters);
        List<ContainerEventRaw> events = new ArrayList<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                events.add(JsonUtils.parse(trimmed, ContainerEventRaw.class));
            } catch (Exception e) {
                //单行损坏跳过，不拖垮整窗。
                log.warn("skip malformed event line: {}", e.getMessage());
            }
        }
        return events;
    }

    /**
     * 纳秒时间戳转 Docker API 时间参数（sec.nanos 格式，保证窗口边界纳秒精度）。
     */
    private static String dockerTimestamp(long nano) {
        return (nano / 1_000_000_000L) + "." + String.format("%09d", nano % 1_000_000_000L);
    }

    /**
     * 查询参数 URL 编码。
     */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }


    /**
     * 批量拉取容器 stats，并发 8；单容器失败（含 404/超时/非法 JSON）与整轮预算超时的容器本轮置 NULL，不影响其余。
     *
     * @param containerIds 容器完整 Id 列表
     * @return containerId -&gt; stats vo（失败项缺省）
     */
    public static Map<String, ContainerStatsRaw> containerStatsBatch(List<String> containerIds) {
        return executeBatch(containerIds, DockerInfoHelper::containerStats, STATS_BATCH_BUDGET_MILLIS);
    }

    /**
     * 批量拉取容器 inspect，并发 8；单容器失败与整轮预算超时的容器本轮缺省，不影响其余。
     *
     * @param containerIds 容器完整 Id 列表
     * @return containerId -&gt; inspect vo（失败项缺省）
     */
    public static Map<String, ContainerInspectRaw> containerInspectBatch(List<String> containerIds) {
        return executeBatch(containerIds, DockerInfoHelper::containerInspect, INSPECT_BATCH_BUDGET_MILLIS);
    }

    /**
     * 批量执行：固定并发 8，单失败跳过（stats 单容器失败只丢该容器性能，不影响其余容器与 inventory）。
     * 整轮预算超时后取消未完成任务（排队的不启动、运行中的被中断），防止 daemon 繁忙时
     * 全部请求各自超时导致的排队串行累积（千容器最坏 ~600s）拖垮采集节奏——超时容器本轮 NULL。
     */
    private static <T> Map<String, T> executeBatch(List<String> containerIds, ContainerCall<T> call, long budgetMillis) {
        Map<String, T> result = new HashMap<>();
        if (containerIds == null || containerIds.isEmpty()) {
            return result;
        }
        List<Callable<Map.Entry<String, T>>> tasks = new ArrayList<>(containerIds.size());
        for (String id : containerIds) {
            tasks.add(() -> Map.entry(id, call.get(id)));
        }
        try {
            List<Future<Map.Entry<String, T>>> futures = workerPool.invokeAll(tasks, budgetMillis, TimeUnit.MILLISECONDS);
            for (Future<Map.Entry<String, T>> future : futures) {
                try {
                    Map.Entry<String, T> entry = future.get();
                    if (entry.getValue() != null) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                } catch (CancellationException e) {
                    //整轮预算到期被取消：该容器本轮置 NULL。
                    log.debug("docker batch item cancelled by round budget");
                } catch (ExecutionException e) {
                    // 单容器失败已被 call 内部抛出，这里吞掉：该容器本轮性能置 NULL。
                    log.debug("docker batch item failed: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /**
     * 单容器请求函数。
     */
    @FunctionalInterface
    private interface ContainerCall<T> {

        /**
         * 拉取单容器数据。
         *
         * @param containerId 容器 Id
         * @return 响应 vo
         * @throws DockerApiException 请求失败
         */
        T get(String containerId) throws DockerApiException;
    }

    /**
     * JSON 一次性绑定到响应 vo（缺失字段保持 null）。
     */
    private static <T> T parseVo(byte[] body, Class<T> voClass) throws DockerApiException {
        try {
            return JsonUtils.parse(body, voClass);
        } catch (Exception e) {
            throw new DockerApiException("illegal json response for " + voClass.getSimpleName(), e);
        }
    }

    /**
     * 执行一次 GET 请求（自动拼协商的版本前缀）。
     * 单连接、单请求、Connection: close；总超时控制整个连接生命周期。
     *
     * @param path 请求路径(含查询串)
     * @return 响应体字节
     */
    private static byte[] execute(String path) throws DockerApiException {
        String fullPath = apiPrefix + path;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            boolean connected = channel.connect(address);
            if (!connected) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                if (selector.select(remainingMillis(deadline)) == 0) {
                    throw new DockerApiException("connect timeout: " + address.getPath());
                }
                if (!channel.finishConnect()) {
                    throw new DockerApiException("connect failed: " + address.getPath());
                }
            }
            //发送请求。
            byte[] requestBytes = ("GET " + fullPath + " HTTP/1.1\r\nHost: docker\r\nAccept: application/json\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            writeFully(channel, selector, ByteBuffer.wrap(requestBytes), deadline);
            //读取完整响应。
            return readResponse(channel, selector, deadline);
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            throw new DockerApiException("uds request failed: " + fullPath + ", err=" + e, e);
        }
    }

    /**
     * 带超时写完整请求。
     */
    private static void writeFully(SocketChannel channel, Selector selector, ByteBuffer buffer, long deadline) throws IOException, DockerApiException {
        channel.register(selector, SelectionKey.OP_WRITE);
        while (buffer.hasRemaining()) {
            if (selector.select(remainingMillis(deadline)) == 0) {
                throw new DockerApiException("write timeout");
            }
            selector.selectedKeys().clear();
            channel.write(buffer);
        }
    }

    /**
     * 读取到连接关闭或按头声明的 body 长度读满，限制总大小。
     * 完整性判断状态化：头只定位解析一次并缓存（{@link ResponseHead}），Content-Length 路径之后
     * 纯长度比较零拷贝，chunked 路径用游标只推进新增数据——避免每读入一段就全量拷贝重扫
     * （千容器 inventory 响应为 O(n²) 拷贝，是常驻进程最大的 GC 压力源）。
     */
    private static byte[] readResponse(SocketChannel channel, Selector selector, long deadline) throws IOException, DockerApiException {
        channel.register(selector, SelectionKey.OP_READ);
        ResponseBuffer buffer = new ResponseBuffer(16 * 1024);
        ByteBuffer chunk = ByteBuffer.allocate(16 * 1024);
        ResponseHead head = null;
        int headerScanFrom = 0;
        int chunkScanPos = -1;
        while (true) {
            int n = channel.read(chunk);
            if (n > 0) {
                if (buffer.size() + n > MAX_RESPONSE_BYTES) {
                    throw new DockerApiException("response too large, limit=" + MAX_RESPONSE_BYTES);
                }
                int prevSize = buffer.size();
                buffer.append(chunk.array(), 0, n);
                chunk.clear();
                if (head == null) {
                    //从未段末尾回退 3 字节起增量找 \r\n\r\n，跨段边界不漏。
                    int from = Math.max(headerScanFrom, Math.max(prevSize - 3, 0));
                    int headerEnd = indexOf(buffer.data(), buffer.size(), HEADER_END, from);
                    if (headerEnd < 0) {
                        headerScanFrom = Math.max(buffer.size() - 3, 0);
                        continue;
                    }
                    head = ResponseHead.parse(buffer.data(), headerEnd);
                    continue;
                }
                if (head.contentLength() >= 0) {
                    if (buffer.size() >= head.bodyStart() + head.contentLength()) {
                        break;
                    }
                } else if (head.chunked()) {
                    chunkScanPos = advanceChunkScan(buffer.data(), buffer.size(), chunkScanPos < 0 ? head.bodyStart() : chunkScanPos);
                    if (chunkScanPos < 0) {
                        break;
                    }
                }
                //无长度声明且非 chunked：Connection: close 下读到 EOF。
            } else if (n == 0) {
                if (selector.select(remainingMillis(deadline)) == 0) {
                    throw new DockerApiException("read timeout");
                }
                selector.selectedKeys().clear();
            } else {
                //EOF
                break;
            }
        }
        return parseResponse(buffer.data(), buffer.size(), head);
    }

    /**
     * 解析响应：状态行 + 头 + body（Content-Length 截断或 chunked 解码）。
     * 头解析结果由读取期缓存传入（head 为 null 表示响应未含完整头）。
     *
     * @return body 字节
     */
    private static byte[] parseResponse(byte[] data, int limit, ResponseHead head) throws DockerApiException {
        int lineEnd = indexOf(data, limit, CRLF, 0);
        if (lineEnd < 0) {
            throw new DockerApiException("malformed http response: no status line");
        }
        String statusLine = new String(data, 0, lineEnd, StandardCharsets.US_ASCII);
        //状态行：HTTP/1.1 200 OK
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new DockerApiException("malformed status line: " + statusLine);
        }
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new DockerApiException("malformed status code: " + statusLine);
        }
        if (code != 200) {
            if (code == 404) {
                throw new ContainerNotFoundException("docker api 404: " + (parts.length > 2 ? parts[2] : "not found"));
            }
            throw new DockerApiException("docker api non-200: " + code);
        }
        if (head == null) {
            throw new DockerApiException("malformed http response: no header terminator");
        }
        if (head.contentLength() >= 0) {
            if (limit < head.bodyStart() + head.contentLength()) {
                throw new DockerApiException("truncated body: declared=" + head.contentLength() + ", got=" + (limit - head.bodyStart()));
            }
            byte[] body = new byte[(int) head.contentLength()];
            System.arraycopy(data, head.bodyStart(), body, 0, (int) head.contentLength());
            return body;
        }
        if (head.chunked()) {
            return decodeChunked(data, limit, head.bodyStart());
        }
        //无长度声明且非 chunked：到 EOF 为止的全部剩余字节即 body。
        byte[] body = new byte[limit - head.bodyStart()];
        System.arraycopy(data, head.bodyStart(), body, 0, body.length);
        return body;
    }

    /**
     * 已解析的响应头（读取期定位一次，完整性与 body 解析共用，不重复解析）。
     */
    private record ResponseHead(int headerEnd, long contentLength, boolean chunked) {

        int bodyStart() {
            return headerEnd + 4;
        }

        static ResponseHead parse(byte[] data, int headerEnd) {
            String header = new String(data, 0, headerEnd, StandardCharsets.US_ASCII).toLowerCase();
            return new ResponseHead(headerEnd, headerContentLength(header), header.contains("transfer-encoding: chunked"));
        }
    }

    /**
     * 从头区解析 content-length（小写头），无声明返回 -1。
     */
    private static long headerContentLength(String lowerHeader) {
        for (String line : lowerHeader.split("\r\n")) {
            if (line.startsWith("content-length:")) {
                try {
                    return Long.parseLong(line.substring("content-length:".length()).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * chunk size 行解析结果：dataStart 为 chunk 数据起点。
     */
    private record ChunkHeader(int dataStart, int size) {
    }

    /**
     * 解析 pos 起的 chunk size 行（hex[;扩展]）。
     *
     * @return null=行未读满或损坏（读取期等待更多数据，解码期据此报错）
     */
    private static ChunkHeader parseChunkHeader(byte[] data, int limit, int pos) {
        int lineEnd = indexOf(data, limit, CRLF, pos);
        if (lineEnd < 0) {
            return null;
        }
        String sizeLine = new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
        int size;
        try {
            size = Integer.parseInt(sizeLine.split(";")[0], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return new ChunkHeader(lineEnd + 2, size);
    }

    /**
     * chunked 增量扫描：从 from 起推进游标到尚未读满的 chunk 边界（只扫新增数据）。
     *
     * @return 新游标（&gt;=0，继续读）；-1 表示已读到终止块（响应完整）
     */
    private static int advanceChunkScan(byte[] data, int limit, int from) {
        int pos = from;
        while (pos < limit) {
            ChunkHeader header = parseChunkHeader(data, limit, pos);
            if (header == null) {
                //size 行未读满或损坏：等待更多数据（真损坏最终由 decodeChunked 报错）。
                return pos;
            }
            if (header.size() == 0) {
                return -1;
            }
            int next = header.dataStart() + header.size() + 2;
            if (next > limit) {
                return pos;
            }
            pos = next;
        }
        return pos;
    }

    /**
     * 解码 chunked body（读取完成后一次执行）。
     */
    private static byte[] decodeChunked(byte[] data, int limit, int pos) throws DockerApiException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        while (true) {
            ChunkHeader header = parseChunkHeader(data, limit, pos);
            if (header == null) {
                throw new DockerApiException("malformed chunked response: missing or bad chunk size line");
            }
            if (header.size() == 0) {
                //终止 chunk，trailer 直接忽略(读到 EOF 或剩余)。
                return out.toByteArray();
            }
            int dataEnd = header.dataStart() + header.size();
            if (dataEnd + 2 > limit) {
                throw new DockerApiException("truncated chunk: declared=" + header.size() + ", got=" + (limit - header.dataStart()));
            }
            if (out.size() + header.size() > MAX_RESPONSE_BYTES) {
                throw new DockerApiException("response too large, limit=" + MAX_RESPONSE_BYTES);
            }
            out.write(data, header.dataStart(), header.size());
            pos = dataEnd + 2;
        }
    }

    /**
     * 查找子串位置，未找到返回 -1。
     */
    private static int indexOf(byte[] data, int limit, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= limit - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 剩余毫秒（至少 1ms 保证 select 不立即返回 0）。
     */
    private static long remainingMillis(long deadline) {
        long remain = (deadline - System.nanoTime()) / 1_000_000L;
        return Math.max(remain, 1);
    }

    /**
     * 读取期可增长字节缓冲：支持按区间增量扫描与长度比较（ByteArrayOutputStream 只能全量
     * toByteArray，会让每读入 16KB 就整体拷贝一次）。
     */
    private static final class ResponseBuffer {

        private byte[] data;

        private int size;

        ResponseBuffer(int capacity) {
            data = new byte[capacity];
        }

        void append(byte[] src, int offset, int length) {
            if (size + length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + length));
            }
            System.arraycopy(src, offset, data, size, length);
            size += length;
        }

        byte[] data() {
            return data;
        }

        int size() {
            return size;
        }
    }

    /**
     * Docker API 请求异常（连接/超时/非 200/非法 JSON/超长响应）。
     */
    public static class DockerApiException extends Exception {

        /**
         * @param message 异常信息
         */
        public DockerApiException(String message) {
            super(message);
        }

        /**
         * @param message 异常信息
         * @param cause   根因
         */
        public DockerApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 404：容器已不存在（正常业务事件，调用方据此清基线而非报错）。
     */
    public static class ContainerNotFoundException extends DockerApiException {

        /**
         * @param message 异常信息
         */
        public ContainerNotFoundException(String message) {
            super(message);
        }
    }

}
