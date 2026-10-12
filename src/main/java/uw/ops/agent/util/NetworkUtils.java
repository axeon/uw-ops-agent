package uw.ops.agent.util;

import uw.common.util.SystemClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机端口可用性探测工具（结构化 start 前的端口校验数据源）。
 * <p>
 * Center 的端口分配只汇总 ops_container_info 台账（受管容器），对非受管进程、手工容器、
 * 内核临时端口（ip_local_port_range）的本机占用不可见——台账空闲 ≠ 端口真的空闲。
 * 本工具用 bind 探测拿到内核视角的真实可用性：指定端口被占时自动递增寻找下一个可用端口。
 * <p>
 * 探测结果短时缓存（bind 失败的端口与已分配出的端口在 TTL 内不重复探测、不重复分配），
 * 同时承担同进程多端口名分配的互斥（bind 成功的端口 TTL 内不会被再次给出）。
 */
public class NetworkUtils {

    /**
     * 端口占用缓存的存活期(毫秒)：期内复用其他探测的结果，避免重复 bind 探测。
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
     * 从输入端口起逐个递增 bind 探测，返回首个可绑定的端口（含输入值本身：空闲即原样返回）。
     * synchronized：bind 探测非原子（探测即关闭），并发任务串行探测 + 缓存登记互斥，
     * 避免两个并发 start 各自探测到同一"空闲"端口造成双分配。
     *
     * @param port 起始端口号
     * @return 可用端口
     * @throws IOException 递增到 65535 仍无可用端口
     */
    public static synchronized int getUsablePort(int port) throws IOException {
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
