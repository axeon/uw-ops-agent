package uw.ops.agent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.SystemClock;
import uw.ops.agent.api.OpsAgentApi;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.constant.TaskType;
import uw.ops.agent.helper.DockerInfoHelper;
import uw.ops.agent.helper.HostStatsCollector;
import uw.ops.agent.nativeimage.NativeImageMetadataVerifier;
import uw.ops.agent.util.NetworkUtils;
import uw.ops.agent.util.PropertyUtils;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.util.ShellRiskChecker;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 主运行服务。
 * <p>
 * v2.0 调度：主轮 scheduleAtFixedRate(60s 恒定激发)一次完成"采集→差分组装→上报"
 * （两点差分=精确满窗均值，硬预算~33s<周期不追赶）；事件窗口(20s)与任务轮询(10s)独立。
 */
public class MainService {

    private static final Logger log = LoggerFactory.getLogger(Constants.getAgentInfo());

    /**
     * 报告重试次数100次。
     */
    private static final int REPORT_TRY_TIMES = 100;

    /**
     * 报告重试间隔10s。
     */
    private static final int REPORT_TRY_INTERVAL = 10_000;

    /**
     * 任务签名时间窗口：5分钟(与Center端 agentSignTimeWindow 对齐)。
     */
    private static final long SIGN_TIME_WINDOW = 300_000L;

    /**
     * 指标上报当场重试次数：3 次×5s 覆盖 Center 短暂抖动，全失败放弃该窗口。
     */
    private static final int STATS_UPLOAD_RETRY_TIMES = 3;

    /**
     * 指标上报重试间隔(毫秒)。
     */
    private static final long STATS_UPLOAD_RETRY_INTERVAL = 5_000L;

    /**
     * 普通任务脚本执行超时：10分钟。
     */
    private static final long TASK_TIMEOUT_MILLIS = 600_000L;

    /**
     * 升级任务脚本执行超时：5分钟(下载二进制)。
     */
    private static final long UPGRADE_TIMEOUT_MILLIS = 300_000L;

    static {
        //必须是初始化之前执行log属性设置。
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
    }

