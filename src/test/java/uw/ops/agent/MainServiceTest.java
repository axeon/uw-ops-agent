package uw.ops.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.common.util.JsonUtils;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.FakeDockerDaemon;
import uw.ops.agent.helper.StructuredTaskExecutor;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MainService 单测：execType 分发（SHELL 最终脚本原样执行、DOCKER 结构化执行器、升级任务自举
 * 三分支）与领取 executeDate 的分层语义（分发层 dispatchOpsTask 不改写领取值；真实开始执行时刻
 * 的覆写发生在 runOpsTask 入口，见 MainServiceTaskSchedulingTest——center 侧迟到判定为
 * "上报 executeDate >= 行内 execute_date 即接受"）另锁定任务协议字段集合（无 token/签名/租约字段）。
 * DOCKER 分支经 FakeDockerDaemon（helper 包的公共测试基建）注入临时 UDS，不出网。
 */
class MainServiceTest {

    private FakeDockerDaemon daemon;

    @BeforeEach
    void setUp() throws Exception {
        daemon = new FakeDockerDaemon();
        daemon.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        daemon.close();
    }

    /**
     * 构造 SHELL 任务（Center 已把 APP_PORT 解析为 10010，Ports 只保留分配结果）。
     */
    private OpsTask newShellTask() {
        OpsTask task = new OpsTask();
        task.setId(201L);
        task.setPlanId(1L);
        task.setContainerId(1L);
        task.setClusterId(1L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_SHELL);
        TaskData.ShellSection shell = new TaskData.ShellSection();
        shell.setScript("echo hello-10010");
        Map<String, String> ports = new HashMap<>();
        ports.put("APP_PORT", "10010");
        shell.setPorts(ports);
        taskData.setShell(shell);
        task.setTaskData(taskData);
        return task;
    }

    @Test
    void dispatchLayerDoesNotRewriteClaimedExecuteDate() throws Exception {
        //分发层不改写领取的 executeDate（真实开始执行时刻的覆写发生在 runOpsTask 入口，
        //见 MainServiceTaskSchedulingTest）；center 侧以 execute_date 比对裁决迟到结果
        //（重领后旧执行的上报不覆盖新执行）。
        Date claimed = new Date(1700000000000L);
        OpsTask task = newShellTask();
        task.setExecuteDate(claimed);
        MainService.dispatchOpsTask(task, false);
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertSame(claimed, task.getExecuteDate(), "dispatch layer must not rewrite claimed executeDate");
        //协议往返：字段名映射（无 PascalCase 注解）双向不丢失——领取响应反序列化与
        //结果上报序列化走同一路径，回传值与领取值逐字段一致。
        OpsTask roundTrip = JsonUtils.parse(JsonUtils.toString(task), OpsTask.class);
        assertEquals(claimed, roundTrip.getExecuteDate());
    }

    @Test
    void opsTaskProtocolCarriesNoTokenOrSignFields() {
        //任务协议字段集合锁定：无 claimToken/leaseUntil/taskSign/taskStamp（通道简化后删除，
        //防止协议 vo 意外回添）；executeDate 以字段名映射随载荷往返。
        OpsTask task = newShellTask();
        task.setExecuteDate(new Date(1700000000000L));
        task.setDockerId("hash01");
        String json = JsonUtils.toString(task);
        for (String banned : new String[]{"ClaimToken", "LeaseUntil", "TaskSign", "taskSign", "taskStamp"}) {
            assertFalse(json.contains(banned), "task protocol JSON must not contain " + banned + ": " + json);
        }
        assertTrue(json.contains("\"executeDate\""), "executeDate must serialize by field name: " + json);
    }

    @Test
    void dispatchShellPreservesLiteralVariableText() throws Exception {
        OpsTask task = newShellTask();
        task.getTaskData().getShell().setScript("printf '%s' 'hello-${APP_NAME}'");
        task.getTaskData().getShell().setPorts(Map.of("APP_NAME", "uw-app"));
        MainService.dispatchOpsTask(task, false);
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("hello-${APP_NAME}", task.getTaskResult());
        assertEquals("printf '%s' 'hello-${APP_NAME}'", task.getTaskData().getShell().getScript());
    }

