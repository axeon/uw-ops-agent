package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;
import oshi.SystemInfo;
import uw.common.util.JsonUtils;
import uw.ops.agent.vo.HostInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SystemInfoHelper 全流程测试（只走 public 入口，无注入缝）。
 */
class SystemInfoHelperTest {

    @Test
    void hardwareCapacitySurvivesHostInfoWireFormat() {
        HostInfo host = SystemInfoHelper.buildHostInfo();
        var json = JsonUtils.parse(JsonUtils.toString(host), java.util.Map.class);
        assertEquals(new SystemInfo().getHardware().getProcessor().getLogicalProcessorCount(),
                json.get("cpuLogicalCount"));
    }

    @Test
    void hostHashStableAcrossRebuilds() {
        //主机标识是进程级不变量：重采系统信息（每小时路径）不得改变身份。
        String first = SystemInfoHelper.getHostHash();
        assertFalse(first.isEmpty(), "hostHash must be computed");
        SystemInfoHelper.buildHostInfo();
        assertEquals(first, SystemInfoHelper.getHostHash());
    }
}
