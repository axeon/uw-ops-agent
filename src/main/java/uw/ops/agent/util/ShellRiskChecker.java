package uw.ops.agent.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 检测过滤危险的shell指令。
 */
public class ShellRiskChecker {

    private ShellRiskChecker() {
    }

    /**
     * 危险命令模式列表。
     * 匹配到任一模式则拒绝执行。
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 递归删除根目录（含路径前缀如 /bin/rm；两个可选组覆盖 -f -r /, -r -f /, -rf /, -fr / 等所有顺序）
            Pattern.compile("(.*/)?rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?(-[a-zA-Z]*r[a-zA-Z]*\\s+)?/\\s*(;|&&|\\|\\||$)", Pattern.MULTILINE),
            // 格式化磁盘
            Pattern.compile("\\bmkfs\\.?\\w*\\b"),
            // dd 写零/随机数据到磁盘设备（两种参数顺序都检测）
            Pattern.compile("dd\\s+.*if=/dev/(zero|random|urandom).*of=/dev/"),
            Pattern.compile("dd\\s+.*of=/dev/.*if=/dev/(zero|random|urandom)"),
            // fork bomb
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|\\s*:&\\s*\\}"),
            // 关闭服务器（不允许关机，允许重启）
            Pattern.compile("(.*/)?(shutdown|poweroff|halt)\\b"),
            Pattern.compile("(.*/)?(init|telinit)\\s+0\\b"),
            // 递归修改根目录权限/属主
            Pattern.compile("(.*/)?chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile("(.*/)?chown\\s+-R\\s+.*\\s+/($|\\s)"),
            // base64/xxd 解码后管道到 shell（典型攻击向量）
            Pattern.compile("base64\\s+(-d|--decode)\\s*\\|\\s*(ba)?sh"),
            Pattern.compile("xxd\\s+(-r|-r\\s+-p)\\s*\\|\\s*(ba)?sh"),
            // 反引号/$() 命令替换中包含破坏性命令
            Pattern.compile("`.*\\b(rm\\s+-rf|mkfs|dd\\s+.*of=/dev/|shutdown|poweroff|halt)\\b.*`", Pattern.MULTILINE),
            Pattern.compile("\\$\\(.*\\b(rm\\s+-rf|mkfs|dd\\s+.*of=/dev/|shutdown|poweroff|halt)\\b.*\\)", Pattern.MULTILINE),
            // 覆写关键系统文件
            Pattern.compile(">{1,2}\\s*/etc/(passwd|shadow|sudoers|crontab)\\b"),
            Pattern.compile("\\btee\\s+/etc/(passwd|shadow|sudoers|crontab)\\b")
    );

    /**
     * 检查脚本是否包含危险命令。
     *
     * @param script 脚本内容
     * @return 危险命令描述，null表示安全
     */
    public static String checkRisk(String script) {
        if (script == null || script.isEmpty()) {
            return null;
        }
        String normalized = script.toLowerCase().trim();
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return "脚本包含危险命令，匹配模式: " + pattern.pattern();
            }
        }
        return null;
    }
}
