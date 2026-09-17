package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;
import uw.ops.agent.vo.HostStats;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HostStatsCollector 全流程测试（只走 public 入口，无注入缝）：
 * 无 docker daemon 环境下的降级链路与窗口独立性。
 */
class HostStatsCollectorTest {

    @Test
    void buildHostStatsDegradesWithoutDockerDaemon() {
        //主入口在无 daemon 环境下完整降级：inventory/stats 失败仅清空容器侧，
        //Host 侧照常产出（真实 OSHI），返回可上报的全新窗口对象。
        HostStats payload = HostStatsCollector.buildHostStats();
        assertNotNull(payload);
        assertNotNull(payload.getStatsDate());
        assertNotNull(payload.getMemStats());
    }

    @Test
    void consecutiveWindowsProduceFreshPayload() {
        //每轮调用必产出全新窗口对象（发送失败重试归 MainService 发送层，采集不感知）。
        HostStats first = HostStatsCollector.buildHostStats();
        HostStats second = HostStatsCollector.buildHostStats();
        assertFalse(first == second);
    }

    @Test
    void pollContainerEventsSwallowsDaemonFailure() {
        //无 daemon 环境下事件轮询入口内部消化异常（跳过本轮），不向调度线程抛出。
        assertDoesNotThrow(HostStatsCollector::pollContainerEvents);
    }
}
