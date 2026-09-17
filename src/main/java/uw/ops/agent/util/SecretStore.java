package uw.ops.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.ops.agent.constant.Constants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/**
 * Agent 鉴权密钥本地存储。
 * <p>
 * 密钥文件 /etc/uw-ops-agent/agent.secret，权限 0600 owner=root。
 * 写入采用 原子 rename(tmp -> target)，避免半写状态。
 * 可通过环境变量 OPS_AGENT_SECRET_FILE 覆盖路径(便于测试)。
 */
public final class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    /**
     * 密钥文件路径(环境变量 OPS_AGENT_SECRET_FILE 可覆盖，收口在 Constants)。
     */
    private static final Path SECRET_PATH = initSecretPath();

    /**
     * 内存中缓存的密钥(进程启动时从文件加载)。
     */
    private static volatile String cachedSecret;

    private SecretStore() {
    }

    private static Path initSecretPath() {
        return Paths.get(Constants.getOpsAgentSecretFile());
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
     * 以 0600 属性创建 tmp（无明文窗口）→ 写入并 fsync → 原子 rename 到目标路径。
     *
     * @param secret 密钥明文
     * @return 保存成功返回 true
     */
    public static synchronized boolean save(String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        if (!writeSecret(SECRET_PATH, secret)) {
            return false;
        }
        cachedSecret = secret;
        log.info("agent secret saved.");
        return true;
    }

    static boolean writeSecret(Path secretPath, String secret) {
        Path tmp = secretPath.resolveSibling(secretPath.getFileName() + ".tmp");
        try {
            Path parent = secretPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            //以 0600 属性创建后写入并 fsync（Files.write 按 umask 创建存在短暂 0644 明文窗口）。
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            try (FileChannel channel = FileChannel.open(tmp,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    PosixFilePermissions.asFileAttribute(perms))) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            } catch (UnsupportedOperationException e) {
                //非POSIX文件系统(如Windows测试环境)：普通写入，权限设置跳过。
                Files.write(tmp, bytes);
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            Files.move(tmp, secretPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            log.error("save agent secret failed: {}", e.getMessage(), e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                log.warn("cleanup agent secret temp file failed: {}", cleanupError.getMessage());
            }
            return false;
        }
    }

    /**
     * 清空密钥：删除本地密钥文件并清空内存缓存。
     * 用于 Center 下发 host-disabled 标记(主机被禁用)时，Agent 回到无密钥初始态，
     * 停止拉取任务，等待 Center 重新审核后再次领取。
     */
    public static synchronized void clear() {
        //先删文件、成功后再清缓存：若先清缓存而删除失败，下次 load 会从磁盘读回密钥"复活"，
        //disable 停拉任务的意图失效且陷入 401→clear→复活的循环。
        try {
            Files.deleteIfExists(SECRET_PATH);
            cachedSecret = null;
            log.info("agent secret cleared (host disabled by center).");
        } catch (IOException e) {
            //删除失败保留缓存（保持已 disable 状态可被 load 观察到），下轮 clear 重试。
            log.error("clear agent secret failed, will retry on next round: {}", e.getMessage(), e);
        }
    }

    /**
     * 清除内存缓存(强制下次 load 重新读盘，主要用于测试或重置)。
     */
    static synchronized void clearCache() {
        cachedSecret = null;
    }
}
