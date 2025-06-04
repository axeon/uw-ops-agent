package uw.ops.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.util.SystemClock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

public class NetworkUtils {

    private static final Logger log = LoggerFactory.getLogger(NetworkUtils.class);

    /**
     * 端口检测的ttl。
     */
    public static final long CHECK_TTL = 300_000L;

    /**
     * 已被使用的端口map表。
     * key:port value:检测时间戳
     */
    private static final Map<Integer, Long> usedPortMap = new HashMap<>(100);


    /**
     * 检查端口缓存。
     *
     * @param port
     * @return
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
     * 根据输入端口号，递增递归查询可使用端口
     *
     * @param port 端口号
     * @return 如果被占用，递归；否则返回可使用port
     */
    public static int getUsablePort(int port) throws IOException {
        //先检测端口缓存。
        boolean flag = checkPortCache(port);
        if (flag) {
            port = port + 1;
            return getUsablePort(port);
        }
        //实际端口检测。
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            flag = true;
            //如果测试端口号没有被占用，那么会抛出异常，通过下文flag来返回可用端口
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        if (flag) {
            //端口被占用，port + 1递归
            port = port + 1;
            return getUsablePort(port);
        } else {
            //可用端口
            return port;
        }
    }

}
