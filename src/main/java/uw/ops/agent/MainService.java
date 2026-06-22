package uw.ops.agent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.SystemClock;
import uw.ops.agent.api.OpsAgentApi;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.constant.TaskType;
import uw.ops.agent.helper.SystemInfoHelper;
import uw.ops.agent.util.NetworkUtils;
import uw.ops.agent.util.PropertyUtils;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.util.ShellRiskChecker;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.OpsTask;

import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static uw.ops.agent.AgentInfo.AGENT_INFO;

/**
 * 主运行服务。
 */
public class MainService {

    private static final Logger log = LoggerFactory.getLogger(AGENT_INFO);
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

    static {
        //必须是初始化之前执行log属性设置。
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
    }

    public static void main(String[] args) throws Exception {
        log.info("uw-ops-agent starting at: {}", MainService.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        if ("root".equals(System.getProperty("user.name"))) {
            log.warn("!!!uw-ops-agent is running as root, which is not recommended for security reasons.");
        }

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
        //每60秒钟上传一次服务器性能信息。
        scheduledExecutorService.scheduleAtFixedRate(new UploadHostStatsTask(), 10, 60, TimeUnit.SECONDS);
        //每10秒钟检查并处理ops任务。
        scheduledExecutorService.scheduleAtFixedRate(new ProcessOpsTask(), 10, 10, TimeUnit.SECONDS);
        if (StringUtils.isBlank(SecretStore.load())) {
            log.warn("uw-ops-agent started without agent secret, waiting for center to deliver via uploadHostStats...");
        } else {
            log.info("uw-ops-agent started with agent secret.");
        }
    }

    /**
     * 验证下行任务签名。
     * 密钥从本地 SecretStore 读取；无密钥时拒绝执行任何任务(去除legacy，硬约束)。
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
     */
    private static void reportTaskResult(OpsTask opsTask) {
        for (int i = 0; i < REPORT_TRY_TIMES; i++) {
            try {
                OpsAgentApi.reportTaskResult(opsTask);
                return;
            } catch (Throwable e) {
                log.error("!uw-ops-agent retry report {} times, error message: {}", i, e.getMessage(), e);
            }
            try {
                Thread.sleep(REPORT_TRY_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 普通任务脚本执行超时：10分钟。
     */
    private static final long TASK_TIMEOUT_MILLIS = 600_000L;
    /**
     * 升级任务脚本执行超时：5分钟(下载二进制)。
     */
    private static final long UPGRADE_TIMEOUT_MILLIS = 300_000L;

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
        log.info("Shell receive results: \n{}", opsTask.getTaskResult());
        opsTask.setState(TaskState.SUCCEED.getValue());
    }

    /**
     * 上传服务器信息的任务。
     */
    public static class UploadHostInfoTask implements Runnable {

        @Override
        public void run() {
            long start = SystemClock.now();
            try {
                OpsAgentApi.uploadHostInfo(SystemInfoHelper.buildHostInfo());
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            log.info("UploadHostInfoTask run finished in {}ms.", SystemClock.now() - start);
        }
    }

    /**
     * 上传服务器统计信息。
     */
    public static class UploadHostStatsTask implements Runnable {

        @Override
        public void run() {
            //上传服务器统计信息。
            long start = SystemClock.now();
            try {
                OpsAgentApi.uploadHostStats(SystemInfoHelper.buildHostStats());
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            log.info("UploadHostStatsTask run finished in {}ms.", SystemClock.now() - start);

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
                        // 升级任务(planId==0 && instanceId==0 && clusterId==0)排到最后执行，
                        // 避免其 System.exit 丢弃同批次后续任务。
                        OpsTask upgradeTask = null;
                        for (OpsTask opsTask : responseData.getData()) {
                            if (opsTask.getPlanId() == 0 && opsTask.getInstanceId() == 0 && opsTask.getClusterId() == 0) {
                                upgradeTask = opsTask;
                                continue;
                            }
                            taskSuccess += runOpsTask(opsTask);
                        }
                        // 最后处理升级任务(若存在)。
                        if (upgradeTask != null) {
                            runOpsTask(upgradeTask);
                            // 升级任务已完成上报，退出进程，由外部机制(systemd/supervisor)重启新版本。
                            log.warn("!!!uw-ops-agent self update finished, exiting for restart...");
                            System.exit(0);
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
     * 判断是否为 agent 自身升级任务。
     */
    private static boolean isUpgradeTask(OpsTask opsTask) {
        return opsTask.getPlanId() == 0 && opsTask.getInstanceId() == 0 && opsTask.getClusterId() == 0;
    }

    /**
     * 执行单个 ops 任务(验签、端口替换、危险检查、运行脚本、上报结果)。
     * 升级任务使用独立长超时，并在异步线程内上报后通过 latch 唤醒；普通任务同步执行。
     *
     * @param opsTask
     * @return 任务执行成功返回1，失败返回0
     */
    private static int runOpsTask(OpsTask opsTask) {
        boolean isUpgrade = isUpgradeTask(opsTask);
        opsTask.setExecuteDate(new Date());
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
                //升级任务异步执行，带独立超时，上报完成后唤醒主线程。
                opsTask.setTaskResult("!!!uw-ops-agent start self updating......");
                log.warn(opsTask.getTaskResult());
                opsTask.setState(TaskState.STARTING.getValue());
                CountDownLatch upgradeLatch = new CountDownLatch(1);
                new Thread(() -> {
                    try {
                        runTaskScript(opsTask, UPGRADE_TIMEOUT_MILLIS);
                    } catch (Throwable e) {
                        log.error(e.getMessage(), e);
                        opsTask.setTaskError(e.getMessage());
                        opsTask.setState(TaskState.FAILED.getValue());
                    } finally {
                        opsTask.setFinishDate(new Date());
                        reportTaskResult(opsTask);
                        upgradeLatch.countDown();
                    }
                }).start();
                //await 加超时(升级超时 + 报告重试余量)，防止 hang 住卡死调度线程。
                upgradeLatch.await(UPGRADE_TIMEOUT_MILLIS + 120_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                return 0;
            } else {
                runTaskScript(opsTask);
                return 1;
            }
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            opsTask.setTaskError(e.getMessage());
            opsTask.setState(TaskState.FAILED.getValue());
            return 0;
        } finally {
            //升级任务已在新线程内完成上报，跳过；普通任务在此上报。
            if (!isUpgrade) {
                opsTask.setFinishDate(new Date());
                reportTaskResult(opsTask);
            }
        }
    }

//    /**
//     * 删除自身，并退出。
//     */
//    public static void deleteAndExitSelf() {
//        String self = MainService.class.getProtectionDomain().getCodeSource().getLocation().getPath().trim();
//        //必须增加必要的判定，再去删除文件。
//        if (StringUtils.isNotBlank(self) && !self.contains(" ") && !self.equals("/")) {
//            ShellCmdUtils.runNative("rm -f " + self);
//        }
//        System.exit(0);
//    }
}
