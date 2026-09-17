package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Docker Engine API GET /containers/{id}/stats?stream=false&amp;one-shot=1 响应映射。
 * <p>
 * 仅声明采集所需字段；字段缺失（cgroup 未挂载/驱动不支持/host 网络模式无 networks 块）
 * 绑定后保持 null，是 NULL=失败/不完整/不适用语义的源头。嵌套 section 均为纯数据载体。
 */
public class ContainerStatsRaw {

    /**
     * CPU 计数与限流统计。
     */
    @JsonProperty("cpu_stats")
    private CpuSection cpuStats;

    /**
     * 内存计数：usage/limit 与 cgroup v1 cache / v2 inactive_file（stats map 按 key 取）。
     */
    @JsonProperty("memory_stats")
    private MemorySection memoryStats;

    /**
     * pids 计数与限额。
     */
    @JsonProperty("pids_stats")
    private PidsSection pidsStats;

    /**
     * 网络分接口计数；host 网络模式整个块缺失，绑定后为 null。
     */
    @JsonProperty("networks")
    private Map<String, NetworkIfStats> networks;

    /**
     * 块 IO 计数（cgroup v2 仅 io_service_bytes_recursive 可用）。
     */
    @JsonProperty("blkio_stats")
    private BlkioSection blkioStats;

    public CpuSection getCpuStats() {
        return cpuStats;
    }

    public void setCpuStats(CpuSection cpuStats) {
        this.cpuStats = cpuStats;
    }

    public MemorySection getMemoryStats() {
        return memoryStats;
    }

    public void setMemoryStats(MemorySection memoryStats) {
        this.memoryStats = memoryStats;
    }

    public PidsSection getPidsStats() {
        return pidsStats;
    }

    public void setPidsStats(PidsSection pidsStats) {
        this.pidsStats = pidsStats;
    }

    public Map<String, NetworkIfStats> getNetworks() {
        return networks;
    }

    public void setNetworks(Map<String, NetworkIfStats> networks) {
        this.networks = networks;
    }

    public BlkioSection getBlkioStats() {
        return blkioStats;
    }

    public void setBlkioStats(BlkioSection blkioStats) {
        this.blkioStats = blkioStats;
    }

    /**
     * CPU 段。
     */
    public static class CpuSection {

        /**
         * CPU 用量累计。
         */
        @JsonProperty("cpu_usage")
        private CpuUsageSection cpuUsage;

        /**
         * CFS 限流统计。
         */
        @JsonProperty("throttling_data")
        private ThrottlingSection throttlingData;

        public CpuUsageSection getCpuUsage() {
            return cpuUsage;
        }

        public void setCpuUsage(CpuUsageSection cpuUsage) {
            this.cpuUsage = cpuUsage;
        }

        public ThrottlingSection getThrottlingData() {
            return throttlingData;
        }

        public void setThrottlingData(ThrottlingSection throttlingData) {
            this.throttlingData = throttlingData;
        }
    }

    /**
     * CPU 用量段。
     */
    public static class CpuUsageSection {

        /**
         * 容器生命周期累计 CPU 纳秒。
         */
        @JsonProperty("total_usage")
        private Long totalUsage;

        public Long getTotalUsage() {
            return totalUsage;
        }

        public void setTotalUsage(Long totalUsage) {
            this.totalUsage = totalUsage;
        }
    }

    /**
     * CFS 限流段。
     */
    public static class ThrottlingSection {

        /**
         * 累计被限流纳秒。
         */
        @JsonProperty("throttled_time")
        private Long throttledTime;

        /**
         * 累计 CFS 周期数。
         */
        @JsonProperty("periods")
        private Long periods;

        /**
         * 累计被限流周期数。
         */
        @JsonProperty("throttled_periods")
        private Long throttledPeriods;

        public Long getThrottledTime() {
            return throttledTime;
        }

        public void setThrottledTime(Long throttledTime) {
            this.throttledTime = throttledTime;
        }

        public Long getPeriods() {
            return periods;
        }

        public void setPeriods(Long periods) {
            this.periods = periods;
        }

        public Long getThrottledPeriods() {
            return throttledPeriods;
        }

