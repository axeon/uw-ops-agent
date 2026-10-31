package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 八进制权限位 → POSIX 权限集转换锚定（跨进程序列化后 0600=384 十进制到达，按位拆解防
 * 十进制直传 chmod 的同类错位复发）。
 */
class PosixPermissionsTest {

    @Test
    void octalModesMapExactly() {
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                StructuredTaskExecutor.posixPermissions(0600));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
                StructuredTaskExecutor.posixPermissions(0644));
        assertEquals(Set.of(PosixFilePermission.values()),
                StructuredTaskExecutor.posixPermissions(0777));
        assertTrue(StructuredTaskExecutor.posixPermissions(0000).isEmpty());
    }

    @Test
    void serializedDecimalArrivesAsSameBits() {
        //JSON 序列化：0600 字面量→384 到达——位判定不受十进制形态影响
        assertEquals(StructuredTaskExecutor.posixPermissions(0600),
                StructuredTaskExecutor.posixPermissions(384));
    }
}
