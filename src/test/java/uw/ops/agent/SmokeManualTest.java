package uw.ops.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.core.type.TypeReference;
import uw.common.response.ResponseData;
import uw.common.util.JsonUtils;
import uw.common.util.SystemClock;
import uw.ops.agent.api.OpsAgentApi;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.DockerInfoHelper;
import uw.ops.agent.helper.ReadinessProber;
import uw.ops.agent.helper.StructuredTaskExecutor;
import uw.ops.agent.helper.SystemInfoHelper;
import uw.ops.agent.helper.vo.ContainerInspectRaw;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;
import uw.ops.agent.vo.sub.ContainerInfo;
import uw.ops.agent.vo.sub.CpuStats;
import uw.ops.agent.vo.sub.LoadStats;
import uw.ops.agent.vo.sub.MemStats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器操作 API 化端到端冒烟（真实环境手动执行，平时跳过）。
 * <p>
 * 触发条件：系统属性 -Dsmoke.e2e=true（CI/日常测试不激活，等价 @Disabled 语义）。
 * <p>
 * 前置环境（JVM 启动前准备）：
 * <ul>
 *   <li>uw-ops-center 以 profile=debug 在本机启动（默认 http://127.0.0.1:1000，可用
 *       OPS_CENTER_HOST 覆盖），连 dev 库；</li>
 *   <li>主机行已 SQL 置为 state=1(AUDIT) 且写入固定测试 secret（secret_delivered=0）；</li>
 *   <li>Docker Desktop 已启动；macOS 下 socket 在 ~/.docker/run/docker.sock，需设置
 *       OPS_DOCKER_SOCK 指向该路径（FALLBACK_SOCKETS 只有 Linux FHS 路径）；</li>
 *   <li>Maven 传 -Dtest.agent.secret-file=/tmp/uw-ops-smoke/agent.secret 覆盖测试默认路径，避免污染
 *       /etc/uw-ops-agent/agent.secret；冒烟镜像已 docker tag 为
 *       192.168.88.21:5000/uw-auth-center:6.1.0（本地存在即不拉远程）。</li>
 * </ul>
 * <p>
 * 步骤方法（按序单独跑，bash 编排 SQL 与 center 冒烟端点）：
 * <ol>
 *   <li>{@link #step1_uploadHostInfo()}：注册主机（复用生产 OpsAgentApi/HostInfo 协议）；</li>
 *   <li>{@link #step2_uploadHostStats()}：首调下发 secret、次调真实入库指标窗口
 *       （deployPlan 需要 90s 内的 ops_host_stats 行）；</li>
 *   <li>{@link #step3_claimExecuteReport()}：领取任务→StructuredTaskExecutor 真实 create/start→
 *       docker inspect 断言→上报回填（executeDate 原样回传领取值）；</li>
 *   <li>{@link #step4_stopChain()}：领取 STOP 任务→真实 stop+remove→上报（可选加分链路）。</li>
 * </ol>
 * hostHash 默认取本机真实标识（SystemInfoHelper.getHostHash），可用 -Dsmoke.hostHash 覆盖；
 * 各步把关键产物写入 /tmp/uw-ops-smoke/（hostHash.txt/claimed-task-*.json）供 bash 断言与清理。
 */
@EnabledIfSystemProperty(named = "smoke.e2e", matches = "true")
class SmokeManualTest {

    /**
     * 冒烟产物目录（hostHash、领取的任务 JSON）。
     */
    private static final Path SMOKE_DIR = Paths.get("/tmp/uw-ops-smoke");

    /**
     * 冒烟主机标识（-Dsmoke.hostHash 可覆盖，默认本机真实标识）。
     */
    private static String hostHash;

    @BeforeAll
    static void init() throws Exception {
        Files.createDirectories(SMOKE_DIR);
        hostHash = System.getProperty("smoke.hostHash", SystemInfoHelper.getHostHash());
        Files.writeString(SMOKE_DIR.resolve("hostHash.txt"), hostHash, StandardCharsets.UTF_8);
        //Docker 客户端初始化（生产 MainService 启动动作）。
        DockerInfoHelper.negotiateApiVersion();
    }

    /**
     * 生产通道前置：上报主机信息（OpsAgentApi.hostHash 静态字段随之就绪）。
     * HostInfo 用真实 SystemInfoHelper 采集，仅 hostHash 覆盖为冒烟标识。
     */
    private static void uploadHostInfoViaAgentApi() throws Exception {
        HostInfo hostInfo = SystemInfoHelper.buildHostInfo();
        hostInfo.setHostHash(hostHash);
        OpsAgentApi.uploadHostInfo(hostInfo);
    }

    /**
     * 最小可部署 HostStats 窗口：deployPlan 的 calcHostScore 需要 memUsable&gt;containerMem
     * 且 load 不过载；containerInfoList=null（inventory 未采集语义，不触碰任何容器台账）。
     */
    private static HostStats minimalHostStats() {
        HostStats stats = new HostStats();
        stats.setHostHash(hostHash);
        stats.setStatsDate(new Date());
        MemStats mem = new MemStats();
        mem.setTotal(34_359_738_368L);
        mem.setUsable(16_106_127_360L);
        stats.setMemStats(mem);
        LoadStats load = new LoadStats();
        load.setLoad1m(1.2);
        load.setLoad5m(1.5);
        load.setLoad15m(1.8);
        stats.setLoadStats(load);
        CpuStats cpu = new CpuStats();
        cpu.setUser(5.0);
        cpu.setSys(2.0);
        cpu.setIdle(92.0);
        stats.setCpuStats(cpu);
        return stats;
    }

    /**
     * 步骤1：上传宿主机信息（注册前接口，无鉴权）。
     */
    @Test
    void step1_uploadHostInfo() throws Exception {
        uploadHostInfoViaAgentApi();
        System.out.println("[smoke] step1 uploadHostInfo OK, hostHash=" + hostHash);
    }

    /**
     * 步骤2：上传主机统计窗口。首调触发 secret 一次性下发（X-Agent-Secret 落盘
     * SecretStore），次调带签名真实入库——deployPlan 依赖 90s 内的 ops_host_stats 行。
     */
    @Test
    void step2_uploadHostStats() throws Exception {
        uploadHostInfoViaAgentApi();
        HostStats payload = minimalHostStats();
        OpsAgentApi.uploadHostStats(payload);
        OpsAgentApi.uploadHostStats(payload);
        System.out.println("[smoke] step2 uploadHostStats OK (secret delivered + stats ingested), hostHash=" + hostHash);
    }

    /**
     * 步骤3：领取→执行→上报（START 任务全链）。
     * <p>
     * 领取支持两种方式：默认走生产 OpsAgentApi.getTaskList；共享 dev 库存在旧版 center
     * 抢消费 INIT 任务时，用 -Dsmoke.claimFile=&lt;file&gt; 载入 bash 签名 curl 抢先领取的
     * 响应 JSON（领取即 CAS INIT→STARTING，旧消费方只取 INIT，竞争天然屏蔽）。
     * <p>
     * 断言：单条任务、executeDate 随领取下发且<b>原样回传</b>（不覆写——行内值与回传值同源，
     * 比对不依赖跨机墙钟）、Env 已注入 secret 条目、真实容器 running 且 labels/Env 正确、
     * 上报后 state=SUCCEED。
     */
    @Test
    void step3_claimExecuteReport() throws Exception {
        uploadHostInfoViaAgentApi();
        OpsTask task;
        String claimFile = System.getProperty("smoke.claimFile");
        if (claimFile != null) {
            //载入 bash 签名 curl 已领取的响应（领取事务已在服务端完成）。
            ResponseData<List<OpsTask>> claimed = JsonUtils.parse(
                    Files.readString(Path.of(claimFile), StandardCharsets.UTF_8),
                    new TypeReference<ResponseData<List<OpsTask>>>() {
                    });
            assertTrue(claimed.isSuccess(), "载入的领取响应应成功: " + claimed.getMsg());
            assertNotNull(claimed.getData());
            assertEquals(1, claimed.getData().size(), "载入的领取响应应恰为一条任务");
            task = claimed.getData().get(0);
            //注：下发协议不携带 hostHash（任务行该列在 agent 上报时才回填），上报前由
            // OpsAgentApi.reportTaskResult 以本地 hostHash 填充，此处无需断言。
        } else {
            task = claimOneTask("start");
        }
        //===== 协议断言（领取响应）=====
        assertNotNull(task.getExecuteDate(), "executeDate 应随领取下发（center 侧迟到结果裁决依据）");
        TaskData.ContainerSection docker = task.getTaskData().getContainer();
        assertNotNull(docker, "DOCKER 任务应有 Docker 段");
        assertTrue(containsEnv(docker.getEnv(), "NACOS_PASSWORD="), "领取版 Env 应含 secret 条目 NACOS_PASSWORD（领取注入）: " + docker.getEnv());
        assertTrue(containsEnv(docker.getEnv(), "LOG_ES_PASSWORD="), "领取版 Env 应含 secret 条目 LOG_ES_PASSWORD（领取注入）");
        assertTrue(containsEnv(docker.getEnv(), "NACOS_SERVER="), "领取版 Env 应含 NACOS_SERVER");
        assertNotNull(docker.getHostPorts(), "HostPorts 应已分配");
        assertTrue(docker.getHostPorts().containsKey("APP_PORT"), "HostPorts 应含 APP_PORT");
        //留档领取响应（回程上报前的原始下发版）。
        Files.writeString(SMOKE_DIR.resolve("claimed-task-" + task.getId() + ".json"), JsonUtils.toString(task), StandardCharsets.UTF_8);
        //===== 执行 + 上报（镜像 MainService.runOpsTask 全链，executeDate 原样回传）=====
        Date claimedExecuteDate = task.getExecuteDate();
        executeAndReport(task);
        assertEquals(claimedExecuteDate.getTime(), task.getExecuteDate().getTime(),
                "executeDate 应原样回传领取值（不覆写；center 侧行内值与回传值同源，等值即接受）");
        assertEquals(TaskState.SUCCEED.getValue(), task.getState(), "执行后任务应 SUCCEED, taskError=" + task.getTaskError());
        assertNotNull(task.getDockerId(), "DOCKER 任务应回填 dockerId");
        //===== 真实容器断言（docker inspect 权威源）=====
        ContainerInspectRaw inspect = DockerInfoHelper.containerInspect(task.getDockerId());
        assertTrue(Boolean.TRUE.equals(inspect.getState().getRunning()), "容器应 running: " + inspect.getState());
        assertEquals("uw-ops-" + task.getContainerId(), inspect.getName() != null && inspect.getName().startsWith("/") ? inspect.getName().substring(1) : inspect.getName(), "容器名应为 uw-ops-{containerId}");
        assertEquals(String.valueOf(task.getPlanId()), inspect.getConfig().getLabels().get(StructuredTaskExecutor.LABEL_PLAN_ID), "label uw.ops.plan-id 应为 planId");
        assertEquals(String.valueOf(task.getContainerId()), inspect.getConfig().getLabels().get(StructuredTaskExecutor.LABEL_CONTAINER_ID), "label uw.ops.container-id 应为 containerId");
        //容器 Env 断言在 bash 侧用 docker inspect CLI 核对（inspect vo 未映射 Env 字段）。
        //===== 上报回填（重复上报幂等复核）=====
        OpsAgentApi.reportTaskResult(task);
        System.out.println("[smoke] step3 DONE taskId=" + task.getId() + " containerId=" + task.getContainerId()
                + " dockerId=" + task.getDockerId() + " actualPorts=" + task.getActualPorts());
        Files.writeString(SMOKE_DIR.resolve("last-start-result.txt"),
                "taskId=" + task.getId() + "\ncontainerId=" + task.getContainerId() + "\nplanId=" + task.getPlanId()
                        + "\ndockerId=" + task.getDockerId() + "\ncontainerName=" + docker.getContainerName() + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 步骤4（可选加分）：STOP 链路——领取 STOP 任务→真实 stop+remove→上报。
     * 前置：bash 已调 center /smoke/stopContainer?containerId=X 生成 STOP 任务。
     */
    @Test
    void step4_stopChain() throws Exception {
        uploadHostInfoViaAgentApi();
        OpsTask task;
        String claimFile = System.getProperty("smoke.claimFile");
        if (claimFile != null) {
            ResponseData<List<OpsTask>> claimed = JsonUtils.parse(
                    Files.readString(Path.of(claimFile), StandardCharsets.UTF_8),
                    new TypeReference<ResponseData<List<OpsTask>>>() {
                    });
            assertTrue(claimed.isSuccess(), "载入的领取响应应成功: " + claimed.getMsg());
            assertEquals(1, claimed.getData().size(), "载入的领取响应应恰为一条任务");
            task = claimed.getData().get(0);
        } else {
            task = claimOneTask("stop");
        }
        TaskData.ContainerSection docker = task.getTaskData().getContainer();
        assertNotNull(docker.getTargetContainerHash(), "STOP 任务应携带 TargetContainerHash（台账 dockerId）");
        executeAndReport(task);
        assertEquals(TaskState.SUCCEED.getValue(), task.getState(), "STOP 执行后应 SUCCEED, taskError=" + task.getTaskError());
        //容器应已被 remove：inspect 404。
        assertNotNull(task.getDockerId());
        boolean gone = false;
        try {
            DockerInfoHelper.containerInspect(task.getDockerId());
        } catch (DockerInfoHelper.ContainerNotFoundException e) {
            gone = true;
        }
        assertTrue(gone, "STOP 后容器应已删除（inspect 404）");
        OpsAgentApi.reportTaskResult(task);
        System.out.println("[smoke] step4 DONE stop taskId=" + task.getId() + " containerId=" + task.getContainerId()
                + " dockerId=" + task.getDockerId() + " result=" + task.getTaskResult());
        Files.writeString(SMOKE_DIR.resolve("last-stop-result.txt"),
                "taskId=" + task.getId() + "\ncontainerId=" + task.getContainerId() + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 镜像生产 MainService.runOpsTask 全链：领取下发的 executeDate 原样随上报回传（不覆写，
     * center 侧行内值与回传值同源，等值即接受）→ 分发执行 → 上报（finishDate 终态落值）。
     * 失败也必须上报（生产语义：FAILED 上报保持台账/任务状态机一致），异常继续上抛。
     */
    private static void executeAndReport(OpsTask task) throws Exception {
        try {
            MainService.dispatchOpsTask(task, false);
        } catch (Throwable e) {
            task.setTaskError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            task.setState(TaskState.FAILED.getValue());
            throw e;
        } finally {
            task.setFinishDate(SystemClock.nowDate());
            OpsAgentApi.reportTaskResult(task);
        }
    }

    /**
     * 领取一条任务并断言恰为一条（单次领取语义），按期望动作校验 taskType。
     * <p>
     * hostHash 不随任务下发（任务行 host_hash 由 agent 上报时经 reportTaskResult 回填，
     * OpsAgentApi 上报前以本地 hostHash 覆盖），不再断言下发值。
     */
    private OpsTask claimOneTask(String action) throws Exception {
        ResponseData<List<OpsTask>> response = OpsAgentApi.getTaskList();
        assertTrue(response.isSuccess(), "getTaskList 应成功: " + response.getMsg());
        assertNotNull(response.getData(), "任务列表应非 null");
        assertTrue(response.getData().size() <= 1, "单次领取最多一条, 实际=" + response.getData().size());
        assertEquals(1, response.getData().size(), "冒烟场景应恰好领到一条任务（无任务时请先生成/等待 schedule_date 到期）");
        OpsTask task = response.getData().get(0);
        assertEquals("start".equals(action) ? 1 : 2, task.getTaskType(), "任务类型应为 START(1)/STOP(2)");
        return task;
    }

    /**
     * 步骤9（验收通用执行器）：载入 bash 签名 curl 领取的任务 JSON，镜像生产全链执行并上报，
     * <b>不断言成败</b>（失败拉取/失败命令等负向场景由 bash 侧按结果文件断言终态与文案）。
     * 结果写 /tmp/uw-ops-smoke/exec-result-{taskId}.txt（state/taskError 各一行）。
     */
    @Test
    void step9_executeClaimedFile() throws Exception {
        uploadHostInfoViaAgentApi();
        String claimFile = System.getProperty("smoke.claimFile");
        assertTrue(claimFile != null && !claimFile.isBlank(), "smoke.claimFile 必须指定");
        ResponseData<List<OpsTask>> claimed = JsonUtils.parse(
                Files.readString(Path.of(claimFile), StandardCharsets.UTF_8),
                new TypeReference<ResponseData<List<OpsTask>>>() {
                });
        assertTrue(claimed.isSuccess(), "载入的领取响应应成功: " + claimed.getMsg());
        assertNotNull(claimed.getData());
        assertEquals(1, claimed.getData().size(), "应恰为一条任务");
        OpsTask task = claimed.getData().get(0);
        try {
            MainService.dispatchOpsTask(task, false);
        } catch (Throwable e) {
            task.setTaskError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            task.setState(TaskState.FAILED.getValue());
        } finally {
            task.setFinishDate(SystemClock.nowDate());
            OpsAgentApi.reportTaskResult(task);
        }
        Files.writeString(SMOKE_DIR.resolve("exec-result-" + task.getId() + ".txt"),
                "state=" + task.getState() + "\ntaskError=" + task.getTaskError() + "\ndockerId=" + task.getDockerId() + "\n",
                StandardCharsets.UTF_8);
        System.out.println("[smoke] step9 DONE taskId=" + task.getId() + " state=" + task.getState()
                + " taskError=" + task.getTaskError());
    }

    /**
     * 步骤5（验收探测恢复，F10）：本测试 JVM 是独立进程——上一 JVM 执行 START 时落盘的探测
     * 条目经 {@link ReadinessProber#restoreFromDisk()} 重建（等价 Agent 自升级/重启恢复）。
     * 断言目标 dockerId 的探测条目已恢复（脚本/脚本容器 ID 一致）。
     */
    @Test
    void step5_probeEntryRestored() throws Exception {
        String dockerId = System.getProperty("smoke.probeDockerId");
        assertTrue(dockerId != null && !dockerId.isBlank(), "smoke.probeDockerId 必须指定");
        ReadinessProber.restoreFromDisk();
        assertTrue(ReadinessProber.size() > 0, "重启后探测集合应从落盘恢复非空");
        ContainerInfo restored = new ContainerInfo();
        restored.setId(dockerId);
        ReadinessProber.applyResult(restored);
        System.out.println("[smoke] step5 probe restored, entries=" + ReadinessProber.size()
                + ", dockerId=" + dockerId + ", probeState=" + restored.getProbeState() + ", probeDate=" + restored.getProbeDate());
        assertNotNull(restored.getProbeState(), "目标容器探测条目应已恢复（probeState 非 null）");
    }

    /**
     * Env 列表包含指定前缀条目。
     */
    private static boolean containsEnv(List<String> env, String prefix) {
        if (env == null) {
            return false;
        }
        for (String entry : env) {
            if (entry != null && entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
