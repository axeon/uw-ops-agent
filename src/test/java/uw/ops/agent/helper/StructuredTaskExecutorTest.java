package uw.ops.agent.helper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uw.common.util.JsonUtils;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.vo.CreateContainerParam;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;
import uw.ops.agent.vo.sub.ContainerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StructuredTaskExecutor 单测（自环 UDS 假 daemon 模式）。
 * 覆盖：start 全链（新建/幂等命中/清残留重建/Env 无 TZ 注入）、stop（404 幂等/正常 stop+rm/
 * remove 失败后重跑补删/名字兜底）、RestartPolicy 解析、Extra 合并固定字段优先、失败脱敏
 * （taskError 不含 env 值）。
 */
class StructuredTaskExecutorTest {

    @Test
    void failedStopKeepsProbeForStillRunningContainer() {
        ReadinessProber.cacheStartScript("hash01", 123, "exit 0");
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/hash01/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "hash01", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStopTask();
        assertThrows(Exception.class, () -> StructuredTaskExecutor.execute(task, task.getTaskData()));
        assertTrue(ReadinessProber.size() > 0, "taskData probe entry registered");
        ReadinessProber.removeContainer("hash01");
    }

    private static final String IMAGE = "registry.example.com/uw-x@sha256:abc123";

    private FakeDockerDaemon daemon;

    @BeforeEach
    void startDaemon() throws Exception {
        daemon = new FakeDockerDaemon();
        daemon.start();
        //时区注入源固定（测试机可能无 /etc/timezone，保证 TZ 断言确定性）。
        StructuredTaskExecutor.hostTimezoneSupplier = () -> "Asia/Shanghai";
        //端口校验默认 no-op：现有断言按 taskData 原值确定；真实校验链路由专门测试覆盖。
        StructuredTaskExecutor.portVerifier = docker -> {
        };
    }

    @AfterEach
    void stopDaemon() throws Exception {
        StructuredTaskExecutor.hostTimezoneSupplier = StructuredTaskExecutor::readHostTimezoneFile;
        StructuredTaskExecutor.portVerifier = StructuredTaskExecutor.NATIVE_PORT_VERIFIER;
        daemon.close();
    }

    /**
     * 构造 start 任务（容器 123、镜像 digest、host 网络、unless-stopped、内存/CPU/挂载/端口全集）。
     */
    private OpsTask newStartTask() {
        OpsTask task = new OpsTask();
        task.setId(101L);
        task.setPlanId(11L);
        task.setContainerId(123L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_DOCKER);
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setAction("start");
        docker.setImage(IMAGE);
        docker.setContainerName("uw-ops-123");
        docker.setEnv(new ArrayList<>(List.of("APP_PORT=10010")));
        docker.setMounts(List.of("/etc/localtime:/etc/localtime:ro"));
        docker.setNetworkMode("host");
        docker.setRestartPolicy("unless-stopped");
        docker.setHostPorts(Map.of("APP_PORT", 10010));
        docker.setContainerMem(2048L);
        docker.setNanoCpus(2_000_000_000L);
        taskData.setContainer(docker);
        task.setTaskData(taskData);
        return task;
    }

    /**
     * 构造 stop 任务（hash 定位 + 名字兜底材料）。
     */
    private OpsTask newStopTask() {
        OpsTask task = new OpsTask();
        task.setId(102L);
        task.setPlanId(11L);
        task.setContainerId(123L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_DOCKER);
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setAction("stop");
        docker.setContainerName("uw-ops-123");
        docker.setTargetContainerHash("hash01");
        taskData.setContainer(docker);
        task.setTaskData(taskData);
        return task;
    }

    /**
     * 判定请求序列中是否出现指定方法+路径前缀的请求。
     */
    private boolean hasRequest(String methodAndPathPrefix) {
        return daemon.requests().stream().anyMatch(r -> r.startsWith(methodAndPathPrefix));
    }

