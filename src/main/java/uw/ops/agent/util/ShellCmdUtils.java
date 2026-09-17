package uw.ops.agent.util;


import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * shell命令工具类。
 */
public final class ShellCmdUtils {

    private static final Logger log = LoggerFactory.getLogger(ShellCmdUtils.class);

    /** 主调度池最多并发四个任务，每条命令最多需要两个输出读取器。 */
    private static final int OUTPUT_READER_POOL_SIZE = 8;
    private static final long OUTPUT_READER_KEEP_ALIVE_MINUTES = 5;

    /**
     * 进程退出后的输出排水预算(毫秒)：正常脚本瞬时排完（进程退出后管道积压上限 64KB，
     * 毫秒级排空）；后台孙进程持有管道写端（如 nohup xxx & 未重定向）时 readLine
     * 永久阻塞——必须设限，否则任务轮询线程永久挂死导致主机脱控（上报照常、任务不再执行）。
     * 注意本预算在 waitFor 之后才开始，与脚本执行超时（分钟级）无关。
     */
    private static final long OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L;

    /**
     * stdout 字符上限：对齐 Center 落库列 ops_deploy_task.task_result 的 65535 字符
     * （超限塞库会 Data too long 导致上报链路整体失败），留余量取 60000；失控脚本
     * （如误执行 yes 类命令）在超时窗内的海量输出不再全量读入。超限截断并追加标记行。
     */
    private static final int MAX_OUTPUT_CHARS = 60_000;

    /**
     * stderr 字符上限（redirectErrorStream=false 时独立排水）：stderr 不进 taskResult，
     * 仅用于非零退出的错误诊断摘要——小容量即可，既防啰嗦脚本撑爆内存，又持续读
     * 防写端管道阻塞（截断即停止读会让大输出脚本收到 SIGPIPE 被误杀）。
     */
    private static final int STDERR_SAMPLE_CHARS = 5_000;

