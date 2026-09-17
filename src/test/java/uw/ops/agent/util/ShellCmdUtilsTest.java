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
        assertTrue(error.getMessage().contains("failed"));
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
    void shouldDrainStderrWithoutDeadlocking() {
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
    void shouldReuseOutputReaderThreadsAcrossCommands() {
        String[] command = new String[]{"/bin/sh", "-c", "sleep 0.1; echo completed"};

        ShellCmdUtils.runNative(command, null, false, 5_000);
        Set<Long> initialThreadIds = outputReaderThreadIds();
        for (int i = 0; i < 10; i++) {
            ShellCmdUtils.runNative(command, null, false, 5_000);
        }

        assertFalse(initialThreadIds.isEmpty());
        assertEquals(initialThreadIds, outputReaderThreadIds());
    }

    private static Set<Long> outputReaderThreadIds() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("shell-output-reader-"))
                .map(Thread::threadId)
                .collect(Collectors.toSet());
    }
}