    /**
     * 提取请求序列中首个匹配请求原文。
     */
    private String firstRequest(String methodAndPathPrefix) {
        return daemon.requests().stream().filter(r -> r.startsWith(methodAndPathPrefix)).findFirst().orElse("");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void startFullChainCreatesAndWaitsRunning(boolean sensitive) throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[]");
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"def456\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/def456/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/def456/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "def456", "/uw-ops-123", IMAGE, true, null, Map.of("uw.ops.container-id", "123")));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        task.getTaskData().setSensitiveOutput(sensitive);
        StructuredTaskExecutor.execute(task, task.getTaskData());

        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("def456", task.getDockerId());
        assertEquals("APP_PORT=10010\n", task.getActualPorts());
        assertTrue(task.getTaskResult().contains(IMAGE) && task.getTaskResult().contains("uw-ops-123"), task.getTaskResult());

        //请求序列：inventory 定位 → create → start → inspect 等 running。
        String createRequest = firstRequest("POST /containers/create");
        assertEquals(sensitive, createRequest.contains("\"uw.ops.sensitive\":\"true\""));
        assertTrue(createRequest.startsWith("POST /containers/create?name=uw-ops-123 "), createRequest);
        //请求体全集断言：镜像/Labels/Env(无 TZ 条目时注入主机时区)/NetworkMode/RestartPolicy/Memory(MB→字节)/NanoCpus/Binds/PortBindings。
        assertTrue(createRequest.contains("\"Image\":\"" + IMAGE + "\""), createRequest);
        assertTrue(createRequest.contains("\"uw.ops.plan-id\":\"11\"") && createRequest.contains("\"uw.ops.container-id\":\"123\""), createRequest);
        assertTrue(createRequest.contains("\"Env\":[\"APP_PORT=10010\",\"TZ=Asia/Shanghai\"]"), createRequest);
        assertTrue(createRequest.contains("\"NetworkMode\":\"host\""), createRequest);
        assertTrue(createRequest.contains("\"Name\":\"unless-stopped\""), createRequest);
        assertTrue(createRequest.contains("\"Memory\":2147483648"), createRequest);
        assertTrue(createRequest.contains("\"NanoCpus\":2000000000"), createRequest);
        assertTrue(createRequest.contains("\"Binds\":[\"/etc/localtime:/etc/localtime:ro\"]"), createRequest);
        //PortBindings 键=容器端口/协议（数字，host 模型下容器端口=分配值），误用端口名会被 daemon 400 拒绝。
        assertTrue(createRequest.contains("\"PortBindings\":{\"10010/tcp\":[{\"HostPort\":\"10010\"}]}"), createRequest);
        //全新建链路不应有 stop/remove。
        assertFalse(hasRequest("POST /containers/def456/stop"), "no stop expected");
        assertFalse(hasRequest("DELETE /containers/"), "no remove expected");
    }

