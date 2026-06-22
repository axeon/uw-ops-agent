package uw.ops.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 鉴权密钥本地存储。
 * <p>
 * 密钥文件 /etc/uw-ops-agent/agent.secret，权限 0400 owner=root。
 * 写入采用 原子 rename(tmp -> target)，避免半写状态。
 * 可通过环境变量 OPS_AGENT_SECRET_FILE 覆盖路径(便于测试)。
 */
public final class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    /**
     * 默认密钥文件路径。
     */
    private static final Path DEFAULT_PATH = Paths.get("/etc/uw-ops-agent/agent.secret");

    /**
     * 密钥文件路径(可通过环境变量覆盖)。
     */
    private static final Path SECRET_PATH = initSecretPath();

    /**
     * 内存中缓存的密钥(进程启动时从文件加载)。
     */
    private static volatile String cachedSecret;

    private SecretStore() {
    }

    private static Path initSecretPath() {
        String override = System.getenv("OPS_AGENT_SECRET_FILE");
        return override != null && !override.isEmpty() ? Paths.get(override) : DEFAULT_PATH;
    }

    /**
     * 获取密钥文件路径。
     */
    public static Path getSecretPath() {
        return SECRET_PATH;
    }

    /**
     * 从文件加载密钥(仅在内存缓存为空时读盘)。
     *
     * @return 密钥明文，不存在时返回 null
     */
    public static synchronized String load() {
        if (cachedSecret != null) {
            return cachedSecret;
        }
        try {
            if (Files.exists(SECRET_PATH)) {
                String content = new String(Files.readAllBytes(SECRET_PATH), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    cachedSecret = content;
                    return cachedSecret;
                }
            }
        } catch (IOException e) {
            log.error("load agent secret failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 原子保存密钥。
     * 先写 tmp 文件、fsync、设置权限、再 rename 到目标路径。
     *
     * @param secret 密钥明文
     */
    public static synchronized void save(String secret) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        try {
            Path parent = SECRET_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path tmp = SECRET_PATH.resolveSibling(SECRET_PATH.getFileName() + ".tmp");
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, bytes);
            try {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(tmp, perms);
            } catch (UnsupportedOperationException e) {
                // 非POSIX文件系统(如Windows测试环境)，跳过权限设置。
            }
            Files.move(tmp, SECRET_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            cachedSecret = secret;
            log.info("agent secret saved.");
        } catch (IOException e) {
            log.error("save agent secret failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 清空密钥：删除本地密钥文件并清空内存缓存。
     * 用于 Center 下发 host-disabled 标记(主机被禁用)时，Agent 回到无密钥初始态，
     * 停止拉取任务，等待 Center 重新审核后再次领取。
     */
    public static synchronized void clear() {
        cachedSecret = null;
        try {
            java.nio.file.Files.deleteIfExists(SECRET_PATH);
            log.info("agent secret cleared (host disabled by center).");
        } catch (IOException e) {
            log.error("clear agent secret failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 清除内存缓存(强制下次 load 重新读盘，主要用于测试或重置)。
     */
    static synchronized void clearCache() {
        cachedSecret = null;
    }
}