    @Test
    void resolvedPortRemainsCenterAllocationEvenWhenLocallyOccupied() throws Exception {
        //已分配端口不可自行递增，原脚本使用的端口和Center台账必须一致。
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            OpsTask task = newShellTask();
            task.getTaskData().getShell().setScript("echo hello-" + port);
            task.getTaskData().getShell().setPorts(Map.of("APP_PORT", String.valueOf(port)));
            MainService.dispatchOpsTask(task, false);
            assertEquals(TaskState.SUCCEED.getValue(), task.getState());
            assertEquals("hello-" + port, task.getTaskResult());
            assertEquals(String.valueOf(port), task.getTaskData().getShell().getPorts().get("APP_PORT"));
            assertEquals("echo hello-" + port, task.getTaskData().getShell().getScript());
        }
    }

    @Test
    void alreadyRenderedSecretContainingPortTokenIsExecutedUnchanged(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("actual-secret.txt");
        OpsTask task = newShellTask();
        task.getTaskData().setSensitiveOutput(true);
        String secret = "literal-${APP_PORT}-secret";
        String script = "printf '%s' '" + secret + "' > '" + output.toString().replace("'", "'\"'\"'") + "'";
        task.getTaskData().getShell().setScript(script);
        task.getTaskData().getShell().setPorts(new HashMap<>(Map.of("APP_PORT", "19000")));
        MainService.dispatchOpsTask(task, false);
        assertEquals(secret, Files.readString(output), "secret bytes must survive the real shell execution");
        assertEquals(script, task.getTaskData().getShell().getScript());
        assertEquals("19000", task.getTaskData().getShell().getPorts().get("APP_PORT"));
        assertEquals("result withheld: task uses secrets.", task.getTaskResult());
    }

    @Test
    void sensitiveScriptOutputIsWithheld() throws Exception {
        OpsTask task = newShellTask();
        task.getTaskData().setSensitiveOutput(true);
        task.getTaskData().getShell().setScript("printf synthetic-output-secret");
        MainService.dispatchOpsTask(task, false);
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("result withheld: task uses secrets.", task.getTaskResult());
    }

    @Test
    void dispatchShellRejectsDangerousScript() {
        OpsTask task = newShellTask();
        task.getTaskData().getShell().setScript("rm -rf /");
        task.getTaskData().getShell().setPorts(null);
        assertThrows(SecurityException.class, () -> MainService.dispatchOpsTask(task, false));
        assertNotEquals(TaskState.SUCCEED.getValue(), task.getState());
    }

    @Test
    void dispatchShellRejectsMissingScript() {
        //SHELL 载荷缺脚本（Shell 段或 Script 为空）：显式拒绝，不落到 /bin/sh -c null 的晦默 NPE。
        OpsTask task = newShellTask();
        task.getTaskData().getShell().setScript(null);
        Exception error = assertThrows(IllegalArgumentException.class, () -> MainService.dispatchOpsTask(task, false));
        assertTrue(error.getMessage().contains("shell.script missing"), error.getMessage());

        OpsTask noSection = newShellTask();
        noSection.getTaskData().setShell(null);
        assertThrows(IllegalArgumentException.class, () -> MainService.dispatchOpsTask(noSection, false));
    }

    @Test
    void dispatchDockerRoutesToStructuredExecutor() throws Exception {
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
                        "def456", "/uw-ops-123", "registry.example.com/uw-x@sha256:abc", true, null, null));
            }
            return FakeDockerDaemon.emptyResponse(500);
        });
        OpsTask task = new OpsTask();
        task.setId(202L);
        task.setPlanId(11L);
        task.setContainerId(123L);
        task.setClusterId(1L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_DOCKER);
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setAction("start");
        docker.setImage("registry.example.com/uw-x@sha256:abc");
        docker.setContainerName("uw-ops-123");
        docker.setNetworkMode("host");
        taskData.setContainer(docker);
        task.setTaskData(taskData);

        MainService.dispatchOpsTask(task, false);
        //DOCKER 分支走结构化执行器：create+start+等待 running，dockerId 回填。
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("def456", task.getDockerId());
        assertTrue(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /containers/create")));
    }

    @Test
    void dispatchUpgradeTaskRunsShellBootstrap() throws Exception {
        OpsTask task = new OpsTask();
        task.setId(203L);
        //升级任务三 id 全 0，SHELL 自举路径（独立 5 分钟超时）。
        task.setPlanId(0L);
        task.setContainerId(0L);
        task.setClusterId(0L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_SHELL);
        TaskData.ShellSection shell = new TaskData.ShellSection();
        shell.setScript("echo upgrade-ok");
        taskData.setShell(shell);
        task.setTaskData(taskData);

        MainService.dispatchOpsTask(task, true);
        assertEquals(TaskState.SUCCEED.getValue(), task.getState());
        assertEquals("upgrade-ok", task.getTaskResult());
    }

    @Test
    void dispatchUnknownExecTypeRejected() {
        OpsTask task = newShellTask();
        task.getTaskData().setExecType("FOO");
        assertThrows(IllegalArgumentException.class, () -> MainService.dispatchOpsTask(task, false));

        //taskData 缺失同样拒绝（不静默走 shell）。
        OpsTask noTaskData = newShellTask();
        noTaskData.setTaskData(null);
        assertThrows(IllegalArgumentException.class, () -> MainService.dispatchOpsTask(noTaskData, false));
    }
}
