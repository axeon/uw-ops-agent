package uw.ops.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.StructuredTaskExecutor;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MainService 任务调度模型测试（领取单线程+执行池并发）：经 TaskChannel 内存桩替换领取/上报
 * 通道，验证——并发执行（一轮循环领取填槽、执行池并发消化、各自上报带真实开始执行时刻）、
 * 升级任务门禁（池忙时本轮不领任何任务、同轮领到不丢弃暂存、池排空后同步执行并触发退出）、
 * 槽满停止领取（占满并发槽后本轮不再领取，槽释放后下一轮补领）、无 secret 不领取。
 */
class MainServiceTaskSchedulingTest {

    @BeforeEach
    void setUp() {
        MainService.resetTaskRuntime();
        MainService.secretReady = () -> true;
    }

    @AfterEach
    void tearDown() {
        MainService.resetTaskRuntime();
    }

    /**
     * 构造 SHELL 任务（脚本=入参，id 区分）。
     */
    private OpsTask newShellTask(long id, String script) {
        OpsTask task = new OpsTask();
        task.setId(id);
        task.setPlanId(id);
        task.setContainerId(id);
        task.setClusterId(id);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_SHELL);
        TaskData.ShellSection shell = new TaskData.ShellSection();
        shell.setScript(script);
        taskData.setShell(shell);
        task.setTaskData(taskData);
        return task;
    }

    @Test
    void shellFailuresExposeOnlySafeSummaryAndSensitiveOutputIsWithheld() {
        RecordingChannel channel = new RecordingChannel(List.of());
        MainService.taskChannel = channel;
        OpsTask regular = newShellTask(811, "printf synthetic-error-secret >&2; exit 9");
        assertTrue(!MainService.runOpsTask(regular));
        assertEquals(TaskState.FAILED.getValue(), regular.getState());
        assertEquals("Command exited with code 9.", regular.getTaskError());
        assertNull(regular.getTaskResult());

        OpsTask sensitive = newShellTask(812, "printf synthetic-error-secret >&2; exit 9");
        sensitive.getTaskData().setSensitiveOutput(true);
        assertTrue(!MainService.runOpsTask(sensitive));
        assertEquals("task execution failed.", sensitive.getTaskError());
        assertEquals("result withheld: task uses secrets.", sensitive.getTaskResult());
        assertEquals(2, channel.reports.size());
    }

    /**
     * 构造升级任务（三 id 全 0）。
     */
    private OpsTask newUpgradeTask(long id, String script) {
        OpsTask task = new OpsTask();
        task.setId(id);
        task.setPlanId(0L);
        task.setContainerId(0L);
        task.setClusterId(0L);
        TaskData taskData = new TaskData();
        taskData.setExecType(StructuredTaskExecutor.EXEC_TYPE_SHELL);
        TaskData.ShellSection shell = new TaskData.ShellSection();
        shell.setScript(script);
        taskData.setShell(shell);
        task.setTaskData(taskData);
        return task;
    }

    /**
     * 内存任务通道桩：队列按序出队（每 claim 一条，模拟现有单条 CAS 领取），记录领取尝试次数/
     * 实领任务数与上报载荷（上报全成功，不触发重试路径）。
     */
    static class RecordingChannel implements MainService.TaskChannel {

        final Queue<OpsTask> queue = new ConcurrentLinkedQueue<>();
        final List<OpsTask> reports = new CopyOnWriteArrayList<>();
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger taskClaims = new AtomicInteger();

        RecordingChannel(List<OpsTask> tasks) {
            queue.addAll(tasks);
        }

        @Override
        public List<OpsTask> claim() {
            claims.incrementAndGet();
            OpsTask task = queue.poll();
            if (task == null) {
                return Collections.emptyList();
            }
            taskClaims.incrementAndGet();
            return Collections.singletonList(task);
        }

        @Override
        public void report(OpsTask opsTask) {
            reports.add(opsTask);
        }

        OpsTask pollReportById(long id) {
            for (OpsTask report : reports) {
                if (report.getId() == id) {
                    return report;
                }
            }
            return null;
        }
    }

    /**
     * 等待上报数达到 expected（上限 15s），返回是否达标。
     */
    private static boolean awaitReports(RecordingChannel channel, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (channel.reports.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return channel.reports.size() >= expected;
    }

    /**
     * 等待在途任务数归零（上报发生在池任务 finally 之前，归零比上报更晚，需单独等待）。
     */
    private static void awaitIdlePool() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (MainService.activeTasks.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void claimLoopFillsSlotsAndPoolExecutesConcurrentlyKeepingClaimedExecuteDate() throws Exception {
        //4 条 sleep 2 任务：串行下限 8s，并发(默认4槽)约 2s——断言全部上报在 6s 内完成。
        long claimedStamp = System.currentTimeMillis() - 60_000;
        List<OpsTask> tasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            OpsTask task = newShellTask(300L + i, "sleep 2");
            //领取响应下发的 executeDate=领取时刻（60s 前），上报应原样回传（不覆写）。
            task.setExecuteDate(new Date(claimedStamp));
            tasks.add(task);
        }
        RecordingChannel channel = new RecordingChannel(tasks);
        MainService.taskChannel = channel;

        long roundStart = System.currentTimeMillis();
        new MainService.ProcessOpsTask().run();

        //一轮循环领取：池空起步，连续领 4 条填满槽位后才返回。
        assertEquals(4, channel.claims.get(), "claim loop must claim all 4 tasks in one round");
        assertTrue(awaitReports(channel, 4), "all 4 tasks must be reported, got: " + channel.reports.size());
        long elapsed = System.currentTimeMillis() - roundStart;

        //并发消化：全部完成耗时远小于串行下限(4×2s)。
        assertTrue(elapsed < 6_000, "4 concurrent sleep-2 tasks must finish well under serial bound 8s, took " + elapsed + "ms");
        //上报原样携带领取时刻（runOpsTask 不覆写）：恒等于领取下发的值——center 侧行内值与
        //回传值同源（center 时钟），比对不依赖跨机器墙钟。
        for (OpsTask report : channel.reports) {
            assertEquals(claimedStamp, report.getExecuteDate().getTime(),
                    "reported executeDate must be the claimed value verbatim, never overwritten");
            assertEquals(TaskState.SUCCEED.getValue(), report.getState());
        }
    }

    @Test
    void upgradeGateHoldsClaimWhilePoolBusyAndRunsAfterDrainThenExits() throws Exception {
        //队头普通任务(长) + 队列第二位升级任务。
        OpsTask normal = newShellTask(401L, "sleep 2");
        OpsTask upgrade = newUpgradeTask(402L, "echo upgrade-ok");
        long claimedStamp = System.currentTimeMillis() - 60_000;
        upgrade.setExecuteDate(new Date(claimedStamp));
        RecordingChannel channel = new RecordingChannel(List.of(normal, upgrade));
        MainService.taskChannel = channel;
        AtomicBoolean exitCalled = new AtomicBoolean(false);
        MainService.exitAction = () -> exitCalled.set(true);

        //第一轮：领到普通任务提交执行池，随后领到升级任务——池忙，不提交也不丢弃，暂存并停止领取。
        new MainService.ProcessOpsTask().run();
        assertEquals(2, channel.claims.get(), "round 1 claims normal then upgrade task");
        assertNotNull(MainService.pendingUpgradeTask, "upgrade task claimed while pool busy must be held, not dropped");
        assertEquals(402L, MainService.pendingUpgradeTask.getId());
        assertTrue(channel.reports.isEmpty(), "upgrade task must not execute while pool is busy");

        //第二轮：暂存升级任务在身 + 池忙 → 本轮不领任何任务（队列空也不再领取）。
        new MainService.ProcessOpsTask().run();
        assertEquals(2, channel.claims.get(), "busy pool must block all claiming in the round");
        assertTrue(channel.reports.isEmpty() || channel.reports.size() == 1);

        //等池排空后第三轮：暂存的升级任务同步执行→上报（原样携带领取时刻）→优雅停机→退出动作。
        assertTrue(awaitReports(channel, 1), "normal task must finish");
        awaitIdlePool();
        new MainService.ProcessOpsTask().run();

        assertTrue(exitCalled.get(), "successful upgrade must trigger process exit action");
        OpsTask upgradeReport = channel.pollReportById(402L);
        assertNotNull(upgradeReport, "held upgrade task must execute and report after pool drains");
        assertEquals(TaskState.SUCCEED.getValue(), upgradeReport.getState());
        assertEquals(claimedStamp, upgradeReport.getExecuteDate().getTime(),
                "upgrade report must carry the claimed executeDate verbatim");
        assertNull(MainService.pendingUpgradeTask, "held upgrade task must be cleared after execution");
        //普通任务先于升级任务上报（升级独占执行，池排空后才跑）。
        assertNotNull(channel.pollReportById(401L));
    }

    @Test
    void slotFullStopsClaimingUntilFreed() throws Exception {
        //并发度压到 2：3 条任务，第一轮只能领 2 条（槽满即停），第三条等槽释放后下一轮补领。
        MainService.maxConcurrentTasks = 2;
        List<OpsTask> tasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            tasks.add(newShellTask(500L + i, "sleep 1"));
        }
        RecordingChannel channel = new RecordingChannel(tasks);
        MainService.taskChannel = channel;

        new MainService.ProcessOpsTask().run();
        assertEquals(2, channel.claims.get(), "claim loop must stop when all slots are taken");
        assertEquals(2, MainService.activeTasks.get(), "both slots must be occupied");

        //等待两条任务完成（槽释放）。
        assertTrue(awaitReports(channel, 2), "first two tasks must finish");

        //下一轮：池已排空，补领第三条（领到后队列已空，循环会再做一次空探测后停）。
        new MainService.ProcessOpsTask().run();
        assertEquals(3, channel.taskClaims.get(), "next round must claim the remaining task");
        assertTrue(awaitReports(channel, 3), "third task must finish");
    }

    @Test
    void noSecretSkipsClaiming() {
        MainService.secretReady = () -> false;
        RecordingChannel channel = new RecordingChannel(List.of(newShellTask(601L, "echo x")));
        MainService.taskChannel = channel;
        new MainService.ProcessOpsTask().run();
        assertEquals(0, channel.claims.get(), "no secret must skip task claiming entirely");
    }

    @Test
    void runOpsTaskKeepsClaimedExecuteDateVerbatim() {
        //契约单测：runOpsTask 把领取响应下发的 executeDate 原样随上报回传（不覆写）——
        //center 侧迟到判定为"上报 executeDate >= 行内 execute_date 即接受"，行内值与回传值
        //同源（center 时钟），比对不依赖跨机器墙钟（agent 墙钟覆写会让慢时钟主机被误拒）。
        OpsTask task = newShellTask(701L, "echo contract");
        Date claimed = new Date(System.currentTimeMillis() - 120_000);
        task.setExecuteDate(claimed);
        RecordingChannel channel = new RecordingChannel(List.of());
        MainService.taskChannel = channel;

        assertTrue(MainService.runOpsTask(task));
        assertEquals(1, channel.reports.size());
        OpsTask report = channel.reports.get(0);
        assertEquals(claimed.getTime(), report.getExecuteDate().getTime(),
                "executeDate must be the claimed value verbatim, never overwritten");
        assertEquals(TaskState.SUCCEED.getValue(), report.getState());
    }

    @Test
    void upgradeClaimedOnIdlePoolRunsImmediatelyAndExits() {
        //池空时领到升级任务（队头）：本线程同步执行，成功上报后触发退出动作。
        RecordingChannel channel = new RecordingChannel(List.of(newUpgradeTask(801L, "echo upgrade-now")));
        MainService.taskChannel = channel;
        AtomicBoolean exitCalled = new AtomicBoolean(false);
        MainService.exitAction = () -> exitCalled.set(true);

        new MainService.ProcessOpsTask().run();

        assertTrue(exitCalled.get(), "upgrade on idle pool must run and exit in the same round");
        assertEquals(1, channel.claims.get());
        OpsTask report = channel.reports.get(0);
        assertEquals(801L, report.getId());
        assertEquals(TaskState.SUCCEED.getValue(), report.getState());
    }
}