    @Test
    void startEnvWithTzKeepsOriginal() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[]");
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"def456\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/def456/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/def456/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "def456", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        task.getTaskData().getContainer().setEnv(new ArrayList<>(List.of("TZ=UTC", "APP_PORT=10010")));
        StructuredTaskExecutor.execute(task, task.getTaskData());
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        //已有 TZ 条目：不重复注入，原值保持。
        String createRequest = firstRequest("POST /containers/create");
        assertTrue(createRequest.contains("\"Env\":[\"TZ=UTC\",\"APP_PORT=10010\"]"), createRequest);
        assertEquals(1, createRequest.split("TZ=", -1).length - 1, "single TZ entry expected");
    }

    /**
     * 本机端口校验：分配端口被非受管进程占用（bind 失败，center 台账不可见）时，
     * 从分配值递增至下一个空闲端口——PortBindings 与 actualPorts 回程均用实际值
     * （center 台账比对不符告警，端口偏移运维可见）。
     */
    @Test
    void startShiftsOccupiedHostPortToNextUsable() throws Exception {
        //真实校验链路（默认接缝为 no-op，本测试显式启用）。
        StructuredTaskExecutor.portVerifier = StructuredTaskExecutor.NATIVE_PORT_VERIFIER;
        //占住一个随机端口模拟非受管占用（原生 ServerSocket，不经 NetworkUtils、不污染探测缓存）。
        try (ServerSocket occupied = new ServerSocket(0)) {
            int assigned = occupied.getLocalPort();
            daemon.setResponder(request -> {
                if (request.startsWith("GET /containers/json")) {
                    return FakeDockerDaemon.jsonResponse(200, "[]");
                }
                if (request.startsWith("POST /containers/create")) {
                    return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"shift001\",\"Warnings\":[]}");
                }
                if (request.startsWith("POST /containers/shift001/start")) {
                    return FakeDockerDaemon.emptyResponse(204);
                }
                if (request.startsWith("GET /containers/shift001/json")) {
                    return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                            "shift001", "/uw-ops-123", IMAGE, true, null, null));
                }
                return FakeDockerDaemon.emptyResponse(500);
            });
            OpsTask task = newStartTask();
            task.getTaskData().getContainer().setHostPorts(Map.of("APP_PORT", assigned));
            StructuredTaskExecutor.execute(task, task.getTaskData());
            String createRequest = firstRequest("POST /containers/create");
            java.util.regex.Matcher hostPort = java.util.regex.Pattern
                    .compile("\"HostPort\":\"(\\d+)\"").matcher(createRequest);
            assertTrue(hostPort.find(), "create must carry HostPort: " + createRequest);
            int actualPort = Integer.parseInt(hostPort.group(1));
            assertTrue(actualPort > assigned, "occupied port must shift up: assigned=" + assigned + ", actual=" + actualPort);
            assertEquals("APP_PORT=" + actualPort + "\n", task.getActualPorts());
            assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        }
    }

    @Test
    void startIdempotentHitSkipsCreate() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[{\"Id\":\"abc999\",\"Names\":[\"/uw-ops-123\"],"
                        + "\"Image\":\"img:1\",\"State\":\"running\",\"Labels\":{\"uw.ops.container-id\":\"123\"}}]");
            }
            if (request.startsWith("GET /containers/abc999/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "abc999", "/uw-ops-123", IMAGE, true, null, Map.of("uw.ops.container-id", "123")));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        //命中且镜像一致、running：直接成功并上报该 Id，重放不产生第二个容器。
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("abc999", task.getDockerId());
        assertFalse(hasRequest("POST /containers/create"), "create must be skipped on idempotent hit");
        assertFalse(hasRequest("POST /containers/abc999/stop"), "no stop expected");
    }

    @Test
    void startCleanRunningResidueThenRecreate() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[{\"Id\":\"old777\",\"Names\":[\"/uw-ops-123\"],"
                        + "\"Image\":\"img:1\",\"State\":\"running\",\"Labels\":{\"uw.ops.container-id\":\"123\"}}]");
            }
            if (request.startsWith("GET /containers/old777/json")) {
                //镜像不符且 running：stop(30s)+remove 后重建。
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "old777", "/uw-ops-123", "registry.example.com/uw-x@sha256:OLD", true, null,
                        Map.of("uw.ops.container-id", "123")));
            }
            if (request.startsWith("POST /containers/old777/stop")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("DELETE /containers/old777")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"new888\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/new888/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/new888/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "new888", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("new888", task.getDockerId());
        //残留清理：stop 带 30s 宽限、remove 强制删，再走 create+start。
        assertTrue(hasRequest("POST /containers/old777/stop?t=30 "), "stop residue with grace period");
        assertTrue(hasRequest("DELETE /containers/old777?force=1&v=1 "), "remove residue with force");
        assertTrue(hasRequest("POST /containers/create"));
    }

    @Test
    void startNameLocatedForeignContainerReclaimed() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                //无 uw.ops label 的同名容器（按名兜底定位）：镜像一致也不算幂等命中。
                return FakeDockerDaemon.jsonResponse(200, "[{\"Id\":\"foreign55\",\"Names\":[\"/uw-ops-123\"],"
                        + "\"Image\":\"img:1\",\"State\":\"running\"}]");
            }
            if (request.startsWith("GET /containers/foreign55/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "foreign55", "/uw-ops-123", IMAGE, true, null, null));
            }
            if (request.startsWith("POST /containers/foreign55/stop")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("DELETE /containers/foreign55")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"new888\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/new888/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/new888/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "new888", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        //按名兜底定位的无 label 异物：即使镜像一致也回收重建（labels 与目标不符不算幂等命中）。
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("new888", task.getDockerId());
        assertTrue(hasRequest("POST /containers/foreign55/stop?t=30 "), "foreign same-name container must be reclaimed");
        assertTrue(hasRequest("DELETE /containers/foreign55?force=1&v=1 "));
        assertTrue(hasRequest("POST /containers/create"));
    }

    @Test
    void startCleanExitedResidueWithoutStop() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[{\"Id\":\"dead666\",\"Names\":[\"/uw-ops-123\"],"
                        + "\"Image\":\"img:1\",\"State\":\"exited\",\"Labels\":{\"uw.ops.container-id\":\"123\"}}]");
            }
            if (request.startsWith("GET /containers/dead666/json")) {
                //已退出：不 stop 直接 remove。
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "dead666", "/uw-ops-123", IMAGE, false, 137L, Map.of("uw.ops.container-id", "123")));
            }
            if (request.startsWith("DELETE /containers/dead666")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"new888\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/new888/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/new888/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "new888", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("new888", task.getDockerId());
        assertFalse(hasRequest("POST /containers/dead666/stop"), "exited residue needs no stop");
        assertTrue(hasRequest("DELETE /containers/dead666?force=1&v=1 "));
    }

    @Test
    void startExtraMergedWithFixedFieldPriority() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[]");
            }
            if (request.startsWith("POST /containers/create")) {
                return FakeDockerDaemon.jsonResponse(201, "{\"Id\":\"def456\",\"Warnings\":[]}");
            }
            if (request.startsWith("POST /containers/def456/start")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/def456/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "def456", "/uw-ops-123", IMAGE, true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        //Extra 同名键（NetworkMode/Memory）试图覆盖固定字段，另带 CapAdd/Ulimits 补充字段。
        task.getTaskData().getContainer().setExtra(JsonUtils.parse(
                "{\"NetworkMode\":\"bridge\",\"Memory\":111,\"CapAdd\":[\"NET_ADMIN\"],\"Ulimits\":[]}", JsonNode.class));
        StructuredTaskExecutor.execute(task, task.getTaskData());

        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        String createRequest = firstRequest("POST /containers/create");
        //固定字段优先：host 网络与调度内存不被 Extra 覆盖；Extra 补充字段透传。
        assertTrue(createRequest.contains("\"NetworkMode\":\"host\""), createRequest);
        assertTrue(createRequest.contains("\"Memory\":2147483648"), createRequest);
        assertTrue(createRequest.contains("\"CapAdd\":[\"NET_ADMIN\"]"), createRequest);
        assertTrue(createRequest.contains("\"Ulimits\":[]"), createRequest);
        assertFalse(createRequest.contains("\"NetworkMode\":\"bridge\""), createRequest);
        assertFalse(createRequest.contains("\"Memory\":111,"), createRequest);
    }

    @Test
    void stopTargetGoneSucceedsIdempotently() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/")) {
                //hash 与容器名都查不到：直接幂等成功。
                return FakeDockerDaemon.jsonResponse(404, "{\"message\":\"No such container\"}");
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStopTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        //容器已不存在：直接成功（幂等），不发 stop。
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("hash01", task.getDockerId());
        assertTrue(task.getTaskResult().contains("already gone"), task.getTaskResult());
        assertFalse(hasRequest("POST /containers/hash01/stop"), "no stop for gone container");
        assertFalse(hasRequest("DELETE /containers/"), "no remove for gone container");
    }

    @Test
    void stopNormalStopsRemovesAndReportsExitCode() throws Exception {
        //状态化响应：stop 之后的 inspect 返回 exited/3（remove 前的退出码数据源）。
        AtomicBoolean stopped = new AtomicBoolean(false);
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/hash01/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "hash01", "/uw-ops-123", IMAGE, !stopped.get(), 3L, Map.of("uw.ops.container-id", "123")));
            }
            if (request.startsWith("POST /containers/hash01/stop")) {
                stopped.set(true);
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("DELETE /containers/hash01")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStopTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        //stop+rm 全链成功才 SUCCEED：stop?t=30 → 复核退出码 → remove(force+v)。
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("hash01", task.getDockerId());
        assertTrue(task.getTaskResult().contains("stopped and removed"), task.getTaskResult());
        assertTrue(task.getTaskResult().contains("exitCode=3"), task.getTaskResult());
        assertTrue(hasRequest("POST /containers/hash01/stop?t=30 "), "stop with grace period");
        assertTrue(hasRequest("DELETE /containers/hash01?force=1&v=1 "), "stop must remove container");

        //重放（容器已删）：hash 定位 404 → 直接幂等成功，不再发 stop/remove。
        daemon.setResponder(request -> FakeDockerDaemon.jsonResponse(404, "{\"message\":\"No such container\"}"));
        OpsTask replay = newStopTask();
        StructuredTaskExecutor.execute(replay, replay.getTaskData());
        assertEquals(TaskState.SUCCEED.getValue(), replay.getState());
        assertEquals("hash01", replay.getDockerId());
        assertTrue(replay.getTaskResult().contains("already gone"), replay.getTaskResult());
    }

    @Test
    void stopRemoveFailureFailsThenRerunCompletes() throws Exception {
        //首轮：stop 成功但 remove 500 → 任务失败，taskError 摘要注明 stopped-but-remove-failed。
        AtomicBoolean stopped = new AtomicBoolean(false);
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/hash01/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "hash01", "/uw-ops-123", IMAGE, !stopped.get(), 3L, Map.of("uw.ops.container-id", "123")));
            }
            if (request.startsWith("POST /containers/hash01/stop")) {
                stopped.set(true);
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("DELETE /containers/hash01")) {
                return FakeDockerDaemon.emptyResponse(500);
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStopTask();
        Exception error = assertThrows(Exception.class, () -> StructuredTaskExecutor.execute(task, task.getTaskData()));
        assertTrue(error.getMessage().contains("stopped but remove failed"), error.getMessage());
        assertTrue(error.getMessage().contains("exitCode=3"), error.getMessage());
        assertFalse(error.getMessage().contains("uw-ops-123"), error.getMessage());

        //重跑：容器仍在（已停）→ stop 304 幂等成功 → 补删 204 → 全链成功。
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/hash01/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "hash01", "/uw-ops-123", IMAGE, false, 3L, Map.of("uw.ops.container-id", "123")));
            }
            if (request.startsWith("POST /containers/hash01/stop")) {
                return FakeDockerDaemon.emptyResponse(304);
            }
            if (request.startsWith("DELETE /containers/hash01")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask rerun = newStopTask();
        StructuredTaskExecutor.execute(rerun, rerun.getTaskData());
        assertEquals(TaskState.SUCCEED.getValue(), rerun.getState());
        assertEquals("hash01", rerun.getDockerId());
        assertTrue(rerun.getTaskResult().contains("stopped and removed"), rerun.getTaskResult());
        assertTrue(rerun.getTaskResult().contains("exitCode=3"), rerun.getTaskResult());
    }

    @Test
    void stopFallsBackToContainerName() throws Exception {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/hash01/json")) {
                //hash 定位失败（404）→ 按名兜底。
                return FakeDockerDaemon.jsonResponse(404, "{\"message\":\"No such container\"}");
            }
            if (request.startsWith("GET /containers/uw-ops-123/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "name777", "/uw-ops-123", IMAGE, true, null, null));
            }
            if (request.startsWith("POST /containers/name777/stop")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            if (request.startsWith("GET /containers/name777/json")) {
                return FakeDockerDaemon.jsonResponse(200, FakeDockerDaemon.inspectBody(
                        "name777", "/uw-ops-123", IMAGE, false, 0L, null));
            }
            if (request.startsWith("DELETE /containers/name777")) {
                return FakeDockerDaemon.emptyResponse(204);
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStopTask();
        StructuredTaskExecutor.execute(task, task.getTaskData());

        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("name777", task.getDockerId());
        assertTrue(hasRequest("POST /containers/name777/stop?t=30 "), "stop by name-located id");
        assertTrue(hasRequest("DELETE /containers/name777?force=1&v=1 "), "stop must remove container");
    }

    @Test
    void restartPolicyParsingVariants() {
        CreateContainerParam.RestartPolicySection plain = StructuredTaskExecutor.parseRestartPolicy("unless-stopped");
        assertEquals("unless-stopped", plain.getName());
        assertNull(plain.getMaximumRetryCount());

        CreateContainerParam.RestartPolicySection always = StructuredTaskExecutor.parseRestartPolicy("always");
        assertEquals("always", always.getName());

        CreateContainerParam.RestartPolicySection onFailure = StructuredTaskExecutor.parseRestartPolicy("on-failure:5");
        assertEquals("on-failure", onFailure.getName());
        assertEquals(5, onFailure.getMaximumRetryCount());

        CreateContainerParam.RestartPolicySection onFailureNoRetry = StructuredTaskExecutor.parseRestartPolicy("on-failure");
        assertEquals("on-failure", onFailureNoRetry.getName());
        assertNull(onFailureNoRetry.getMaximumRetryCount());

        assertNull(StructuredTaskExecutor.parseRestartPolicy("  "));
        assertNull(StructuredTaskExecutor.parseRestartPolicy(null));

        assertThrows(IllegalArgumentException.class, () -> StructuredTaskExecutor.parseRestartPolicy("on-failure:x"));
    }

    @Test
    void mergeExtraOnlyFillsUnsetFields() {
        CreateContainerParam.HostConfigSection fixed = new CreateContainerParam.HostConfigSection();
        fixed.setNetworkMode("host");
        fixed.setMemory(1024L * 1024 * 1024);
        //未设 Extra：原样返回。
        assertSame(fixed, StructuredTaskExecutor.mergeExtra(fixed, null));
        assertSame(fixed, StructuredTaskExecutor.mergeExtra(fixed, JsonUtils.parse("null", JsonNode.class)));

        CreateContainerParam.HostConfigSection merged = StructuredTaskExecutor.mergeExtra(fixed,
                JsonUtils.parse("{\"NetworkMode\":\"bridge\",\"Memory\":1,\"CapAdd\":[\"NET_ADMIN\"],\"PidsLimit\":100}", JsonNode.class));
        assertEquals("host", merged.getNetworkMode());
        assertEquals(1024L * 1024 * 1024, merged.getMemory());
        assertEquals(List.of("NET_ADMIN"), merged.getCapAdd());
        assertEquals(100L, merged.getPidsLimit());

        //非对象 Extra：拒绝。
        assertThrows(IllegalArgumentException.class, () -> StructuredTaskExecutor.mergeExtra(fixed,
                JsonUtils.parse("[1,2]", JsonNode.class)));
    }

    @Test
    void failureSummaryNeverLeaksEnvValues() {
        daemon.setResponder(request -> {
            if (request.startsWith("GET /containers/json")) {
                return FakeDockerDaemon.jsonResponse(200, "[]");
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = newStartTask();
        task.getTaskData().getContainer().setEnv(new ArrayList<>(List.of("NACOS_PASSWORD=super-secret-value", "APP_PORT=10010")));
        Exception error = assertThrows(Exception.class, () -> StructuredTaskExecutor.execute(task, task.getTaskData()));
        //对外 taskError 固定英文摘要：不含 env 值（脱敏规范），也不含 daemon 响应细节。
        assertFalse(error.getMessage().contains("super-secret-value"), error.getMessage());
        assertFalse(error.getMessage().contains("NACOS_PASSWORD"), error.getMessage());
        assertTrue(error.getMessage().startsWith("docker task failed:"), error.getMessage());
    }

    @Test
    void unsupportedActionRejected() {
        OpsTask task = newStartTask();
        task.getTaskData().getContainer().setAction("restart");
        Exception error = assertThrows(Exception.class, () -> StructuredTaskExecutor.execute(task, task.getTaskData()));
        assertTrue(error.getMessage().contains("docker task failed"), error.getMessage());

        //Docker 段缺失：直接拒绝（不进入执行流）。
        OpsTask noContainerSection = new OpsTask();
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_DOCKER);
        noContainerSection.setTaskData(taskData);
        assertThrows(IllegalArgumentException.class, () -> StructuredTaskExecutor.execute(noContainerSection, taskData));
    }
}
