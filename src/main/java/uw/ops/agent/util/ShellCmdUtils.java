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
import java.util.*;

/**
 * shell命令工具类。
 */
public final class ShellCmdUtils {

    private static final Logger log = LoggerFactory.getLogger(ShellCmdUtils.class);

    private static final Map<String, String> DEFAULT_ENV = getDefaultEnv();

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
     * 运行本地指令。
     */
    public static List<String> runNative(String cmdToRun) {
        String[] cmd = cmdToRun.split(" ");
        return runNative(cmd, DEFAULT_ENV, false);
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
        Process process = null;
        List<String> infoList = null, errorList = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdToRunWithArgs);
            if (envMap != null) {
                pb.environment().putAll(envMap);
            }
            pb.redirectErrorStream(redirectErrorStream);
            process = pb.start();
            infoList = getProcessOutput(process.getInputStream(), cmdToRunWithArgs);
            if (redirectErrorStream) {
                errorList = getProcessOutput(process.getErrorStream(), cmdToRunWithArgs);
            }
            process.waitFor();
        } catch (Throwable e) {
            String errorInfo = "Couldn't run command:" + Arrays.toString(cmdToRunWithArgs) + ". Exception info: " + e.getMessage();
            log.error(errorInfo, e);
            throw new RuntimeException(errorInfo);
        } finally {
            // Ensure all resources are released
            if (process != null) {
                // Windows and Solaris don't close descriptors on destroy,
                // so we must handle separately
                if (Platform.isWindows() || Platform.isSolaris()) {
                    try {
                        process.getOutputStream().close();
                    } catch (IOException e) {
                        // do nothing on failure
                    }
                    try {
                        process.getInputStream().close();
                    } catch (IOException e) {
                        // do nothing on failure
                    }
                    try {
                        process.getErrorStream().close();
                    } catch (IOException e) {
                        // do nothing on failure
                    }
                }
                process.destroy();
            }
        }
        if (errorList != null && errorList.size() > 0) {
            throw new RuntimeException("Error Message: " + StringUtils.join(errorList, "\n"));
        }
        return infoList;
    }

    private static List<String> getProcessOutput(InputStream is, String[] cmd) {
        ArrayList<String> sa = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sa.add(line);
            }
        } catch (Throwable e) {
            String errorInfo = "Problem reading output from command:" + Arrays.toString(cmd) + ". Exception info: " + e.getMessage();
            log.error(errorInfo, e);
            throw new RuntimeException(errorInfo);
        }
        return sa;
    }

    /**
     * Return first line of response for selected command.
     *
     * @param cmd2launch String command to be launched
     * @return String or empty string if command failed
     */
    public static String getFirstAnswer(String cmd2launch) {
        return getAnswerAt(cmd2launch, 0);
    }

    /**
     * Return response on selected line index (0-based) after running selected command.
     *
     * @param cmd2launch String command to be launched
     * @param answerIdx  int index of line in response of the command
     * @return String whole line in response or empty string if invalid index or running of command fails
     */
    public static String getAnswerAt(String cmd2launch, int answerIdx) {
        List<String> sa = ShellCmdUtils.runNative(cmd2launch);
        if (answerIdx >= 0 && answerIdx < sa.size()) {
            return sa.get(answerIdx);
        }
        return "";
    }

}