        public void setThrottledPeriods(Long throttledPeriods) {
            this.throttledPeriods = throttledPeriods;
        }
    }

    /**
     * 内存段。
     */
    public static class MemorySection {

        /**
         * 内存使用（含 cache）。
         */
        @JsonProperty("usage")
        private Long usage;

        /**
         * 内存配额。
         */
        @JsonProperty("limit")
        private Long limit;

        /**
         * 明细计数：v1 的 cache / v2 的 inactive_file 均在此 map 中按 key 取。
         */
        @JsonProperty("stats")
        private Map<String, Long> stats;

        public Long getUsage() {
            return usage;
        }

        public void setUsage(Long usage) {
            this.usage = usage;
        }

        public Long getLimit() {
            return limit;
        }

        public void setLimit(Long limit) {
            this.limit = limit;
        }

        public Map<String, Long> getStats() {
            return stats;
        }

        public void setStats(Map<String, Long> stats) {
            this.stats = stats;
        }
    }

    /**
     * pids 段。
     */
    public static class PidsSection {

        /**
         * 当前进程/线程数。
         */
        @JsonProperty("current")
        private Long current;

        /**
         * pids 限额（0=未设）。
         */
        @JsonProperty("limit")
        private Long limit;

        public Long getCurrent() {
            return current;
        }

        public void setCurrent(Long current) {
            this.current = current;
        }

        public Long getLimit() {
            return limit;
        }

        public void setLimit(Long limit) {
            this.limit = limit;
        }
    }

    /**
     * 单网卡计数段。
     */
    public static class NetworkIfStats {

        /**
         * 接收字节累计。
         */
        @JsonProperty("rx_bytes")
        private Long rxBytes;

        /**
         * 发送字节累计。
         */
        @JsonProperty("tx_bytes")
        private Long txBytes;

        /**
         * 接收丢包累计。
         */
        @JsonProperty("rx_dropped")
        private Long rxDropped;

        /**
         * 发送丢包累计。
         */
        @JsonProperty("tx_dropped")
        private Long txDropped;

        /**
         * 接收错包累计。
         */
        @JsonProperty("rx_errors")
        private Long rxErrors;

        /**
         * 发送错包累计。
         */
        @JsonProperty("tx_errors")
        private Long txErrors;

        public Long getRxBytes() {
            return rxBytes;
        }

        public void setRxBytes(Long rxBytes) {
            this.rxBytes = rxBytes;
        }

        public Long getTxBytes() {
            return txBytes;
        }

        public void setTxBytes(Long txBytes) {
            this.txBytes = txBytes;
        }

        public Long getRxDropped() {
            return rxDropped;
        }

        public void setRxDropped(Long rxDropped) {
            this.rxDropped = rxDropped;
        }

        public Long getTxDropped() {
            return txDropped;
        }

        public void setTxDropped(Long txDropped) {
            this.txDropped = txDropped;
        }

        public Long getRxErrors() {
            return rxErrors;
        }

        public void setRxErrors(Long rxErrors) {
            this.rxErrors = rxErrors;
        }

        public Long getTxErrors() {
            return txErrors;
        }

        public void setTxErrors(Long txErrors) {
            this.txErrors = txErrors;
        }
    }

    /**
     * 块 IO 段。
     */
    public static class BlkioSection {

        /**
         * 按设备/操作的字节计数。
         */
        @JsonProperty("io_service_bytes_recursive")
        private List<BlkioEntry> ioServiceBytes;

        public List<BlkioEntry> getIoServiceBytes() {
            return ioServiceBytes;
        }

        public void setIoServiceBytes(List<BlkioEntry> ioServiceBytes) {
            this.ioServiceBytes = ioServiceBytes;
        }
    }

    /**
     * 单条块 IO 计数。
     */
    public static class BlkioEntry {

        /**
         * 操作类型：Read/Write。
         */
        @JsonProperty("op")
        private String op;

        /**
         * 累计字节。
         */
        @JsonProperty("value")
        private Long value;

        public String getOp() {
            return op;
        }

        public void setOp(String op) {
            this.op = op;
        }

        public Long getValue() {
            return value;
        }

        public void setValue(Long value) {
            this.value = value;
        }
    }
}
