package uw.ops.agent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.response.ResponseData;
import uw.common.util.SystemClock;
import uw.ops.agent.api.OpsAgentApi;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.DockerInfoHelper;
import uw.ops.agent.helper.HostStatsCollector;
import uw.ops.agent.helper.ReadinessProber;
import uw.ops.agent.helper.StructuredTaskExecutor;
import uw.ops.agent.nativeimage.NativeImageMetadataVerifier;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.util.ShellRiskChecker;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 主运行服务。
 * <p>
 * v2.0 调度：主轮 scheduleAtFixedRate(60s 恒定激发)一次完成"采集→差分组装→上报"
 * （两点差分=精确满窗均值，硬预算~33s<周期不追赶）；事件窗口(20s)与任务轮询(10s)独立。
 * <p>
 * 任务通道（agent 纯客户端，无监听面），调度模型=领取单线程+执行池并发：ProcessOpsTask(10s轮)
 * 是唯一领取者（现有单条 CAS 领取通道不变，串行领取天然安全），循环领取填满空闲槽后提交固定
 * 线程池（并发度 {@link Constants#getMaxConcurrentTasks()}，daemon 线程 agent-task-exec-N），
 * 各任务按 taskData.execType 独立分发执行、独立上报（HTTP 调用天然并发安全）——SHELL 走 shell
 * 执行器（Center 完成端口与秘密展开，Agent 原样执行最终脚本），DOCKER 走 {@link StructuredTaskExecutor}；
 * 升级任务独占执行（只在池完全空闲时执行，成功上报后优雅停机并退出进程）。
 * 信道安全由上行签名认证+TLS 承担（响应走已验证连接）；重复执行由执行器幂等承担（inspect
 * 幂等定位）；超时与迟到结果由 center 侧 execute_date 比对裁决——center 侧迟到判定为
 * "上报 executeDate 等于行内 execute_date 才接受"，agent 侧将领取响应下发的 executeDate
 * 原样随上报回传、不覆写（契约详见 {@link #runOpsTask} javadoc）。
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

    /**
     * 任务执行池优雅停机等待上限(秒)：升级退出/外部停机时等待在途任务上报完成，超时放弃
     * （在途任务按崩溃兜底语义由 center 超时回收，升级场景可接受）。
     */
    private static final long TASK_POOL_SHUTDOWN_WAIT_SECONDS = 10;

    /**
     * 任务执行池：固定并发度（daemon 线程 agent-task-exec-N）。各任务独立执行、独立上报；
     * 池队列只是占位缓冲——空闲槽判定在 activeTasks（提交前计数），队列不会积压超过并发度。
     */
    private static volatile ExecutorService taskExecutor = newTaskExecutor();

    /**
     * 在途任务数（已提交执行池、未完成，含池内排队）：领取循环的空闲槽判定与升级门禁依据。
     * 领取线程提交时 +1，执行池任务 finally 中 -1。
     */
    static final AtomicInteger activeTasks = new AtomicInteger(0);

    /**
     * 任务并发上限（领取循环空闲槽判定；初始=执行池线程数，测试可调整）。
     */
    static volatile int maxConcurrentTasks = Constants.getMaxConcurrentTasks();

    /**
     * 暂存的升级任务：同轮领取先提交了普通任务、随后领到升级任务时（升级门禁挡住的是整轮
     * 首领，此场景仅同轮内可达）——不提交也不丢弃，暂存等池完全排空后由领取线程同步执行。
     */
    static volatile OpsTask pendingUpgradeTask;

    /**
     * 生产任务通道：OpsAgentApi getTaskList（单条领取）/ reportTaskResult（单条上报）。
     */
    private static final TaskChannel HTTP_TASK_CHANNEL = new TaskChannel() {

        @Override
        public List<OpsTask> claim() throws Exception {
            ResponseData<List<OpsTask>> responseData = OpsAgentApi.getTaskList();
            if (responseData.isNotSuccess()) {
                throw new RuntimeException("GetTaskList Error: " + responseData.getMsg());
            }
            return responseData.getData();
        }

        @Override
        public void report(OpsTask opsTask) throws Exception {
            OpsAgentApi.reportTaskResult(opsTask);
        }
    };

    /**
     * 任务通道接缝（测试可替换）：生产=OpsAgentApi HTTP 通道（协议不变——领取仍是现有单条
     * CAS 通道、上报仍是现有单条接口）。
     */
    static TaskChannel taskChannel = HTTP_TASK_CHANNEL;

    /**
     * secret 可用性判定（测试可替换）：生产=SecretStore.load() 非空。
     */
    static BooleanSupplier secretReady = () -> StringUtils.isNotBlank(SecretStore.load());

    /**
     * 进程退出动作（测试可替换）：生产=System.exit(0)，调用方已完成执行池优雅停机。
     */
    static Runnable exitAction = () -> System.exit(0);

    /**
     * 任务通道接缝：领取(单条 CAS)与上报(单条)两个端点的最小抽象。
     */
    interface TaskChannel {

        /**
         * 领取一条任务（现有 getTaskList 协议：一次最多一条）。
         *
         * @return 领取结果列表（0 或 1 条）；通道错误抛异常由领取轮统一记日志
         */
        List<OpsTask> claim() throws Exception;

        /**
         * 上报单条任务结果（各任务在执行池内独立调用，天然并发安全）。
         *
         * @param opsTask 任务对象（executeDate 已覆写为真实开始执行时刻）
         */
        void report(OpsTask opsTask) throws Exception;
    }

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
        //重建就绪探测集合（探测脚本只随 START 任务下发，落盘恢复使自升级/重启后受管容器
        //不失去探测；已消失容器的条目由首轮探测存活检查自然清理）。
        ReadinessProber.restoreFromDisk();

        //任务执行池优雅停机兜底（升级 System.exit 路径已同步停机，此处覆盖 systemd SIGTERM 等
        //外部停机：等待在途任务上报完成，上限10s超时放弃）。
        Runtime.getRuntime().addShutdownHook(new Thread(MainService::gracefulShutdownTaskPool, "agent-task-shutdown"));

        AtomicInteger threadCounter = new AtomicInteger(0);
        //池大小5：60s主轮/10s事件/10s任务领取/小时信息上报/60s就绪探测五类周期任务——任务执行
        //已分离到独立执行池（taskExecutor，结构化执行可占线程分钟级），任务轮只剩毫秒级领取
        //与提交；独立余量保证探测循环不被饿死（readyState 新鲜度是 Center 门禁的输入）。
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5, runnable -> {
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
        //每5秒探测 tick：条目按 nextProbeAt 到期执行（启动期5s密集/稳态15s，tcp=http=JVM原生
        //毫秒级）；聚合摘要随60s主轮上报；fixedDelay防重入(exec 3s超时×N串行顺延不堆积)。
        scheduledExecutorService.scheduleWithFixedDelay(new ReadinessProbeTask(), reportScatterSeconds + 5,
                ReadinessProber.PROBE_TICK_SECONDS, TimeUnit.SECONDS);
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
                log.error("Agent operation failed, errorType={}", e.getClass().getSimpleName());
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
                //组包内部完成采集+差分+事件合并（重试重复由 Center 端唯一键幂等挡除：
                //窗口 (host_id, create_date)、事件 (host_id, time_nano)）。
                uploadStatsWithRetry(HostStatsCollector.buildHostStats());
            } catch (Throwable e) {
                log.error("window report build failed, errorType={}", e.getClass().getSimpleName());
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
                log.error("stats upload attempt {} failed, errorType={}", i + 1, e.getClass().getSimpleName());
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
     * 就绪探测任务（5 秒 tick）：到期条目探测内聚在 {@link ReadinessProber#probeOnce()}，
     * 聚合摘要由主轮组包随 ContainerInfo 探测字段上报；异常在内部消化，调度线程不感知。
     * 探测配置双路径：label 声明式（inventory 收敛，零持久化）与 taskData（exec/敏感，落盘重启重建）。
     */
    public static class ReadinessProbeTask implements Runnable {

        @Override
        public void run() {
            ReadinessProber.probeOnce();
        }
    }

    /**
     * 检查并处理ops任务（10s 轮，唯一的领取者）。
     * <p>
     * 领取线程保持单线程：领取是单条 CAS 通道（center 侧 INIT→STARTING），串行领取天然安全、
     * 协议不变。每轮进入"循环领取"：池空时连续领取并提交执行池，直到无空闲槽或队列领空。
     * <p>
     * 升级任务门禁：升级任务(planId==0 && containerId==0 && clusterId==0)只能在执行池完全空闲
     * 时执行（其成功路径会退出进程）。领取无法预读队列头——升级任务可能就在队头，若在池忙时
     * 领到却不能执行，会把它占在 STARTING 直到 center 超时把 CMD 任务标失败。因此本轮一旦
     * 池非空就不领任何任务，等池排空后的下一轮再领；同轮内先提交普通任务后领到升级任务的
     * 罕见场景，暂存不丢弃（{@link #pendingUpgradeTask}），等池排空后同步执行。
     */
    public static class ProcessOpsTask implements Runnable {

        @Override
        public void run() {
            long start = SystemClock.now();
            int claimed = 0;
            // 无 secret 时跳过任务拉取(尚未通过首次下发，避免每10秒产生401)。
            if (!secretReady.getAsBoolean()) {
                return;
            }
            try {
                // 暂存的升级任务最优先：等池完全排空后在本线程同步执行（成功即优雅退出），
                // 排空/执行期间都不领任何新任务。
                OpsTask heldUpgrade = pendingUpgradeTask;
                if (heldUpgrade != null) {
                    if (activeTasks.get() == 0) {
                        pendingUpgradeTask = null;
                        runUpgradeTaskAndExitOnSuccess(heldUpgrade);
                    }
                    return;
                }
                // 升级门禁：池非空时本轮不领任何任务（升级任务可能在队列头，领了不能执行就卡住），
                // break 出循环等下轮。
                if (activeTasks.get() > 0) {
                    return;
                }
                // 循环领取：领到即提交执行池（各任务独立执行、独立上报），直到无空闲槽或领空。
                while (activeTasks.get() < maxConcurrentTasks) {
                    OpsTask opsTask = claimOneTask();
                    if (opsTask == null) {
                        break;
                    }
                    claimed++;
                    if (isUpgradeTask(opsTask)) {
                        if (activeTasks.get() == 0) {
                            // 池空时领到升级任务：本线程同步执行（执行+上报+成功即优雅退出）。
                            runUpgradeTaskAndExitOnSuccess(opsTask);
                        } else {
                            // 同轮先提交了普通任务后领到升级任务：不提交也不丢弃，暂存等池空，
                            // 本轮领取到此为止（下轮优先处理暂存任务）。
                            pendingUpgradeTask = opsTask;
                        }
                        // 升级任务终止本轮领取：成功路径会退出进程，后续任务留给新版本进程。
                        break;
                    }
                    submitTask(opsTask);
                }
            } catch (Throwable e) {
                log.error("Agent operation failed, errorType={}", e.getClass().getSimpleName());
            }
            log.info("ProcessOpsTask round finished: claimed={}, activeTasks={}, pendingUpgrade={} in {}ms.",
                    claimed, activeTasks.get(), pendingUpgradeTask != null, SystemClock.now() - start);
        }
    }

    /**
     * 领取一条任务（现有单条 CAS 领取通道，协议不变）。
     *
     * @return 领到的任务；队列为空返回 null
     */
    private static OpsTask claimOneTask() throws Exception {
        List<OpsTask> taskList = taskChannel.claim();
        if (taskList == null || taskList.isEmpty()) {
            return null;
        }
        return taskList.get(0);
    }

    /**
     * 提交一条任务到执行池：占槽计数在提交前 +1（含池内排队），执行任务 finally 中 -1。
     * 各任务在池内独立执行、独立上报（HTTP 调用天然并发安全）。
     */
    private static void submitTask(OpsTask opsTask) {
        activeTasks.incrementAndGet();
        try {
            taskExecutor.execute(() -> {
                try {
                    runOpsTask(opsTask);
                } finally {
                    activeTasks.decrementAndGet();
                }
            });
        } catch (Throwable e) {
            //池已关闭（升级退出路径）等提交失败：归还槽位并中止本轮领取——任务仍占 STARTING，
            //由 center 超时按崩溃兜底语义回收（进程即将退出，与崩溃等价）。
            activeTasks.decrementAndGet();
            throw new IllegalStateException("task submit rejected (executor shut down).", e);
        }
    }

    /**
     * 同步执行升级任务（领取线程，仅在池完全空闲时被调用）：执行+上报，成功则优雅停机
     * （执行池 shutdown+awaitTermination 10s 短等待）后退出进程，由外部机制(systemd/supervisor)
     * 重启新版本；失败则保持当前进程继续运行。
     */
    private static void runUpgradeTaskAndExitOnSuccess(OpsTask opsTask) {
        if (runOpsTask(opsTask)) {
            log.warn("!!!uw-ops-agent self update finished, exiting for restart...");
            gracefulShutdownTaskPool();
            exitAction.run();
            return;
        }
        log.error("uw-ops-agent self update failed or result was not reported; keep current process running.");
    }

    /**
     * 任务执行池优雅停机：shutdown + 短等待（{@link #TASK_POOL_SHUTDOWN_WAIT_SECONDS} 秒，
     * 超时放弃）。升级退出路径同步调用；JVM shutdown hook 兜底（systemd SIGTERM 等外部停机，
     * 相当于容器环境的 PreDestroy）。对已终止的池幂等（立即返回）。
     */
    private static void gracefulShutdownTaskPool() {
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(TASK_POOL_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("task executor still busy after {}s wait, give up (in-flight tasks aborted).",
                        TASK_POOL_SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 新建任务执行池：固定并发度（Constants.getMaxConcurrentTasks()），daemon 线程
     * agent-task-exec-N。
     */
    private static ExecutorService newTaskExecutor() {
        AtomicInteger threadCounter = new AtomicInteger(0);
        return Executors.newFixedThreadPool(Constants.getMaxConcurrentTasks(), runnable -> {
            Thread thread = new Thread(runnable, "agent-task-exec-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 重置任务执行运行时（测试专用）：换新执行池、清零占槽计数/暂存升级任务并还原接缝。
     * 生产代码不得调用。
     */
    static void resetTaskRuntime() {
        taskExecutor.shutdownNow();
        taskExecutor = newTaskExecutor();
        activeTasks.set(0);
        pendingUpgradeTask = null;
        taskChannel = HTTP_TASK_CHANNEL;
        secretReady = () -> StringUtils.isNotBlank(SecretStore.load());
        exitAction = () -> System.exit(0);
        maxConcurrentTasks = Constants.getMaxConcurrentTasks();
    }

    /**
     * 上报任务结果（经 {@link #taskChannel} 接缝，生产=OpsAgentApi.reportTaskResult 单条上报），
     * 必须确保上报成功，否则会出现脱控实例。各任务在执行池内独立调用，天然并发安全。
     * 结果上报失败按有限次数重试；网络响应不能清空本地可信密钥，只有显式管理重置才停止重试。
     */
    private static boolean reportTaskResult(OpsTask opsTask) {
        for (int i = 0; i < REPORT_TRY_TIMES; i++) {
            try {
                taskChannel.report(opsTask);
                return true;
            } catch (Throwable e) {
                log.error("uw-ops-agent report attempt {} failed, errorType={}", i + 1, e.getClass().getSimpleName());
            }
            // 显式管理重置导致 secret 缺失时停止重试。
            if (StringUtils.isBlank(SecretStore.load())) {
                log.warn("agent secret missing during reportTaskResult, stop retrying.");
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
     * @param opsTask 任务对象（结果写回 taskResult）
     * @param script  最终脚本（变量替换完成后的执行体）
     */
    private static void runTaskScript(OpsTask opsTask, String script) {
        runTaskScript(opsTask, script, TASK_TIMEOUT_MILLIS);
    }

    /**
     * 执行任务脚本，指定超时。
     *
     * @param opsTask     任务对象（结果写回 taskResult）
     * @param script      最终脚本（变量替换完成后的执行体）
     * @param timeoutMillis 超时毫秒，0表示不超时
     */
    private static void runTaskScript(OpsTask opsTask, String script, long timeoutMillis) {
        log.info("Shell command execute, taskId={}", opsTask.getId());
        List<String> cmdlist = ShellCmdUtils.runNative(new String[]{"/bin/sh", "-c", script}, null, true, timeoutMillis);
        opsTask.setTaskResult(opsTask.getTaskData().isSensitiveOutput()
                ? TaskData.SENSITIVE_RESULT_WITHHELD : StringUtils.join(cmdlist, "\n"));
        log.info("Shell command finished, taskId={}", opsTask.getId());
        opsTask.setState(TaskState.SUCCEED.getValue());
    }

    /**
     * 判断是否为 agent 自身升级任务。
     */
    private static boolean isUpgradeTask(OpsTask opsTask) {
        return opsTask.getPlanId() == 0 && opsTask.getContainerId() == 0 && opsTask.getClusterId() == 0;
    }

    /**
     * 执行单个 ops 任务(分发执行器、上报结果)——执行池任务 Runnable 的入口
     * （升级任务为领取线程同步调用的入口，语义相同）。
     * 升级任务使用独立长超时；只有任务执行成功且结果上报成功时才返回成功。
     * <p>
     * executeDate 契约（与 center 侧联动，<b>center 侧迟到判定为"上报 executeDate 等于行内
     * execute_date 才接受"</b>）：领取响应下发的 executeDate（=center 写入的领取时刻）
     * <b>原样随上报回传，不覆写</b>——正常执行回传恒等于行内值 → 接受；任务被超时重领后
     * 行内 execute_date 已刷新为新时刻，旧执行者回传的旧时刻 &lt; 行内 → 拒绝（迟到结果不
     * 覆盖新领取）。严禁用 agent 本机墙钟覆写该值：center 的比对是跨机器时间比较，agent
     * 时钟慢于 center 数秒时（偏差远小于上行签名时间窗）正常结果会被误判 stale 拒绝。
     * center 的执行超时判定按行内 execute_date=领取时刻计时（领了没执行也计时，
     * 崩溃兜底语义，保守方向可接受）。
     *
     * @param opsTask 下发任务对象
     * @return 任务执行和结果上报均成功时返回 true
     */
    static boolean runOpsTask(OpsTask opsTask) {
        boolean isUpgrade = isUpgradeTask(opsTask);
        boolean executionSucceeded = false;
        try {
            dispatchOpsTask(opsTask, isUpgrade);
            executionSucceeded = true;
        } catch (Throwable e) {
            log.error("Agent operation failed, errorType={}", e.getClass().getSimpleName());
            //仅允许由本地执行器生成的固定摘要；任意异常message/堆栈可能包含展开后的秘密。
            String summary = e instanceof ShellCmdUtils.CommandException
                    || e instanceof StructuredTaskExecutor.TaskExecutionException
                    ? e.getMessage() : "task execution failed.";
            opsTask.setTaskError(summary);
            opsTask.setTaskResult(null);
            opsTask.setState(TaskState.FAILED.getValue());
        } finally {
            if (opsTask.getTaskData() != null && opsTask.getTaskData().isSensitiveOutput()) {
                opsTask.setTaskResult(TaskData.SENSITIVE_RESULT_WITHHELD);
                if (opsTask.getTaskError() != null) {
                    opsTask.setTaskError(TaskData.SENSITIVE_ERROR_WITHHELD);
                }
            }
            opsTask.setFinishDate(SystemClock.nowDate());
        }
        boolean reportSucceeded = reportTaskResult(opsTask);
        return executionSucceeded && reportSucceeded;
    }

    /**
     * 按 taskData.execType 分发执行器（execType 是分发的唯一依据，
     * 不靠 taskType 推断也不靠字段存在与否隐式判别）：
     * <ul>
     *   <li>DOCKER → {@link StructuredTaskExecutor}（结构化唯一通道，无 shell 回退）；</li>
     *   <li>SHELL → shell 执行器（/bin/sh -c + ShellRiskChecker）；Center 已完成端口和秘密替换，
     *       Agent 不再次解释模板或改写已分配端口；升级任务同走此路径；</li>
     *   <li>缺失/未知 execType → 拒绝执行（任务 FAILED，不静默走 shell）。</li>
     * </ul>
     *
     * @param opsTask   任务对象
     * @param isUpgrade 是否为 agent 自身升级任务
     */
    static void dispatchOpsTask(OpsTask opsTask, boolean isUpgrade) throws Exception {
        TaskData taskData = opsTask.getTaskData();
        String execType = taskData == null ? null : taskData.getExecType();
        if (StructuredTaskExecutor.EXEC_TYPE_DOCKER.equals(execType)) {
            StructuredTaskExecutor.execute(opsTask, taskData);
            return;
        }
        if (!StructuredTaskExecutor.EXEC_TYPE_SHELL.equals(execType)) {
            throw new IllegalArgumentException("unsupported taskData execType: " + execType);
        }
        //Center 是模板解析与端口分配的唯一来源。此处拿到最终脚本，秘密值内可能合法包含
        //字面 ${APP_PORT}，再次 String.replace 会破坏秘密；自行换端口还会偏离Center台账。
        TaskData.ShellSection shell = taskData.getShell();
        String script = shell == null ? null : shell.getScript();
        if (StringUtils.isEmpty(script)) {
            //SHELL 载荷缺脚本属协议错误：显式拒绝，避免 /bin/sh -c null 的晦默 NPE。
            throw new IllegalArgumentException("taskData.shell.script missing.");
        }
        //危险命令检查（作用于Center下发的最终脚本）。
        String danger = ShellRiskChecker.checkRisk(script);
        if (danger != null) {
            throw new SecurityException(danger);
        }
        if (isUpgrade) {
            // 升级任务同步执行，确保退出前任务结果已经上报。
            opsTask.setTaskResult("!!!uw-ops-agent start self updating......");
            log.warn(opsTask.getTaskResult());
            opsTask.setState(TaskState.STARTING.getValue());
            runTaskScript(opsTask, script, UPGRADE_TIMEOUT_MILLIS);
        } else {
            runTaskScript(opsTask, script);
        }
    }

}
