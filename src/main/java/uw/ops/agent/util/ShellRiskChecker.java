package uw.ops.agent.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 任务脚本安全检查工具。
 */
public final class ShellRiskChecker {

    private ShellRiskChecker() {
    }

    /**
     * 危险命令模式列表。
     * 匹配到任一模式则拒绝执行。
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 递归删除根目录
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?(-[a-zA-Z]*r[a-zA-Z]*\\s+)?/\\s*(;|&&|\\||$)", Pattern.MULTILINE),
            Pattern.compile("rm\\s+(-[a-zA-Z]*r[a-zA-Z]*\\s+)?(-[a-zA-Z]*f[a-zA-Z]*\\s+)?/\\s*(;|&&|\\||$)", Pattern.MULTILINE),
            Pattern.compile("rm\\s+-rf\\s+/*\\s*", Pattern.MULTILINE),
            // 格式化磁盘
            Pattern.compile("\\bmkfs\\.?\\w*\\b"),
            // dd 写零/随机数据到磁盘
            Pattern.compile("dd\\s+.*if=/dev/(zero|random|urandom)"),
            // fork bomb
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|\\s*:&\\s*\\}"),
            // 系统关机/重启
            Pattern.compile("\\b(shutdown|reboot|poweroff|halt)\\b"),
            Pattern.compile("\\binit\\s+[0-9sS]\\b"),
            // 递归修改根目录权限
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile("chown\\s+-R\\s+.*\\s+/($|\\s)")
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
