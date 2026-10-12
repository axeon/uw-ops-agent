package uw.ops.agent.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellCmdUtilsTest {

    @Test
    void shouldCaptureSuccessfulCommandOutput() {
        List<String> output = ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "printf 'first\\nsecond\\n'"}, null, true, 5_000);

        assertEquals(List.of("first", "second"), output);
    }

    @Test
    void shouldRejectNonZeroExitCode() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "echo failed >&2; exit 7"}, null, true, 5_000));

        assertTrue(error.getMessage().contains("code 7"));
        assertFalse(error.getMessage().contains("echo failed"));
        assertFalse(error.getMessage().contains("Output:"));
    }

    @Test
    void commandFailureNeverIncludesScriptOrOutputSecrets() {
        String secret = "synthetic-script-secret";
        RuntimeException exit = assertThrows(RuntimeException.class, () -> ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "echo " + secret + "; exit 9"}, null, true, 5_000));
        assertFalse(exit.toString().contains(secret));
        RuntimeException timeout = assertThrows(RuntimeException.class, () -> ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "echo " + secret + "; sleep 2"}, null, true, 30));
        assertFalse(timeout.toString().contains(secret));
    }

    @Test
    void shouldEnforceTimeoutBeforeOutputStreamCloses() {
        long startNanos = System.nanoTime();
        RuntimeException error = assertThrows(RuntimeException.class, () -> ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "sleep 2; echo completed"}, null, true, 50));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(error.getMessage().contains("timed out"));
        assertTrue(elapsedMillis < 1_000, "timeout took " + elapsedMillis + "ms");
        assertEquals(List.of("recovered"), ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "echo recovered"}, null, true, 5_000));
    }

    @Test
    void shouldDiscardLargeStderrWithoutDeadlocking() {
        //stderr 约 200KB（远超管道容量 64KB）：直连内核丢弃目标，不建管道、无写端阻塞，
        //也不能渗入 stdout 结果（回归防线：删掉 DISCARD 重定向即写端阻塞假超时）。
        List<String> output = ShellCmdUtils.runNative(new String[]{"/bin/sh", "-c", """
                i=0
                while [ $i -lt 5000 ]; do
                  echo 'stderr-buffer-padding-012345678901234567890123456789' >&2
                  i=$((i + 1))
                done
                echo completed
                """}, null, false, 5_000);

        assertEquals(List.of("completed"), output);
    }

    @Test
    void shouldFailWhenBackgroundProcessHoldsOutputPipe() {
        //进程秒退（waitFor 立即 true）但后台孙进程继承管道写端且不重定向：
        //readLine 永久阻塞——排水预算必须让它失败而非挂死任务轮询线程（主机脱控）。
        RuntimeException error = assertThrows(RuntimeException.class, () -> ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "sleep 30 & echo done"}, null, true, 5_000));
        assertTrue(error.getMessage().contains("drain timeout"), error.getMessage());
    }

    @Test
    void shouldKeepOutputReadersOffPlatformThreads() {
        //读取器为虚拟线程（Thread.getAllStackTraces 只含平台线程）：连跑多条命令后
        //不得残留任何平台读取线程——排水不占调度线程资源，也无线程池复用记账。
        String[] command = new String[]{"/bin/sh", "-c", "sleep 0.1; echo completed"};
        for (int i = 0; i < 5; i++) {
            ShellCmdUtils.runNative(command, null, false, 5_000);
        }

        assertTrue(outputReaderThreadIds().isEmpty(), "output readers should be virtual threads");
    }

    @Test
    void shouldTruncateRunawayOutputAtMemoryGuard() {
        //约 2M 字符（>1M 失控防线）的输出：以标记行收尾截断，不整量读入内存。
        List<String> output = ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "yes 'padding-0123456789' | head -n 120000"}, null, true, 5_000);

        assertTrue(output.size() < 120_000);
        assertTrue(output.get(output.size() - 1).contains("[output truncated at"), output.get(output.size() - 1));
    }

    private static Set<Long> outputReaderThreadIds() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("shell-output-reader-"))
                .map(Thread::threadId)
                .collect(Collectors.toSet());
    }
}
