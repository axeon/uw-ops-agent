package uw.ops.agent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.dto.ResponseData;
import uw.common.util.SystemClock;
import uw.ops.agent.api.OpsAgentApi;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.constant.TaskType;
import uw.ops.agent.helper.SystemInfoHelper;
import uw.ops.agent.util.NetworkUtils;
import uw.ops.agent.util.PropertyUtils;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.OpsTask;

import java.util.Date;
import java.util.List;
import java.util.Properties;
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

    static {
        //必须是初始化之前执行log属性设置。
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
    }

    public static void main(String[] args) throws Exception {
        log.info("uw-ops-agent starting at: {}", MainService.class.getProtectionDomain().getCodeSource().getLocation().getPath());

        //设置系统属性。
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("service");
            thread.setDaemon(false);
            return thread;
        });
        //每1个小时上传一次服务器信息。
        scheduledExecutorService.scheduleAtFixedRate(new UploadHostInfoTask(), 0, 1, TimeUnit.HOURS);
        //每60秒钟上传一次服务器性能信息。
        scheduledExecutorService.scheduleAtFixedRate(new UploadHostStatsTask(), 10, 60, TimeUnit.SECONDS);
        //每10秒钟检查并处理ops任务。
        scheduledExecutorService.scheduleAtFixedRate(new ProcessOpsTask(), 10, 10, TimeUnit.SECONDS);
        log.info("uw-ops-agent started!");
    }

    /**
     * 执行任务脚本。
     *
     * @param opsTask
     */
    private static void runTaskScript(OpsTask opsTask) {
        log.info("Shell command execute: \n{}", opsTask.getTaskScript());
        List<String> datalist = ShellCmdUtils.runNative(new String[]{"/bin/sh", "-c", opsTask.getTaskScript()}, null, true);
        opsTask.setTaskResult(StringUtils.join(datalist, "\n"));
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
            try {
                ResponseData<List<OpsTask>> responseData = OpsAgentApi.getTaskList();
                if (responseData.isNotSuccess()) {
                    log.warn("GetTaskList Error: {}", responseData.getMsg());
                } else {
                    List<OpsTask> opsTaskList = responseData.getData();
                    if (opsTaskList != null) {
                        taskAll = opsTaskList.size();
                        for (OpsTask opsTask : responseData.getData()) {
                            opsTask.setExecuteDate(new Date());
                            try {
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
                                //单独处理agent升级任务，需要线程异步来跑。
                                if (opsTask.getPlanId() == 0 && opsTask.getInstanceId() == 0 && opsTask.getClusterId() == 0) {
                                    //对于升级任务，直接设置成功标记。
                                    opsTask.setTaskResult("!!!uw-ops-agent start self updating......");
                                    log.warn(opsTask.getTaskResult());
                                    opsTask.setState(TaskState.STARTING.getValue());
                                    new Thread(() -> runTaskScript(opsTask)).start();
                                } else {
                                    runTaskScript(opsTask);
                                    taskSuccess++;
                                }
                            } catch (Throwable e) {
                                log.error(e.getMessage(), e);
                                opsTask.setTaskError(e.getMessage());
                                opsTask.setState(TaskState.FAIlED.getValue());
                            } finally {
                                opsTask.setFinishDate(new Date());
                                //执行任务,执行后上报结果，必须确保上报成功，否则会出现脱控实例。
                                for (int i = 0; i < REPORT_TRY_TIMES; i++) {
                                    try {
                                        OpsAgentApi.reportTaskResult(opsTask);
                                        break;
                                    } catch (Throwable e) {
                                        log.error("!uw-ops-agent retry report {} times, error message: {}", i, e.getMessage(), e);
                                    }
                                    try {
                                        Thread.sleep(REPORT_TRY_INTERVAL);
                                    } catch (InterruptedException e) {
                                    }
                                }
                            }
                            //如果是升级任务，则立即挂起。
                            if (opsTask.getPlanId() == 0 && opsTask.getInstanceId() == 0 && opsTask.getClusterId() == 0) {
                                log.warn("!!!uw-ops-agent run self updating...all task will be halt!");
                                Thread.sleep(Long.MAX_VALUE);
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
            }
            log.info("ProcessOpsTask run {}/{} tasks finished in {}ms.", taskSuccess, taskAll, SystemClock.now() - start);

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
