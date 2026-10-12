package uw.ops.agent.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

/**
 * NetworkUtils 单测：bind 探测空闲原样返回、被占递增偏移、短时缓存不重复探测语义。
 */
class NetworkUtilsTest {

    /**
     * 空闲端口原样返回（bind 成功即自身，不偏移）。
     */
    @Test
    void freePortReturnedAsIs() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Assertions.assertEquals(port, NetworkUtils.getUsablePort(port));
    }

    /**
     * 被占端口递增至下一个空闲（真实占用模拟：受管容器之外的进程监听）。
     */
    @Test
    void occupiedPortShiftsUp() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int assigned = occupied.getLocalPort();
            int usable = NetworkUtils.getUsablePort(assigned);
            Assertions.assertTrue(usable > assigned, "occupied port must shift up: assigned=" + assigned + ", usable=" + usable);
        }
    }

    /**
     * 短时缓存语义：bind 成功的端口 TTL 内不再给出（同进程多端口名分配互斥），
     * 第二次请求必然递增到后续端口。
     */
    @Test
    void probedPortCachedWithinTtl() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        //第一次：空闲原样返回并登记缓存。
        Assertions.assertEquals(port, NetworkUtils.getUsablePort(port));
        //第二次：缓存命中视为已使用，给出的必然是后续端口。
        Assertions.assertNotEquals(port, NetworkUtils.getUsablePort(port));
    }
}
