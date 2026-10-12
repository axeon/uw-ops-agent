package uw.ops.agent.helper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.common.util.JsonUtils;
import uw.ops.agent.helper.DockerInfoHelper.ContainerNotFoundException;
import uw.ops.agent.helper.DockerInfoHelper.DockerApiException;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.CreateContainerParam;
import uw.ops.agent.helper.vo.CreateContainerRaw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void createCannotOutliveRemainingTaskBudget() {
        responder = request -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"budget-test\"}");
        };
        DockerInfoHelper.beginTaskBudget(80);
        long started = System.nanoTime();
        try {
            assertThrows(DockerApiException.class,
                    () -> DockerInfoHelper.createContainer("budget-test", new CreateContainerParam()));
            assertTrue((System.nanoTime() - started) / 1_000_000 < 350,
                    "create must share the task budget instead of its independent ten-minute timeout");
            assertThrows(DockerApiException.class, () -> DockerInfoHelper.startContainer("budget-test"));
        } finally {
            DockerInfoHelper.endTaskBudget();
        }
    }

    @Test
    void malformedOrEmptyPullStreamCannotSucceed() {
        responder = request -> FakeDockerDaemon.jsonResponse(200, "<html>proxy error</html>");
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.pullImage("test/image:1", null));
        responder = request -> FakeDockerDaemon.jsonResponse(200, "\n");
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.pullImage("test/image:1", null));
        responder = request -> FakeDockerDaemon.jsonResponse(200, "{\"errorDetail\":{\"message\":\"denied\"}}\n");
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.pullImage("test/image:1", null));
    }

    /**
     * 共享假 daemon（自环 UDS 服务器，按请求返回预置响应）；
     * responder 为本类可变字段，经桥接闭包逐请求取最新值，测试体内直接赋值即可。
     */
    private final FakeDockerDaemon daemon = new FakeDockerDaemon();

    private volatile FakeDockerDaemon.Responder responder = request -> "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n";

    @BeforeEach
    void startServer() throws IOException {
        daemon.setResponder(request -> responder.respond(request));
        daemon.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        daemon.close();
    }

    /** 最近一次请求原文（请求断言用）。 */
    private String lastRequest() {
        return daemon.requests().getLast();
    }

    @Test
    void contentLengthJsonParsed() {
        responder = request -> FakeDockerDaemon.jsonResponse(200, "{\"ApiVersion\":\"1.46\",\"x\":1}");

        DockerInfoHelper.negotiateApiVersion();
        assertNotNull(lastRequest());
        assertTrue(lastRequest().startsWith("GET /version"));
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
        assertTrue(lastRequest().contains("since=1700000000.000000000"), "since format: " + lastRequest());
        assertTrue(lastRequest().contains("until=1700000060.000000000"), "until format: " + lastRequest());
        assertTrue(lastRequest().contains("type"), "filters present: " + lastRequest());
    }

    @Test
    void createContainerPostsParamAndParsesId() throws DockerApiException {
        String createBody = "{\"Id\":\"def4567890ab\",\"Warnings\":[]}";
        String response = "HTTP/1.1 201 Created\r\nContent-Length: " + createBody.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + createBody;
        responder = request -> {
            //请求行：POST + create 端点 + name 参数（容器名 URL 安全字符）。
            assertTrue(request.startsWith("POST /containers/create?name=uw-ops-123 "), "create request line: " + request);
            //带体请求自动携带 JSON 头与长度。
            assertTrue(request.contains("Content-Type: application/json"), "content-type: " + request);
            assertTrue(request.contains("Content-Length: "), "content-length: " + request);
            //请求体：Image/Env/Labels/HostConfig 关键字段（NON_NULL 省略未设字段）。
            assertTrue(request.contains("\"Image\":\"registry/uw-x@sha256:abc\""), "image body: " + request);
            assertTrue(request.contains("\"Env\":[\"APP_PORT=10010\"]"), "env body: " + request);
            assertTrue(request.contains("\"uw.ops.container-id\":\"123\""), "labels body: " + request);
            assertTrue(request.contains("\"NetworkMode\":\"host\""), "network body: " + request);
            assertTrue(request.contains("\"Name\":\"unless-stopped\""), "restart body: " + request);
            assertTrue(request.contains("\"Binds\":[\"/etc/localtime:/etc/localtime:ro\"]"), "binds body: " + request);
            assertTrue(request.contains("\"NanoCpus\":2000000000"), "cpu body: " + request);
            assertTrue(request.contains("\"Memory\":2147483648"), "memory body: " + request);
            assertFalse(request.contains("\"PortBindings\""), "unset field omitted: " + request);
            return response;
        };
        CreateContainerParam param = new CreateContainerParam();
        param.setImage("registry/uw-x@sha256:abc");
        param.setEnv(List.of("APP_PORT=10010"));
        param.setLabels(Map.of("uw.ops.container-id", "123"));
        param.setExposedPorts(Map.of("10010/tcp", new CreateContainerParam.ExposedPort()));
        CreateContainerParam.HostConfigSection hostConfig = new CreateContainerParam.HostConfigSection();
        hostConfig.setNetworkMode("host");
        hostConfig.setMemory(2048L * 1024 * 1024);
        hostConfig.setNanoCpus(2_000_000_000L);
        hostConfig.setBinds(List.of("/etc/localtime:/etc/localtime:ro"));
        CreateContainerParam.RestartPolicySection restartPolicy = new CreateContainerParam.RestartPolicySection();
        restartPolicy.setName("unless-stopped");
        hostConfig.setRestartPolicy(restartPolicy);
        param.setHostConfig(hostConfig);
        CreateContainerRaw raw = DockerInfoHelper.createContainer("uw-ops-123", param);
        assertEquals("def4567890ab", raw.getId());
    }

    @Test
    void startContainerAccepts204And304() throws DockerApiException {
        responder = request -> {
            assertTrue(request.startsWith("POST /containers/abc123/start "), "start request: " + request);
            return "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        };
        DockerInfoHelper.startContainer("abc123");
        //304=已在运行，幂等成功。
        responder = request -> "HTTP/1.1 304 Not Modified\r\nContent-Length: 0\r\n\r\n";
        DockerInfoHelper.startContainer("abc123");
    }

    @Test
    void stopContainerPassesTimeoutParam() throws DockerApiException {
        responder = request -> {
            assertTrue(request.startsWith("POST /containers/abc123/stop?t=30 "), "stop request: " + request);
            return "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        };
        DockerInfoHelper.stopContainer("abc123", 30);
        //304=已停止，幂等成功。
        responder = request -> "HTTP/1.1 304 Not Modified\r\nContent-Length: 0\r\n\r\n";
        DockerInfoHelper.stopContainer("abc123", 30);
    }

    @Test
    void writeOperation404MappedToContainerNotFound() {
        responder = request -> "HTTP/1.1 404 Not Found\r\nContent-Length: 25\r\n\r\n{\"message\":\"No such container\"}";
        assertThrows(ContainerNotFoundException.class, () -> DockerInfoHelper.stopContainer("gone01", 30));
        assertThrows(ContainerNotFoundException.class, () -> DockerInfoHelper.removeContainer("gone01"));
    }

    @Test
    void writeOperationUnexpectedStatusFails() {
        responder = request -> "HTTP/1.1 409 Conflict\r\nContent-Length: 0\r\n\r\n";
        //同名容器已存在（409）不在 create 成功码内，异常交执行器做幂等清理。
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.createContainer("uw-ops-123", new CreateContainerParam()));
    }

    @Test
    void removeContainerSendsDeleteWithForce() throws DockerApiException {
        responder = request -> {
            assertTrue(request.startsWith("DELETE /containers/abc123?force=1&v=1 "), "remove request: " + request);
            return "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        };
        DockerInfoHelper.removeContainer("abc123");
    }

    @Test
    void illegalContainerTokenRejectedBeforeRequest() {
        //路径注入字符在发请求前即被拒绝（responder 维持 500：若真的发出请求将失败）。
        responder = request -> "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n";
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.startContainer("bad/name"));
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.stopContainer("a b", 30));
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.removeContainer("../etc"));
        assertThrows(DockerApiException.class, () -> DockerInfoHelper.createContainer("a?b", new CreateContainerParam()));
    }

    /**
     * 真实 daemon 形状回归：pids_stats.limit 对未设限额返回 uint64 最大值
     * (18446744073709551615)，直接绑 long 会 InputCoercionException 拖垮整个 stats 解析
     * （该容器性能全部 NULL）；BigInteger 承接并归一化 0=未设。
     */
    @Test
    void pidsLimitUint64MaxParsedAsUnlimited() {
        String body = "{\"pids_stats\":{\"current\":13,\"limit\":18446744073709551615}}";
        ContainerStatsRaw stats = JsonUtils.parse(body, ContainerStatsRaw.class);
        assertNotNull(stats.getPidsStats());
        assertEquals(13L, stats.getPidsStats().getCurrent());
        assertEquals(-1L, stats.getPidsStats().getLimit());
        //正常限额（小数值）原样读取。
        ContainerStatsRaw limited = JsonUtils.parse("{\"pids_stats\":{\"current\":5,\"limit\":512}}", ContainerStatsRaw.class);
        assertEquals(512L, limited.getPidsStats().getLimit());
    }

    /**
     * 镜像准备（F02）：本地已存在（GET /images/{ref}/json 200）不拉取。
     */
    @Test
    void ensureImageSkipsWhenLocalExists() throws DockerApiException {
        responder = request -> {
            assertTrue(request.startsWith("GET /images/"), "unexpected request: " + request);
            return FakeDockerDaemon.jsonResponse(200, "{\"Id\":\"sha256:abc123\"}");
        };
        DockerInfoHelper.ensureImage("registry.uw.local/app/web:1.0", "dGVzdC1jcmVk");
        //本地命中即短路：pull 端点从未被请求（否则 responder 断言失败）。
    }

    /**
     * 镜像准备（F02）：本地缺失（404）→ 带 X-Registry-Auth 头 POST /images/create（tag 引用
     * 拆分 fromImage/tag），事件流无 error 即成功。
     */
    @Test
    void ensureImagePullsWithAuthWhenMissing() throws DockerApiException {
        daemon.imagesNotExistByDefault();
        String pullBody = "{\"status\":\"Pulling from web\"}\n{\"status\":\"Download complete\"}\n";
        responder = request -> {
            if (request.startsWith("GET /images/")) {
                return FakeDockerDaemon.jsonResponse(404, "{\"message\":\"No such image\"}");
            }
            if (request.startsWith("POST /images/create")) {
                assertTrue(request.contains("fromImage="), "pull must carry fromImage: " + request);
                assertTrue(request.contains("tag=1.0"), "tag reference must split into tag param: " + request);
                assertTrue(request.contains("X-Registry-Auth: dGVzdC1jcmVk"), "pull must carry registry auth header");
                return FakeDockerDaemon.jsonResponse(200, pullBody);
            }
            throw new AssertionError("unexpected request: " + request);
        };
        DockerInfoHelper.ensureImage("registry.uw.local/app/web:1.0", "dGVzdC1jcmVk");
    }

    /**
     * 镜像准备（F02）：digest 引用整体作 fromImage（不拆 tag）；事件流含 error 即失败
     * （认证失败/tag 不存在等以 {"error":...} 行报告，HTTP 状态仍 200）。
     */
    @Test
    void pullImageFailsOnErrorEvent() {
        daemon.imagesNotExistByDefault();
        String pullBody = "{\"status\":\"Pulling\"}\n{\"errorDetail\":{\"message\":\"auth failed\"},\"error\":\"unauthorized: authentication required\"}\n";
        responder = request -> {
            if (request.startsWith("GET /images/")) {
                return FakeDockerDaemon.jsonResponse(404, "{\"message\":\"No such image\"}");
            }
            if (request.startsWith("POST /images/create")) {
                assertFalse(request.contains("tag="), "digest pull must not carry tag param: " + request);
                return FakeDockerDaemon.jsonResponse(200, pullBody);
            }
            throw new AssertionError("unexpected request: " + request);
        };
        DockerApiException ex = assertThrows(DockerApiException.class,
                () -> DockerInfoHelper.ensureImage("registry.uw.local/app/web@sha256:abc123", "dGVzdC1jcmVk"));
        assertTrue(ex.getMessage().contains("image pull failed"), "error message should locate pull failure: " + ex.getMessage());
    }
}
