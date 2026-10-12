package uw.ops.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 检测过滤危险的shell指令（CMD 任务唯一本地防线——覆盖典型灾难性命令，
 * 不承诺全量防护；模式统一 CASE_INSENSITIVE，勿在 checkRisk 做大小写预处理，
 * 否则模式中的大写字面量（如 -R）永不匹配——历史 bug）。
 */
public class ShellRiskChecker {

    private ShellRiskChecker() {
    }

    /**
     * rm 的灾难性目标集：根目录 + 核心系统目录（含子路径，rm -rf /etc/nginx 同样灾难）
     * + 外围目录（/opt、/home、/run 仅拦目录本身——其子路径是发布删旧目录的合法常态）。
     * 不含 /tmp（构建清理常态）。整体非捕获组包裹：内含顶层交替，
     * 不包裹会在拼接处逃逸成独立分支（rm 前缀失效，mv /opt/x 也会被拦）。
     */
    private static final String ROOT_OR_SYSTEM_DIR = "(?:/(?:\\s|$|;|&|\\||\\*)"
            + "|/(?:etc|usr|var|bin|sbin|lib|lib64|boot|root|dev|proc|sys)(?:\\s|/|$|;|&|\\||\\*)"
            + "|/(?:opt|home|run)(?:\\s|$|;|&|\\||\\*))";

    /**
     * chmod/chown 的核心系统目录目标集：不含 /opt、/home、/run——应用部署目录的
     * 属主/权限调整是发布常态（chown deploy /opt/app/app.jar 合法），只拦真正的系统树。
     */
    private static final String CORE_SYSTEM_DIR = "(?:/(?:\\s|$|;|&|\\||\\*)|/(?:etc|usr|var|bin|sbin|lib|lib64|boot|root|dev|proc|sys)(?:\\s|/|$|;|&|\\||\\*))";

    /**
     * 关键账户/鉴权文件前缀：覆写向量的目标集。
     */
    private static final String SENSITIVE_ETC = "/etc/(?:passwd|shadow|sudoers|crontab|ssh_)";

    /**
     * 危险命令模式列表。
     * 匹配到任一模式则拒绝执行。
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = buildPatterns();

    private static List<Pattern> buildPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        int flags = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;
        // 递归删除根/系统目录（含 /bin/rm 前缀；(--?[\w-]+\s+)* 覆盖 -rf/-fr/-r -f/--no-preserve-root 等任意顺序重复；
        // 第二条覆盖 rm 与目标间夹普通参数的多参数形态，段内不含 ;|& 隔断符）
        patterns.add(Pattern.compile("\\brm\\s+(--?[\\w-]+\\s+)*" + ROOT_OR_SYSTEM_DIR, flags));
        patterns.add(Pattern.compile("\\brm\\s+[^;|&]*\\s(--?[\\w-]+\\s+)*" + ROOT_OR_SYSTEM_DIR, flags));
        // 格式化磁盘/块设备擦除
        patterns.add(Pattern.compile("\\bmkfs\\.?\\w*\\b", flags));
        patterns.add(Pattern.compile("\\b(wipefs|blkdiscard|mke2fs)\\b", flags));
        // dd 写零/随机数据到磁盘设备（if=/of= 两种顺序 + > /dev/sd* 重定向形态）
        patterns.add(Pattern.compile("\\bdd\\s+.*if=/dev/(zero|random|urandom).*of=/dev/", flags | Pattern.DOTALL));
        patterns.add(Pattern.compile("\\bdd\\s+.*of=/dev/.*if=/dev/(zero|random|urandom)", flags | Pattern.DOTALL));
        patterns.add(Pattern.compile("\\bdd\\b[^;|&]*>\\s*/dev/(sd|nvme|vd|hd|mapper)", flags));
        // fork bomb
        patterns.add(Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|\\s*:&\\s*\\}"));
        // 关闭服务器（不允许关机，允许重启）
        patterns.add(Pattern.compile("\\b(shutdown|poweroff|halt)\\b", flags));
        patterns.add(Pattern.compile("\\b(init|telinit)\\s+0\\b", flags));
        // 递归修改核心系统目录权限/属主（chmod 目标含子路径——系统树内 777 皆灾难）
        patterns.add(Pattern.compile("\\bchmod\\s+(--?[\\w-]+\\s+)*777\\s+" + CORE_SYSTEM_DIR, flags));
        patterns.add(Pattern.compile("\\bchown\\s+(--?[\\w-]+\\s+)*\\S+\\s+" + CORE_SYSTEM_DIR, flags));
        // 网络内容直接管道到 shell（典型攻击向量：curl/wget/base64/xxd）
        patterns.add(Pattern.compile("\\b(curl|wget)\\b[^|;&]*\\|\\s*(ba)?sh\\b", flags));
        patterns.add(Pattern.compile("\\bbase64\\s+(-d|--decode)\\s*\\|\\s*(ba)?sh", flags));
        patterns.add(Pattern.compile("\\bxxd\\s+(-r|-r\\s+-p)\\s*\\|\\s*(ba)?sh", flags));
        // 反引号/$() 命令替换中包含破坏性命令
        patterns.add(Pattern.compile("`.*\\b(rm\\s+(-\\w+\\s+)*-?\\S*/|mkfs|dd\\s+.*of=/dev/|shutdown|poweroff|halt)\\b.*`", flags));
        patterns.add(Pattern.compile("\\$\\(.*\\b(rm\\s+(-\\w+\\s+)*-?\\S*/|mkfs|dd\\s+.*of=/dev/|shutdown|poweroff|halt)\\b.*\\)", flags));
        // 覆写关键系统文件：重定向/tee（含 -a 选项）/cp/install/mv 三参数/dd of=/sed -i
        patterns.add(Pattern.compile(">{1,2}\\s*" + SENSITIVE_ETC + "\\S*", flags));
        patterns.add(Pattern.compile("\\btee\\s+(--?[\\w-]+\\s+)*" + SENSITIVE_ETC + "\\S*", flags));
        patterns.add(Pattern.compile("\\b(cp|install|mv)\\s+\\S+\\s+" + SENSITIVE_ETC + "\\S*", flags));
        patterns.add(Pattern.compile("\\bdd\\b[^;|&]*of=" + SENSITIVE_ETC, flags));
        patterns.add(Pattern.compile("\\bsed\\s+(-\\w+\\s+)*(-i|--in-place)\\b[^;|&]*" + SENSITIVE_ETC, flags));
        return List.copyOf(patterns);
    }

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
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(script).find()) {
                return "脚本包含危险命令，匹配模式: " + pattern.pattern();
            }
        }
        return null;
    }
}
