package uw.ops.agent.util;

import uw.common.util.SystemClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络端口工具。用于在指定端口被占用时自动寻找下一个可用端口，支持短时缓存避免重复探测。
 */
public class NetworkUtils {

    /**
     * 端口检测的ttl。
     */
    private static final long CHECK_TTL = 300_000L;

    /**
     * 已被使用的端口map表。
     * key:port value:检测时间戳
     */
    private static final Map<Integer, Long> usedPortMap = new ConcurrentHashMap<>(100);

    /**
     * 检查端口是否处于"已使用"缓存（TTL 内的其他探测结果复用，避免重复 bind 探测）。
     * 未缓存的端口登记当前时间戳并视为未占用。
     *
     * @param port 端口号
     * @return 缓存命中且未过期为 true（视为已使用）
     */
    private static boolean checkPortCache(int port) {
        boolean exists = true;
        Long timestamp = usedPortMap.get(port);
        if (timestamp == null) {
            exists = false;
            usedPortMap.put(port, SystemClock.now());
        } else {
            if ((SystemClock.now() - timestamp) > CHECK_TTL) {
                exists = false;
                usedPortMap.remove(port);
            }
        }
        return exists;
    }

    /**
     * 根据输入端口号，递增查询可使用端口
     *
     * @param port 端口号
     * @return 可用端口
     */
    public static int getUsablePort(int port) throws IOException {
        while (port <= 65535) {
            //先检测端口缓存。
            if (checkPortCache(port)) {
                port++;
                continue;
            }
            //实际端口检测。
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException e) {
                port++;
            }
        }
        throw new IOException("No available port found");
    }

}
