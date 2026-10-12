package uw.ops.agent.util;



import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * shell 命令工具类：受控子进程执行（任务脚本、就绪探测脚本的底层执行器）。
 * <p>
 * stdout 经虚拟线程排水（上限为失控脚本防线，超限继续读丢弃防写端管道阻塞）；
 * stderr 不进结果，未合并时直连内核丢弃目标（不建管道，无需排水）；超时终止整棵进程树；
 * 退出码非 0 抛 {@link CommandException}（固定摘要，不含命令/输出/环境变量）。读取器
 * 并发天然受任务并发封顶，无需池容量记账。
 */
public final class ShellCmdUtils {

    /** 固定诊断摘要，不包含命令、环境变量、输出或底层异常详情。 */
    public static final class CommandException extends RuntimeException {
        private CommandException(String summary) {
            super(summary);
        }
    }

    /**
     * 进程退出后的输出排水预算(毫秒)：正常脚本瞬时排完（进程退出后管道积压上限 64KB，
     * 毫秒级排空）；后台孙进程持有管道写端（如 nohup xxx & 未重定向）时 readLine
     * 永久阻塞——必须设限，否则任务轮询线程永久挂死导致主机脱控（上报照常、任务不再执行）。
     * 注意本预算在 waitFor 之后才开始，与脚本执行超时（分钟级）无关。
     */
    private static final long OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L;

    /**
     * stdout 字符上限：落库列 ops_deploy_task.task_result 已是 longtext（不再是瓶颈），
     * 此上限仅作为失控防线——误执行 yes 类命令时超时窗内可产出海量输出，全量读入会撑爆
     * agent 内存与上报报文。正常部署脚本输出远低于此值；超限截断并追加标记行。
     */
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    /**
     * stdout 排水读取器（虚拟线程，每命令一个）：排水必须与执行超时并发——不排水则输出超
     * 管道容量后子进程阻塞在写端永不退出（假超时误杀），readLine 阻塞期间又无法 waitFor
     * 超时。虚拟线程无常驻成本，读取器并发天然受任务并发封顶。
     */
    private static final ExecutorService OUTPUT_READER_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("shell-output-reader-", 0).factory());

    private ShellCmdUtils() {
    }

    /**
     * 运行数组参数的指令（不限时），可指定环境变量与错误流重定向。
     *
     * @param cmdToRunWithArgs     命令与参数数组
     * @param envMap               附加环境变量（null=不追加）
     * @param redirectErrorStream  true=stderr 并入 stdout，false=stderr 丢弃（不进结果）
     * @return stdout 行列表（超限截断并追加标记行）
     */
    public static List<String> runNative(String[] cmdToRunWithArgs, Map<String, String> envMap, boolean redirectErrorStream) {
        return runNative(cmdToRunWithArgs, envMap, redirectErrorStream, 0);
    }

    /**
     * 运行数组参数的指令，带超时控制(毫秒，0表示不超时)。
     * 超时后会终止子进程树并抛出异常，防止脚本 hang 住拖垮调度线程。
     *
     * @param cmdToRunWithArgs     命令与参数数组
     * @param envMap               附加环境变量（null=不追加）
     * @param redirectErrorStream  true=stderr 并入 stdout，false=stderr 丢弃（不进结果）
     * @param timeoutMillis        执行超时毫秒数，0 表示不超时
     * @return stdout 行列表（超限截断并追加标记行）
     * @throws CommandException 启动失败/超时/非零退出码/输出排水超时
     */
    public static List<String> runNative(String[] cmdToRunWithArgs, Map<String, String> envMap, boolean redirectErrorStream, long timeoutMillis) {
        Process process = null;
        Future<List<String>> stdoutFuture = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdToRunWithArgs);
            if (envMap != null) {
                pb.environment().putAll(envMap);
            }
            pb.redirectErrorStream(redirectErrorStream);
            if (!redirectErrorStream) {
                //stderr 不进结果：直连内核丢弃目标，不建管道——无需排水线程，也免后台孙进程
                //持有 stderr 管道写端引发的排水超时与 SIGPIPE。
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            process = pb.start();

            Process runningProcess = process;
            stdoutFuture = OUTPUT_READER_EXECUTOR.submit(
                    () -> getProcessOutput(runningProcess.getInputStream(), MAX_OUTPUT_CHARS));

            boolean completed;
            if (timeoutMillis > 0) {
                completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                completed = true;
            }
            if (!completed) {
                terminateProcessTree(process);
                throw new CommandException("Command timed out after " + timeoutMillis + "ms.");
            }

            List<String> stdout = awaitOutput(stdoutFuture);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new CommandException("Command exited with code " + exitCode + ".");
            }
            return stdout;
        } catch (IOException e) {
            throw new CommandException("Command could not be started.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandException("Command interrupted.");
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    terminateProcessTree(process);
                }
                closeProcessStreams(process);
            }
            cancelOutputRead(stdoutFuture);
        }
    }

    /**
     * 取消未完成的输出读取任务（进程被终止后的兜底清理）。
     */
    private static void cancelOutputRead(Future<List<String>> outputFuture) {
        if (outputFuture != null && !outputFuture.isDone()) {
            outputFuture.cancel(true);
        }
    }

    /**
     * 等待输出读取完成（进程已退出后的管道排水，预算 {@link #OUTPUT_DRAIN_TIMEOUT_MILLIS}）。
     */
    private static List<String> awaitOutput(Future<List<String>> outputFuture) throws InterruptedException {
        try {
            //排水预算：进程已退出，正常输出毫秒级排完；超时=后台孙进程持有管道（脚本未重定向后台进程），
            //抛错让本任务失败（输出不可回收），绝不能无限等待挂死任务轮询线程。
            return outputFuture.get(OUTPUT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            outputFuture.cancel(true);
            throw new CommandException("Output drain timeout after " + OUTPUT_DRAIN_TIMEOUT_MILLIS
                    + "ms (redirect stdout/stderr for background jobs).");
        } catch (ExecutionException e) {
            throw new CommandException("Command output could not be read.");
        }
    }

    /**
     * 终止整棵进程树：先逆序杀后代（孙进程先于子进程），最后杀主进程（防孤儿化的后台进程继续占用管道）。
     */
    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
    }

    /**
     * 关闭进程全部标准流（finally 兜底，防文件描述符泄漏）。
     */
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
     * 读取进程输出流为行列表(用于 runNative 的 stdout 读取)：累计字符超限后<b>继续读但丢弃</b>
     * （读不停止，存有上限）——停止读会让写端管道满：进程写阻塞挂到执行超时被杀、或收到 SIGPIPE
     * 直接 141 退出，大输出但正常结束的脚本被误杀；继续读丢弃则任务成功且结果截断。
     * 上限为失控脚本防线。
     *
     * @param is       进程输入流
     * @param maxChars 累计字符上限
     * @return 输出行列表
     */
    private static List<String> getProcessOutput(InputStream is, int maxChars) {
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
            throw new CommandException("Command output could not be read.");
        }
        return sa;
    }

}