    private static final AtomicInteger OUTPUT_READER_THREAD_COUNTER = new AtomicInteger();
    private static final ExecutorService OUTPUT_READER_EXECUTOR = new ThreadPoolExecutor(
            0,
            OUTPUT_READER_POOL_SIZE,
            OUTPUT_READER_KEEP_ALIVE_MINUTES,
            TimeUnit.MINUTES,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable,
                        "shell-output-reader-" + OUTPUT_READER_THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private ShellCmdUtils() {
    }

    /**
     * 运行数组参数的指令，可以指定环境变量，可设定重定向错误流。
     *
     * @param cmdToRunWithArgs
     * @param envMap
     * @param redirectErrorStream
     * @return
     */
    public static List<String> runNative(String[] cmdToRunWithArgs, Map<String, String> envMap, boolean redirectErrorStream) {
        return runNative(cmdToRunWithArgs, envMap, redirectErrorStream, 0);
    }

    /**
     * 运行数组参数的指令，带超时控制(毫秒，0表示不超时)。
     * 超时后会终止子进程树并抛出异常，防止脚本 hang 住拖垮调度线程。
     *
     * @param cmdToRunWithArgs
     * @param envMap
     * @param redirectErrorStream
     * @param timeoutMillis
     * @return
     */
    public static List<String> runNative(String[] cmdToRunWithArgs, Map<String, String> envMap, boolean redirectErrorStream, long timeoutMillis) {
        Process process = null;
        Future<List<String>> stdoutFuture = null;
        Future<List<String>> stderrFuture = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdToRunWithArgs);
            if (envMap != null) {
                pb.environment().putAll(envMap);
            }
            pb.redirectErrorStream(redirectErrorStream);
            process = pb.start();

            Process runningProcess = process;
            stdoutFuture = OUTPUT_READER_EXECUTOR.submit(
                    () -> getProcessOutput(runningProcess.getInputStream(), cmdToRunWithArgs, MAX_OUTPUT_CHARS));
            stderrFuture = redirectErrorStream ? null : OUTPUT_READER_EXECUTOR.submit(
                    () -> getProcessOutput(runningProcess.getErrorStream(), cmdToRunWithArgs, STDERR_SAMPLE_CHARS));

            boolean completed;
            if (timeoutMillis > 0) {
                completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                completed = true;
            }
            if (!completed) {
                terminateProcessTree(process);
                throw new RuntimeException("Command timed out after " + timeoutMillis + "ms: " + Arrays.toString(cmdToRunWithArgs));
            }

            List<String> stdout = awaitOutput(stdoutFuture, cmdToRunWithArgs);
            List<String> stderr = stderrFuture == null ? List.of() : awaitOutput(stderrFuture, cmdToRunWithArgs);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                List<String> errorOutput = redirectErrorStream ? stdout : stderr;
                throw new RuntimeException("Command exited with code " + exitCode + ": "
                        + Arrays.toString(cmdToRunWithArgs) + formatOutput(errorOutput));
            }
            return stdout;
        } catch (IOException e) {
            String errorInfo = "Couldn't run command:" + Arrays.toString(cmdToRunWithArgs) + ". Exception info: " + e.getMessage();
            log.error(errorInfo, e);
            throw new RuntimeException(errorInfo, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Command interrupted: " + Arrays.toString(cmdToRunWithArgs), e);
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    terminateProcessTree(process);
                }
                closeProcessStreams(process);
            }
            cancelOutputRead(stdoutFuture);
            cancelOutputRead(stderrFuture);
        }
    }

    private static void cancelOutputRead(Future<List<String>> outputFuture) {
        if (outputFuture != null && !outputFuture.isDone()) {
            outputFuture.cancel(true);
        }
    }

    private static List<String> awaitOutput(Future<List<String>> outputFuture, String[] command) throws InterruptedException {
        try {
            //排水预算：进程已退出，正常输出毫秒级排完；超时=后台孙进程持有管道（脚本未重定向后台进程），
            //抛错让本任务失败（输出不可回收），绝不能无限等待挂死任务轮询线程。
            return outputFuture.get(OUTPUT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            outputFuture.cancel(true);
            throw new RuntimeException("Output drain timeout after " + OUTPUT_DRAIN_TIMEOUT_MILLIS
                    + "ms (background process holding the pipe? redirect stdout/stderr for background jobs): "
                    + Arrays.toString(command));
        } catch (ExecutionException e) {
            throw new RuntimeException("Problem reading output from command: " + Arrays.toString(command), e.getCause());
        }
    }

    private static String formatOutput(List<String> output) {
        return output.isEmpty() ? "" : ". Output: " + StringUtils.join(output, "\n");
    }

    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 读取进程输出流为行列表(用于 runNative 的 stdout/stderr 读取)：累计字符超限后<b>继续读但丢弃</b>
     * （读不停止，存有上限）——停止读会让写端管道满：进程写阻塞挂到执行超时被杀、或收到 SIGPIPE
     * 直接 141 退出，大输出但正常结束的脚本被误杀；继续读丢弃则任务成功且结果截断。
     * stdout 上限对齐 Center 落库列容量，stderr 仅留诊断摘要。
     *
     * @param is       进程输入流
     * @param cmd      关联的指令(仅用于异常日志)
     * @param maxChars 累计字符上限
     * @return 输出行列表
     */
    private static List<String> getProcessOutput(InputStream is, String[] cmd, int maxChars) {
        ArrayList<String> sa = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, Charset.defaultCharset()))) {
            int totalChars = 0;
            boolean truncated = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (truncated) {
                    //已超限：继续读防写端阻塞，内容丢弃。
                    continue;
                }
                totalChars += line.length() + 1;
                if (totalChars >= maxChars) {
                    sa.add("... [output truncated at " + maxChars + " chars, tail discarded]");
                    truncated = true;
                    continue;
                }
                sa.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Problem reading output from command: " + Arrays.toString(cmd), e);
        }
        return sa;
    }

}
