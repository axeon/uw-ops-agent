package uw.ops.agent.helper;

import org.junit.jupiter.api.Test;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.sub.ContainerInfo;
import uw.ops.agent.helper.vo.ContainerCounters;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;
import java.util.List;
import uw.common.util.JsonUtils;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void missingBaselineAndInvalidWindowStayUnknown() throws Exception {
        ContainerCounters current = counters(120, 600);
        ContainerInfo.Stats first = diff(null, current);
        assertNull(first.getCpuPercent());
        assertNull(first.getDiskRead());
        assertNull(first.getCpuThrottle());
        assertEquals(1024L, first.getMemUsage());
        ContainerInfo.Stats gap = diff(counters(1, 100), current);
        assertNull(gap.getCpuPercent());
        assertNull(gap.getNetworkIn());
    }

    @Test
    void validZeroIsPreservedButAbsentSubsystemsStayUnknown() throws Exception {
        ContainerInfo.Stats sample = diff(counters(60, 100), counters(120, 100));
        assertEquals(0.0, sample.getCpuPercent());
        assertNull(sample.getCpuThrottle());
        assertNull(sample.getNetworkIn());
        assertNull(sample.getDiskRead());
    }

    @Test
    void counterRollbackDoesNotBecomeIdle() throws Exception {
        ContainerCounters before = counters(60, 200);
        ContainerCounters after = counters(120, 100);
        before.diskRead = 200L;
        after.diskRead = 100L;
        ContainerInfo.Stats sample = diff(before, after);
        assertNull(sample.getCpuPercent());
        assertNull(sample.getDiskRead());
    }

    @Test
    void missingRawFieldsRemainUnknownAfterNormalization() throws Exception {
        ContainerCounters before = rawCounters("{\"cpu_stats\":{},\"memory_stats\":{},\"pids_stats\":{},\"blkio_stats\":{}}");
        ContainerCounters after = rawCounters("{\"cpu_stats\":{},\"memory_stats\":{},\"pids_stats\":{},\"blkio_stats\":{}}");
        before.monoNanos = 60_000_000_000L;
        after.monoNanos = 120_000_000_000L;
        ContainerInfo.Stats sample = diff(before, after);
        assertNull(sample.getCpuPercent());
        assertNull(sample.getMemUsage());
        assertNull(sample.getMemPercent());
        assertNull(sample.getMemTotal());
        assertNull(sample.getPidCount());
        assertNull(sample.getPidsLimit());
        assertNull(sample.getDiskRead());
    }

    @Test
    void realZeroNetworkDiskAndThrottleRemainMeasuredZero() throws Exception {
        String json = "{\"cpu_stats\":{\"cpu_usage\":{\"total_usage\":0},\"throttling_data\":{\"periods\":%d,\"throttled_periods\":0,\"throttled_time\":0}},"
                + "\"networks\":{\"eth0\":{\"rx_bytes\":0,\"tx_bytes\":0,\"rx_dropped\":0,\"tx_dropped\":0,\"rx_errors\":0,\"tx_errors\":0}},"
                + "\"blkio_stats\":{\"io_service_bytes_recursive\":[]}}";
        ContainerCounters before = rawCounters(json.formatted(1));
        ContainerCounters after = rawCounters(json.formatted(2));
        before.monoNanos = 60_000_000_000L;
        after.monoNanos = 120_000_000_000L;
        ContainerInfo.Stats sample = diff(before, after);
        assertEquals(0.0, sample.getCpuPercent());
        assertEquals(0.0, sample.getCpuThrottle());
        assertEquals(0L, sample.getNetworkIn());
        assertEquals(0L, sample.getDiskRead());
        ContainerInfo.Stats decoded = JsonUtils.parse(JsonUtils.toString(sample), ContainerInfo.Stats.class);
        assertEquals(0.0, decoded.getCpuPercent());
        assertNull(decoded.getMemUsage());
    }

    @Test
    void missingAndResetSubsystemCountersRemainIndependent() throws Exception {
        String json = "{\"networks\":{\"eth0\":{\"rx_bytes\":%d,\"tx_bytes\":50},\"eth1\":{\"tx_bytes\":50}},"
                + "\"blkio_stats\":{\"io_service_bytes_recursive\":[{\"op\":\"Read\",\"value\":%d},{\"op\":\"Write\",\"value\":50}]}}";
        ContainerCounters before = rawCounters(json.formatted(100, 100));
        ContainerCounters after = rawCounters(json.formatted(200, 50));
        before.monoNanos = 60_000_000_000L;
        after.monoNanos = 120_000_000_000L;
        ContainerInfo.Stats sample = diff(before, after);
        assertNull(sample.getNetworkIn(), "missing second interface must not be counted as zero traffic");
        assertEquals(0L, sample.getNetworkOut());
        assertNull(sample.getDiskRead(), "reset disk counter is unknown");
        assertEquals(0L, sample.getDiskWrite());
    }

    @Test
    void unknownMetricsSurviveJsonRoundTrip() {
        ContainerInfo.Stats decoded = JsonUtils.parse(JsonUtils.toString(new ContainerInfo.Stats()), ContainerInfo.Stats.class);
        assertNull(decoded.getCpuPercent());
        assertNull(decoded.getCpuThrottle());
        assertNull(decoded.getCpuThrottleFreq());
        assertNull(decoded.getMemPercent());
        assertNull(decoded.getMemUsage());
        assertNull(decoded.getMemTotal());
        assertNull(decoded.getPidCount());
        assertNull(decoded.getPidsLimit());
        assertNull(decoded.getDiskRead());
        assertNull(decoded.getDiskWrite());
        assertNull(decoded.getNetworkIn());
        assertNull(decoded.getNetworkOut());
        assertNull(decoded.getRxDropped());
        assertNull(decoded.getTxDropped());
        assertNull(decoded.getRxErrors());
        assertNull(decoded.getTxErrors());
    }

    @Test
    void sensitiveContainerCommandCannotReturnThroughInventory() throws Exception {
        ContainerSummaryRaw sensitive = new ContainerSummaryRaw();
        sensitive.setId("sensitive-inventory");
        sensitive.setCommand("run --token=synthetic-secret");
        sensitive.setLabels(Map.of("uw.ops.sensitive", "true"));
        ContainerSummaryRaw legacyManaged = new ContainerSummaryRaw();
        legacyManaged.setId("legacy-managed-inventory");
        legacyManaged.setCommand("run --token=legacy-synthetic-secret");
        legacyManaged.setLabels(Map.of("uw.ops.container-id", "123"));
        ContainerSummaryRaw ordinary = new ContainerSummaryRaw();
        ordinary.setId("ordinary-inventory");
        ordinary.setCommand("run --mode=normal");
        Method method = HostStatsCollector.class.getDeclaredMethod("buildHostStats", String.class, List.class, Map.class);
        method.setAccessible(true);
        HostStats result = (HostStats) method.invoke(null, "test-host", List.of(sensitive, legacyManaged, ordinary), Map.of());
        ContainerInfo redacted = result.getContainerInfoList().stream()
                .filter(view -> "sensitive-inventory".equals(view.getId())).findFirst().orElseThrow();
        ContainerInfo retained = result.getContainerInfoList().stream()
                .filter(view -> "ordinary-inventory".equals(view.getId())).findFirst().orElseThrow();
        assertNull(redacted.getCommand());
        assertNull(result.getContainerInfoList().stream()
                .filter(view -> "legacy-managed-inventory".equals(view.getId())).findFirst().orElseThrow().getCommand());
        assertEquals("run --mode=normal", retained.getCommand());
        assertFalse(JsonUtils.toString(result).contains("synthetic-secret"));
    }

    private static ContainerCounters counters(long seconds, long cpu) {
        ContainerCounters counters = new ContainerCounters();
        counters.monoNanos = seconds * 1_000_000_000L;
        counters.cpuTotalUsage = cpu;
        counters.memUsage = 2048L;
        counters.memCache = 1024L;
        counters.memLimit = 4096L;
        return counters;
    }

    private static ContainerInfo.Stats diff(ContainerCounters before, ContainerCounters after) throws Exception {
        Method method = HostStatsCollector.class.getDeclaredMethod("diffContainer", ContainerCounters.class, ContainerCounters.class);
        method.setAccessible(true);
        return (ContainerInfo.Stats) method.invoke(null, before, after);
    }

    @SuppressWarnings("unchecked")
    private static ContainerCounters rawCounters(String json) throws Exception {
        Method method = HostStatsCollector.class.getDeclaredMethod("toContainerCounters", Map.class);
        method.setAccessible(true);
        return ((Map<String, ContainerCounters>) method.invoke(null,
                Map.of("container", JsonUtils.parse(json, ContainerStatsRaw.class)))).get("container");
    }
}
