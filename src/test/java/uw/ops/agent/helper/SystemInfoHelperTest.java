package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SystemInfoHelper 全流程测试（只走 public 入口，无注入缝）。
 */
class SystemInfoHelperTest {

    @Test
    void hostHashStableAcrossRebuilds() {
        //主机标识是进程级不变量：重采系统信息（每小时路径）不得改变身份。
        String first = SystemInfoHelper.getHostHash();
        assertFalse(first.isEmpty(), "hostHash must be computed");
        SystemInfoHelper.buildHostInfo();
        assertEquals(first, SystemInfoHelper.getHostHash());
    }
}
