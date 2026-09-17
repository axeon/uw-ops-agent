package uw.ops.agent.helper.vo;

/**
 * 单容器计数快照（Engine API stats 归一化后的累计计数；全基本类型，缺失按 0）。
 * 差分输入快照（非上报 VO），由 HostStatsCollector 持有两份做两点差分。
 */
public class ContainerCounters {

    /**
     * 单调时钟纳秒（Agent 打点，不用 daemon 的 precpu_stats）。
     */
    public long monoNanos;

    /**
     * 容器生命周期累计 CPU 纳秒。
     */
    public long cpuTotalUsage;

    /**
     * 累计被限流纳秒。
     */
    public long throttledNanos;

    /**
     * 累计被限流周期数。
     */
    public long throttledPeriods;

    /**
     * 累计 CFS 周期数。
     */
    public long periods;

    /**
     * 内存使用（含 cache）。
     */
    public long memUsage;

    /**
     * 页缓存：cgroup v1 cache / v2 inactive_file（toContainerCounters 归一，均缺失=0）。
     */
    public long memCache;

    /**
     * 内存配额。
     */
    public long memLimit;

    /**
     * 当前进程/线程数。
     */
    public long pidCount;

    /**
     * pids 限额（0=未设）。
     */
    public long pidsLimit;

    /**
     * networks 块是否存在（host 模式整个块缺失）。
     */
    public boolean networksPresent;

    /**
     * networks.*.rx_bytes 求和。
     */
    public long netRx;

    /**
     * networks.*.tx_bytes 求和。
     */
    public long netTx;

    /**
     * rx_dropped 求和。
     */
    public long rxDropped;

    /**
     * tx_dropped 求和。
     */
    public long txDropped;

    /**
     * rx_errors 求和。
     */
    public long rxErrors;

    /**
     * tx_errors 求和。
     */
    public long txErrors;

    /**
     * blkio Read 求和（累计字节）。
     */
    public long diskRead;

    /**
     * blkio Write 求和（累计字节）。
     */
    public long diskWrite;
}
