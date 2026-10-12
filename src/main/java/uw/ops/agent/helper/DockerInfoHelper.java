package uw.ops.agent.helper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import uw.common.util.JsonUtils;
import uw.common.util.SystemClock;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.helper.vo.ContainerEventRaw;
import uw.ops.agent.helper.vo.ContainerInspectRaw;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;
import uw.ops.agent.helper.vo.CreateContainerParam;
import uw.ops.agent.helper.vo.CreateContainerRaw;

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
 *   <li>GET /containers/{id}/json：inspect（配额核对与结构化任务幂等判定）。</li>
 * </ul>
 * 写操作端点（仅限 ops 结构化任务通道 StructuredTaskExecutor 触发，采集路径只读不变）：
 * <ul>
 *   <li>POST /containers/create?name=：创建容器（daemon 先拉镜像，长超时）；</li>
 *   <li>POST /containers/{id}/start：启动（204/304 幂等成功）；</li>
 *   <li>POST /containers/{id}/stop?t=N：优雅停止（SIGTERM 等待 N 秒后 SIGKILL）；</li>
 *   <li>DELETE /containers/{id}?force=1&amp;v=1：清理残留（结构化 START 的幂等重建前置）。</li>
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

    /** 当前执行线程的任务总截止时间，所有 Engine 请求共享；使用单调时钟防时间校正。 */
    private static final ThreadLocal<Long> TASK_DEADLINE = new ThreadLocal<>();

    /** 开始结构化任务的端到端预算，完成后必须清理线程局部状态。 */
    static void beginTaskBudget(long budgetMillis) {
        TASK_DEADLINE.set(System.nanoTime() + budgetMillis * 1_000_000L);
    }

    /** 清理任务预算，避免线程池复用时影响下一项任务或指标采集。 */
    static void endTaskBudget() {
        TASK_DEADLINE.remove();
    }

    /** 返回剩余预算；预算耗尽即失败，禁止继续产生 create/start 等副作用。 */
    static long remainingTaskMillis() throws DockerApiException {
        Long deadline = TASK_DEADLINE.get();
        if (deadline == null) {
            return Long.MAX_VALUE;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new DockerApiException("docker task budget exhausted.");
        }
        return Math.max(1L, remaining / 1_000_000L);
    }

    /**
     * create 超时(毫秒)：daemon 在 create 时拉取镜像（多架构大镜像可达分钟级），按普通任务 10 分钟预算上限。
     */
    private static final int CREATE_TIMEOUT_MILLIS = 600_000;

    /**
     * start 超时(毫秒)。
     */
    private static final int START_TIMEOUT_MILLIS = 60_000;

    /**
     * stop 超时在 t 参数基础上的余量(毫秒)：覆盖 SIGKILL 与 daemon 清理耗时。
     */
    private static final int STOP_TIMEOUT_MARGIN_MILLIS = 15_000;

    /**
     * remove 超时(毫秒)。
     */
    private static final int REMOVE_TIMEOUT_MILLIS = 30_000;

    /**
     * 读操作成功码。
     */
    private static final int[] STATUS_OK = {200};

    /**
     * create 成功码。
     */
    private static final int[] STATUS_CREATED = {201};

    /**
     * start/stop 成功码：204=已变更，304=已在目标状态（幂等成功）。
     */
    private static final int[] STATUS_NO_CONTENT_IDEMPOTENT = {204, 304};

    /**
     * remove 成功码。
     */
    private static final int[] STATUS_NO_CONTENT = {204};

    /**
     * UDS 目标地址：初始由 {@link Constants#getOpsDockerSock()} 解析（环境变量优先，默认值兜底）；
     * {@link #negotiateApiVersion()} 启动时按候选序探测（docker/podman 自动判定），
     * 探测成功的地址锁定复用；测试经 {@link #initForTest(String, int)} 注入临时 socket 路径（候选第一位优先）。
     */
    private static volatile UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Constants.getOpsDockerSock());

    /**
     * 自动探测的候选 socket 路径（docker 之外含 podman 的 Docker 兼容服务）：
     * 当前地址（env 解析/测试注入）永远第一优先，以下为回退候选，按序去重追加
     * （末位为 macOS Docker Desktop 开发环境的用户目录路径，生产 Linux 不受影响）。
     */
    private static final List<String> FALLBACK_SOCKETS = List.of(
            "/var/run/docker.sock",
            "/run/docker.sock",
            "/run/podman/podman.sock",
            System.getProperty("user.home") + "/.docker/run/docker.sock");

    /**
     * API 版本前缀(如 /v1.46)，协商失败为空串(无前缀路径，daemon 兼容)。
     */
    private static volatile String apiPrefix = "";

    /**
     * 最近一次失败重探时间戳(毫秒)：连接级失败触发地址重探的节流窗，避免每次请求都全候选探测。
     */
    private static volatile long lastRenegotiateAt = 0L;

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
                log.debug("socket {} not usable, errorType={}", candidate, e.getClass().getSimpleName());
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
                log.warn("skip malformed event line, errorType={}", e.getClass().getSimpleName());
            }
        }
        return events;
    }

    /**
     * 拉取超时(毫秒)：多架构大镜像分层下载分钟级，按普通任务 10 分钟预算留出 create/start 余量。
     */
    private static final int PULL_TIMEOUT_MILLIS = 480_000;

    /**
     * 镜像准备：本地不存在则按确定引用拉取（F02）。
     * <p>
     * Docker Engine API 的 /containers/create <b>不具备</b> docker CLI run 的自动拉取行为——
     * 本地无镜像直接 404（新主机/清理镜像后重建/发布新 digest 都会在 create 阶段失败），
     * 故 create 前必须显式确保镜像存在。凭据随任务下发（center 领取时按镜像域名注入
     * X-Registry-Auth=base64 json），用后即弃——不落盘、不进日志。
     *
     * @param imageRef     确定镜像引用（name:tag 或 name@digest）
     * @param registryAuth registry 拉取凭据（base64，null=匿名拉取；Engine API 不读取 CLI 登录配置）
     */
    public static void ensureImage(String imageRef, String registryAuth) throws DockerApiException {
        if (imageRef == null || imageRef.isEmpty()) {
            return;
        }
        if (imageExists(imageRef)) {
            return;
        }
        pullImage(imageRef, registryAuth);
    }

    /**
     * 本地镜像存在性检查：GET /images/{ref}/json，200=存在；404（镜像不存在）=false；
     * 其余异常（daemon 不可达等）上抛由调用方按任务失败处理。
     *
     * @param imageRef 镜像引用
     * @return true=本地已存在
     */
    public static boolean imageExists(String imageRef) throws DockerApiException {
        try {
            execute("/images/" + urlEncode(imageRef) + "/json");
            return true;
        } catch (ContainerNotFoundException e) {
            return false;
        }
    }

    /**
     * 拉取镜像：POST /images/create?fromImage=&amp;tag=（digest 引用 fromImage=repo@digest 不带 tag，
     * tag 引用拆分 name/tag，无 tag 补 latest）。
     * <p>
     * 响应为逐行 JSON 事件流（status/progress/error）——认证失败/tag 不存在等以
     * {"error": "..."} 行报告（HTTP 状态仍 200），全量读取后逐行检查 error 字段；
     * 非事件流响应体、空事件流或任意错误事件均失败，不将未知响应当作拉取成功。
     * 凭据经 X-Registry-Auth 头传递，不进日志。
     *
     * @param imageRef     确定镜像引用
     * @param registryAuth registry 拉取凭据（base64，null=匿名）
     */
    public static void pullImage(String imageRef, String registryAuth) throws DockerApiException {
        String fromImage;
        String tag = null;
        if (imageRef.contains("@")) {
            //digest 引用整体作 fromImage（已锁定内容，无需 tag）。
            fromImage = imageRef;
        } else {
            int colon = imageRef.lastIndexOf(':');
            int slash = imageRef.lastIndexOf('/');
            if (colon > slash) {
                fromImage = imageRef.substring(0, colon);
                tag = imageRef.substring(colon + 1);
            } else {
                fromImage = imageRef;
                tag = "latest";
            }
        }
        StringBuilder path = new StringBuilder("/images/create?fromImage=").append(urlEncode(fromImage));
        if (tag != null) {
            path.append("&tag=").append(urlEncode(tag));
        }
        long start = SystemClock.now();
        byte[] body = execute("POST", path.toString(), null, PULL_TIMEOUT_MILLIS, STATUS_OK,
                "X-Registry-Auth", registryAuth);
        //逐行解析事件流，error 字段非空即拉取失败（详细原因只进日志，凭据不外泄）。
        String text = new String(body, StandardCharsets.UTF_8);
        boolean hasEvent = false;
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> event = JsonUtils.parse(line, new TypeReference<Map<String, Object>>() {
                });
                if (event == null || event.isEmpty()) {
                    throw new DockerApiException("image pull response invalid.");
                }
                hasEvent = true;
                Object error = event.get("error");
                if (error instanceof String s && !s.isEmpty()) {
                    throw new DockerApiException("image pull failed: " + imageRef + ", err=" + s);
                }
                if (event.get("errorDetail") instanceof Map<?, ?> detail && !detail.isEmpty()) {
                    throw new DockerApiException("image pull failed: " + imageRef);
                }
            } catch (DockerApiException e) {
                throw e;
            } catch (Exception e) {
                throw new DockerApiException("image pull response invalid.");
            }
        }
        if (!hasEvent) {
            throw new DockerApiException("image pull response empty.");
        }
        log.info("image pulled: {} in {}ms", imageRef, SystemClock.now() - start);
    }

    /**
     * 创建容器（POST /containers/create?name=），不指定平台（daemon 按主机架构选择）。
     * 镜像存在性由调用方（结构化执行器）经 {@link #ensureImage} 预先确保——create 本身不拉取镜像；
     * 同名容器已存在时 daemon 返回 409，由调用方（结构化执行器）先做幂等清理。
     *
     * @param containerName 容器名（uw-ops-{containerId}，URL 编码前做安全字符校验）
     * @param param         创建参数（镜像/env/labels/挂载/资源/CPU/重启策略/网络等 API 全集）
     * @return 响应 vo，{@link CreateContainerRaw#getId()} 为 Docker 返回的容器 ID（containerHash 台账来源）
     */
    public static CreateContainerRaw createContainer(String containerName, CreateContainerParam param) throws DockerApiException {
        return createContainer(containerName, param, null);
    }

    /**
     * 创建容器（POST /containers/create?name=&amp;platform=）。
     *
     * @param platform 平台（如 linux/amd64、linux/arm64；多架构镜像按 tag 拉取时强制选择，
     *                 null=不指定由 daemon 按主机架构选择；@digest 已锁定架构的场景通常不需要）
     */
    public static CreateContainerRaw createContainer(String containerName, CreateContainerParam param, String platform) throws DockerApiException {
        requireSafeToken(containerName);
        byte[] body;
        try {
            body = JsonUtils.toBytes(param);
        } catch (Exception e) {
            throw new DockerApiException("serialize create param failed: " + containerName, e);
        }
        String path = "/containers/create?name=" + urlEncode(containerName);
        if (platform != null && !platform.isEmpty()) {
            path += "&platform=" + urlEncode(platform);
        }
        byte[] response = execute("POST", path, body, CREATE_TIMEOUT_MILLIS, STATUS_CREATED);
        return parseVo(response, CreateContainerRaw.class);
    }

    /**
     * 启动容器（POST /containers/{id}/start）。204/304 均为成功（304=已在运行，幂等重放安全）。
     *
     * @param containerId 容器完整 Id 或名称
     */
    public static void startContainer(String containerId) throws DockerApiException {
        requireSafeToken(containerId);
        execute("POST", "/containers/" + containerId + "/start", null, START_TIMEOUT_MILLIS, STATUS_NO_CONTENT_IDEMPOTENT);
    }

    /**
     * 优雅停止容器（POST /containers/{id}/stop?t=N）：先 SIGTERM 等待 N 秒（应用走停机链摘流），
     * 超时后 daemon 发 SIGKILL。204/304 均为成功（304=已停止）。404 抛 {@link ContainerNotFoundException}，
     * 调用方按"容器已不在"幂等成功处理。
     *
     * @param containerId   容器完整 Id 或名称
     * @param timeoutSeconds SIGTERM 等待秒数（terminationGraceSeconds）
     */
    public static void stopContainer(String containerId, int timeoutSeconds) throws DockerApiException {
        requireSafeToken(containerId);
        int timeoutMillis = timeoutSeconds * 1000 + STOP_TIMEOUT_MARGIN_MILLIS;
        execute("POST", "/containers/" + containerId + "/stop?t=" + timeoutSeconds, null, timeoutMillis, STATUS_NO_CONTENT_IDEMPOTENT);
    }

    /**
     * 删除容器（DELETE /containers/{id}?force=1&v=1，强制删含卷）。仅用于结构化 START 的幂等重建前置
     * （对齐模板 start 脚本头部的 stop+rm 清理行）和常规 STOP，确认停止后删除残留。
     *
     * @param containerId 容器完整 Id 或名称
     */
    public static void removeContainer(String containerId) throws DockerApiException {
        requireSafeToken(containerId);
        execute("DELETE", "/containers/" + containerId + "?force=1&v=1", null, REMOVE_TIMEOUT_MILLIS, STATUS_NO_CONTENT);
    }

    /**
     * URL 路径段安全校验：容器名/Id 只允许字母数字与 . _ -，拒绝空值与路径注入字符
     * （/ ? & 空格等会破坏手工拼接的请求行）。
     */
    private static void requireSafeToken(String token) throws DockerApiException {
        if (!isSafeIdToken(token)) {
            throw new DockerApiException(token == null || token.isEmpty()
                    ? "container name/id is blank" : "illegal container name/id: " + token);
        }
    }

    /**
     * 容器名/Id 安全线程令牌判定（唯一字符集来源：URL 路径段与持久化文件名共用）：
     * 非空且仅含字母数字与 . _ -。
     *
     * @param token 待判定令牌
     * @return 安全返回 true
     */
    public static boolean isSafeIdToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
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
                    log.debug("docker batch item failed, errorType={}", e.getClass().getSimpleName());
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
     * 执行一次 GET 请求（默认 5 秒超时，成功码 200）。
     *
     * @param path 请求路径(含查询串)
     * @return 响应体字节
     */
    private static byte[] execute(String path) throws DockerApiException {
        return execute("GET", path, null, timeoutMillis, STATUS_OK);
    }

    /**
     * 执行一次请求（自动拼协商的版本前缀）。
     * 单连接、单请求、Connection: close；总超时控制整个连接生命周期。
     *
     * @param method               HTTP 方法（GET/POST/DELETE）
     * @param path                 请求路径(含查询串)
     * @param body                 请求体（null=无体；非 null 自动携带 Content-Type 与 Content-Length）
     * @param requestTimeoutMillis 本次请求总超时(毫秒)——写操作按各自预算独立传入
     * @param successCodes         成功状态码集合（读 200、create 201、start/stop 204+304、remove 204）
     * @return 响应体字节
     */
    private static byte[] execute(String method, String path, byte[] body, int requestTimeoutMillis, int[] successCodes) throws DockerApiException {
        return execute(method, path, body, requestTimeoutMillis, successCodes, null, null);
    }

    /**
     * 执行一次请求（可带一个额外头，如 X-Registry-Auth）：头值在此统一校验
     * （CRLF 注入防护内聚在 HTTP 层，调用方只传结构化名值对）。
     *
     * @param extraHeaderName  额外头名（null=无）
     * @param extraHeaderValue 额外头值（null=不携带；含 CR/LF 时拒绝请求）
     */
    private static byte[] execute(String method, String path, byte[] body, int requestTimeoutMillis, int[] successCodes,
                                  String extraHeaderName, String extraHeaderValue) throws DockerApiException {
        if (extraHeaderName != null && extraHeaderValue != null
                && (extraHeaderValue.contains("\r") || extraHeaderValue.contains("\n"))) {
            throw new DockerApiException("invalid extra header value: " + extraHeaderName);
        }
        String fullPath = apiPrefix + path;
        long deadline = System.nanoTime() + Math.min(requestTimeoutMillis, remainingTaskMillis()) * 1_000_000L;
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
            byte[] requestBytes = buildRequest(method, fullPath, body, extraHeaderName, extraHeaderValue);
            writeFully(channel, selector, ByteBuffer.wrap(requestBytes), deadline);
            return readResponse(channel, selector, deadline, successCodes);
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            //连接级失败(socket 不存在/daemon 重启换了路径)重探地址：启动时一次性锁定会在
            //Docker Desktop 重启后永久连死路径，这里节流 30s 重探自愈。
            renegotiateOnConnectFailure();
            throw new DockerApiException("uds request failed: " + method + " " + fullPath + ", err=" + e, e);
        }
    }

    /**
     * 连接级失败后的地址重探（30 秒节流）：候选 socket 文件存在性可能已变化
     * （Docker Desktop 重启后 /var/run 符号链接或用户目录 socket 重新出现），
     * 重新按候选序探测锁定。并发请求下 volatile 时间戳天然限频，重探本身幂等。
     */
    private static void renegotiateOnConnectFailure() {
        long now = System.currentTimeMillis();
        if (now - lastRenegotiateAt < 30_000L) {
            return;
        }
        lastRenegotiateAt = now;
        negotiateApiVersion();
    }

    /**
     * 拼请求报文：请求行 + 固定头 +（有体时）Content-Type/Content-Length + 空行 + 体。
     * 可带一个额外头（registry 拉取凭据等通道头，值已由 execute 层校验，凭据值不进日志）。
     */
    private static byte[] buildRequest(String method, String path, byte[] body, String extraHeaderName, String extraHeaderValue) {
        StringBuilder head = new StringBuilder(160);
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: docker\r\nAccept: application/json\r\nConnection: close\r\n");
        if (extraHeaderName != null && extraHeaderValue != null) {
            head.append(extraHeaderName).append(": ").append(extraHeaderValue).append("\r\n");
        }
        if (body != null) {
            head.append("Content-Type: application/json\r\nContent-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
        if (body == null || body.length == 0) {
            return headBytes;
        }
        byte[] request = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, request, 0, headBytes.length);
        System.arraycopy(body, 0, request, headBytes.length, body.length);
        return request;
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
    private static byte[] readResponse(SocketChannel channel, Selector selector, long deadline, int[] successCodes) throws IOException, DockerApiException {
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
        return parseResponse(buffer.data(), buffer.size(), head, successCodes);
    }

    /**
     * 解析响应：状态行 + 头 + body（Content-Length 截断或 chunked 解码）。
     * 头解析结果由读取期缓存传入（head 为 null 表示响应未含完整头）。
     *
     * @return body 字节
     */
    private static byte[] parseResponse(byte[] data, int limit, ResponseHead head, int[] successCodes) throws DockerApiException {
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
        if (!containsCode(successCodes, code)) {
            if (code == 404) {
                throw new ContainerNotFoundException("docker api 404: " + (parts.length > 2 ? parts[2] : "not found"));
            }
            throw new DockerApiException("docker api unexpected status: " + code + " (expected " + Arrays.toString(successCodes) + ")");
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
     * 状态码命中判定。
     */
    private static boolean containsCode(int[] successCodes, int code) {
        for (int success : successCodes) {
            if (success == code) {
                return true;
            }
        }
        return false;
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
