package uw.ops.agent.util;


import com.sun.jna.Platform;
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

    private static final Map<String, String> DEFAULT_ENV = getDefaultEnv();
    /** 主调度池最多并发四个任务，每条命令最多需要两个输出读取器。 */
    private static final int OUTPUT_READER_POOL_SIZE = 8;
    private static final long OUTPUT_READER_KEEP_ALIVE_MINUTES = 5;
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
     * 获取默认的环境。
     *
     * @return
     */
    private static Map<String, String> getDefaultEnv() {
        Map<String, String> envMap = new HashMap<>();
        if (Platform.isWindows()) {
            envMap.put("LANGUAGE", "C");
        } else {
            envMap.put("LC_ALL", "C");
        }
        return envMap;
    }

    /**
     * 运行数组参数的指令，使用默认环境变量，不重定向错误流。
     *
     * @param cmdToRunWithArgs
     * @return
     */
    public static List<String> runNative(String[] cmdToRunWithArgs) {
        return runNative(cmdToRunWithArgs, DEFAULT_ENV, false);
    }

    /**
     * 运行数组参数的指令，使用默认环境变量，可设定重定向错误流。
     *
     * @param cmdToRunWithArgs
     * @return
     */
    public static List<String> runNative(String[] cmdToRunWithArgs, boolean redirectErrorStream) {
        return runNative(cmdToRunWithArgs, DEFAULT_ENV, redirectErrorStream);
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
                    () -> getProcessOutput(runningProcess.getInputStream(), cmdToRunWithArgs));
            stderrFuture = redirectErrorStream ? null : OUTPUT_READER_EXECUTOR.submit(
                    () -> getProcessOutput(runningProcess.getErrorStream(), cmdToRunWithArgs));

            boolean completed = timeoutMillis > 0
                    ? process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    : waitWithoutTimeout(process);
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

    private static boolean waitWithoutTimeout(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private static List<String> awaitOutput(Future<List<String>> outputFuture, String[] command) throws InterruptedException {
        try {
            return outputFuture.get();
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
     * 读取进程输出流为行列表(用于 runNative 的 stdout/stderr 读取)。
     *
     * @param is  进程输入流
     * @param cmd  关联的指令(仅用于异常日志)
     * @return 输出行列表
     */
    private static List<String> getProcessOutput(InputStream is, String[] cmd) {
        ArrayList<String> sa = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sa.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Problem reading output from command: " + Arrays.toString(cmd), e);
        }
        return sa;
    }

}