    public static void main(String[] args) throws Exception {
        if (NativeImageMetadataVerifier.isVerificationRequested(args)) {
            NativeImageMetadataVerifier.verify();
            return;
        }
        log.info("uw-ops-agent starting at: {}", MainService.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        if ("root".equals(System.getProperty("user.name"))) {
            log.warn("!!!uw-ops-agent is running as root, which is not recommended for security reasons.");
        }

        //60秒两点差分器(静态单例)；启动即建立差分基线（ticks/cs/int/网卡/磁盘），首个窗口即产出有效差值。
        HostStatsCollector.init();
        //Docker Engine API客户端(本机UDS)，启动时协商API版本前缀。
        DockerInfoHelper.negotiateApiVersion();

        //设置系统属性。
        AtomicInteger threadCounter = new AtomicInteger(0);
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("agent-" + threadCounter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        //每1个小时上传一次服务器信息。
        scheduledExecutorService.scheduleAtFixedRate(new UploadHostInfoTask(), 0, 1, TimeUnit.HOURS);
        //主轮(60s恒定激发,fixedRate)：inventory → 容器stats(两点差分的第二点) → Host边界采集(同刻δ=0)
        //→ 差分组装 → 上报。各环节硬预算合计~33s<周期，激发永不追赶重叠；初始延迟随机打散0~59s
        //(多主机批量重启错开上报相位；首个完整窗口需要两次激发建立差分基准)。
        long reportScatterSeconds = ThreadLocalRandom.current().nextLong(60);
        scheduledExecutorService.scheduleAtFixedRate(new StatsReportTask(), reportScatterSeconds, 60, TimeUnit.SECONDS);
        //每10秒事件窗口采集(独立调度，与主轮错开相位：单请求读 daemon 内存缓冲，成本毫秒级；
        //高密度拉取对抗事件journal环形缓冲被冲刷——缓冲64条硬编码不可调且被挤出不可检测，
        //丢失条件=拉取间隔内事件数>64，10s间隔安全阈值6.4条/s(≈10个并发crash-loop容器的
        //die/restart/start流量)；运维脚本批量启停(50+条/s)属daemon架构级限制防不住，靠台账
        //对账兜底；事件累积在采集器，主轮仍60s消费一次)。
        scheduledExecutorService.scheduleAtFixedRate(new DockerEventTask(), reportScatterSeconds+10, 10, TimeUnit.SECONDS);
        //每10秒钟检查并处理ops任务。
        scheduledExecutorService.scheduleAtFixedRate(new ProcessOpsTask(), reportScatterSeconds+20, 10, TimeUnit.SECONDS);
        if (StringUtils.isBlank(SecretStore.load())) {
            log.warn("uw-ops-agent started without agent secret, waiting for center to deliver via uploadHostStats...");
        } else {
            log.info("uw-ops-agent started with agent secret.");
        }
    }

    /**
     * 上传服务器信息的任务（每小时）：采集入口 {@link HostStatsCollector#buildHostInfo()}。
     */
    public static class UploadHostInfoTask implements Runnable {

        @Override
        public void run() {
            long start = SystemClock.now();
            try {
                OpsAgentApi.uploadHostInfo(HostStatsCollector.buildHostInfo());
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            log.info("UploadHostInfoTask run finished in {}ms.", SystemClock.now() - start);
        }
    }

    /**
     * 主轮任务（60s 恒定激发）：采集+差分内聚在 {@link HostStatsCollector#buildHostStats()}，
     * 本任务只负责发送（失败当场重试同一 payload，全失败放弃该窗口，下轮新窗）。
     * <p>
     * 预算：buildHostStats 内部 inventory 5s + stats 12s + Host 边界毫秒级 + 上报 15s ≈ 33s &lt; 60s 周期，
     * fixedRate 激发时刻恒定 → 差分两点=相邻激发=恰好一个完整窗口（精确满窗均值）。
     */
    public static class StatsReportTask implements Runnable {

        @Override
        public void run() {
            long start = SystemClock.now();
            try {
                //组包内部完成采集+差分+事件合并（重试重复由 Center 端 (host_id, time_nano) 唯一键幂等挡除）。
                uploadStatsWithRetry(HostStatsCollector.buildHostStats());
            } catch (Throwable e) {
                log.error("window report build failed: {}", e.getMessage(), e);
            }
            log.info("StatsReportTask run finished in {}ms.", SystemClock.now() - start);
        }
    }

    /**
     * 发送层当场重试（与采集解耦）：同一 payload 重试撞 Center 的 (host_id, create_date) 唯一键幂等，
     * 3 次×5s 覆盖 Center 短暂抖动；全失败放弃该窗口（监控快照可容忍，长挂期间本就无消费方），
     * 下轮照常产出全新窗口——采集节奏永不被发送失败牵动。
     * 事件批例外：窗口可弃事件不可再生（台账与告警的证据链），全失败/中断时从 payload 提取回灌下轮再报。
     */
    private static void uploadStatsWithRetry(HostStats payload) {
        for (int i = 0; i < STATS_UPLOAD_RETRY_TIMES; i++) {
            try {
                OpsAgentApi.uploadHostStats(payload);
                return;
            } catch (Throwable e) {
                log.error("stats upload attempt {} failed: {}", i + 1, e.getMessage(), e);
            }
            try {
                Thread.sleep(STATS_UPLOAD_RETRY_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                HostStatsCollector.restoreContainerEventsFromPayload(payload);
                return;
            }
        }
        log.error("stats upload abandoned after {} attempts, window dropped (statsDate={})", STATS_UPLOAD_RETRY_TIMES, payload.getStatsDate());
        HostStatsCollector.restoreContainerEventsFromPayload(payload);
    }

    /**
     * 事件窗口采集任务（10 秒）：拉取与累积内聚在 {@link HostStatsCollector#pollContainerEvents()}
     * （单请求读 daemon 内存环形缓冲；失败跳过本轮、窗口上界不推进、下轮无缝续拉，拉取间隔内不丢）。
     */
    public static class DockerEventTask implements Runnable {

        @Override
        public void run() {
            HostStatsCollector.pollContainerEvents();
        }
    }

    /**
     * 检查并处理ops任务。
     */
    public static class ProcessOpsTask implements Runnable {

        @Override
        public void run() {
            long start = SystemClock.now();
            int taskAll = 0, taskSuccess = 0;
            // 无 secret 时跳过任务拉取(尚未通过首次下发，避免每10秒产生401)。
            if (StringUtils.isBlank(SecretStore.load())) {
                return;
            }
            try {
                ResponseData<List<OpsTask>> responseData = OpsAgentApi.getTaskList();
                if (responseData.isNotSuccess()) {
                    log.warn("GetTaskList Error: {}", responseData.getMsg());
                } else {
                    List<OpsTask> opsTaskList = responseData.getData();
                    if (opsTaskList != null) {
                        taskAll = opsTaskList.size();
                        // 升级任务(planId==0 && containerId==0 && clusterId==0)排到最后执行，
                        // 避免其 System.exit 丢弃同批次后续任务。
                        OpsTask upgradeTask = null;
                        for (OpsTask opsTask : responseData.getData()) {
                            if (isUpgradeTask(opsTask)) {
                                upgradeTask = opsTask;
                                continue;
                            }
                            if (runOpsTask(opsTask)) {
                                taskSuccess++;
                            }
                        }
                        // 最后处理升级任务(若存在)。
                        if (upgradeTask != null) {
                            if (runOpsTask(upgradeTask)) {
                                // 升级成功且结果已上报，退出进程，由外部机制(systemd/supervisor)重启新版本。
                                log.warn("!!!uw-ops-agent self update finished, exiting for restart...");
                                System.exit(0);
                            }
                            log.error("uw-ops-agent self update failed or result was not reported; keep current process running.");
                        }
                    }
                }
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            log.info("ProcessOpsTask run {}/{} tasks finished in {}ms.", taskSuccess, taskAll, SystemClock.now() - start);

        }
    }

    /**
     * 验证下行任务签名。
     * 密钥从本地 SecretStore 读取；无密钥时拒绝执行任何任务，不保留兼容放行路径。
     */
    private static void verifyTaskSign(OpsTask opsTask) {
        String agentSecret = SecretStore.load();
        if (StringUtils.isBlank(agentSecret)) {
            throw new SecurityException("本地鉴权密钥缺失，拒绝执行任务");
        }
        if (StringUtils.isBlank(opsTask.getTaskSign())) {
            throw new SecurityException("任务签名缺失，拒绝执行");
        }
        long now = SystemClock.now();
        if (Math.abs(now - opsTask.getTaskStamp()) > SIGN_TIME_WINDOW) {
            throw new SecurityException("任务签名已过期，拒绝执行");
        }
        if (!HmacUtils.verify(opsTask.getId() + ":" + opsTask.getTaskScript() + ":" + opsTask.getTaskStamp(), agentSecret, opsTask.getTaskSign())) {
            throw new SecurityException("任务签名校验失败，拒绝执行");
        }
    }

    /**
     * 上报任务结果，必须确保上报成功，否则会出现脱控实例。
     * 若上报过程中主机被 Center disable(OpsAgentApi 检测到 host-disabled 会清空本地 secret)，
     * 则立即停止重试——继续重试只会收到同样的 host-disabled 响应，无意义且阻塞调度。
     */
    private static boolean reportTaskResult(OpsTask opsTask) {
        for (int i = 0; i < REPORT_TRY_TIMES; i++) {
            try {
                OpsAgentApi.reportTaskResult(opsTask);
                return true;
            } catch (Throwable e) {
                log.error("!uw-ops-agent report attempt {} failed, error message: {}", i + 1, e.getMessage(), e);
            }
            // secret 被清空说明主机已被 Center disable，停止重试。
            if (StringUtils.isBlank(SecretStore.load())) {
                log.warn("agent secret cleared during reportTaskResult (host disabled), stop retrying.");
                return false;
            }
            if (i + 1 == REPORT_TRY_TIMES) {
                break;
            }
            try {
                Thread.sleep(REPORT_TRY_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.error("uw-ops-agent failed to report task result after {} attempts.", REPORT_TRY_TIMES);
        return false;
    }

    /**
     * 执行任务脚本。普通任务默认10分钟超时，防止脚本 hang 住拖垮调度线程。
     *
     * @param opsTask
     */
    private static void runTaskScript(OpsTask opsTask) {
        runTaskScript(opsTask, TASK_TIMEOUT_MILLIS);
    }

    /**
     * 执行任务脚本，指定超时。
     *
     * @param opsTask
     * @param timeoutMillis 超时毫秒，0表示不超时
     */
    private static void runTaskScript(OpsTask opsTask, long timeoutMillis) {
        log.info("Shell command execute: \n{}", opsTask.getTaskScript());
        List<String> cmdlist = ShellCmdUtils.runNative(new String[]{"/bin/sh", "-c", opsTask.getTaskScript()}, null, true, timeoutMillis);
        opsTask.setTaskResult(StringUtils.join(cmdlist, "\n"));
        //日志侧限长（全量结果在 taskResult 上报 Center，落库列容量 64K）：防大输出撑胖本机日志。
        String resultForLog = opsTask.getTaskResult();
        log.info("Shell receive results: \n{}", resultForLog != null && resultForLog.length() > 2000
                ? resultForLog.substring(0, 2000) + "\n... [log truncated, full result reported to center]" : resultForLog);
        opsTask.setState(TaskState.SUCCEED.getValue());
    }

    /**
     * 判断是否为 agent 自身升级任务。
     */
    private static boolean isUpgradeTask(OpsTask opsTask) {
        return opsTask.getPlanId() == 0 && opsTask.getContainerId() == 0 && opsTask.getClusterId() == 0;
    }

    /**
     * 执行单个 ops 任务(验签、端口替换、危险检查、运行脚本、上报结果)。
     * 升级任务使用独立长超时；只有任务执行成功且结果上报成功时才返回成功。
     *
     * @param opsTask
     * @return 任务执行和结果上报均成功时返回 true
     */
    private static boolean runOpsTask(OpsTask opsTask) {
        boolean isUpgrade = isUpgradeTask(opsTask);
        boolean executionSucceeded = false;
        opsTask.setExecuteDate(SystemClock.nowDate());
        try {
            //验签校验（必须在端口替换之前，校验原始脚本）
            verifyTaskSign(opsTask);
            //如果是启动任务，则检查端口可用情况，并替换APP_PORT变量。
            if (opsTask.getTaskType() < TaskType.STOP.getValue() && StringUtils.isNotBlank(opsTask.getTaskPorts())) {
                Properties properties = PropertyUtils.loadFromString(opsTask.getTaskPorts());
                String taskScript = opsTask.getTaskScript();
                for (String portName : properties.stringPropertyNames()) {
                    int preferPort = 0;
                    try {
                        preferPort = Integer.parseInt(properties.getProperty(portName));
                    } catch (Exception e) {
                    }
                    if (preferPort == 0) {
                        //此时可能不是端口数据，直接替换。
                        taskScript = taskScript.replace("${" + portName + "}", properties.getProperty(portName));
                    } else {
                        int usablePort = NetworkUtils.getUsablePort(preferPort);
                        properties.setProperty(portName, String.valueOf(usablePort));
                        taskScript = taskScript.replace("${" + portName + "}", String.valueOf(usablePort));
                    }
                }
                //保存变更后的端口配置。
                opsTask.setTaskPorts(PropertyUtils.storeToString(properties));
                opsTask.setTaskScript(taskScript);
            }
            //危险命令检查
            String danger = ShellRiskChecker.checkRisk(opsTask.getTaskScript());
            if (danger != null) {
                throw new SecurityException(danger);
            }
            if (isUpgrade) {
                // 升级任务同步执行，确保退出前任务结果已经上报。
                opsTask.setTaskResult("!!!uw-ops-agent start self updating......");
                log.warn(opsTask.getTaskResult());
                opsTask.setState(TaskState.STARTING.getValue());
                runTaskScript(opsTask, UPGRADE_TIMEOUT_MILLIS);
            } else {
                runTaskScript(opsTask);
            }
            executionSucceeded = true;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            //NPE 等异常 message 为 null，兜底异常类名保证上报错误信息非空。
            opsTask.setTaskError(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            opsTask.setState(TaskState.FAILED.getValue());
        } finally {
            opsTask.setFinishDate(SystemClock.nowDate());
        }
        boolean reportSucceeded = reportTaskResult(opsTask);
        return executionSucceeded && reportSucceeded;
    }

}
