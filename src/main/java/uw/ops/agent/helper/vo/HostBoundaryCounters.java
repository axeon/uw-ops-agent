package uw.ops.agent.helper.vo;

import java.util.Map;

/**
 * 窗口边界计数快照（主轮每次激发采一次）：除 queueLength 为即时值外均为累计计数，
 * 与上一次激发的快照做两点差分即为窗口占比/速率/差值。
 * 差分输入快照（非上报 VO），与 {@link CpuCounters}/{@link NetIfCounters}/{@link DiskCounters}
 * 同居 helper 包，由 HostStatsCollector 持有两份做两点差分。
 */
public class HostBoundaryCounters {

    /**
     * 单调时钟纳秒。
     */
    public final long monoNanos;

    /**
     * CPU 边界计数（8 状态 ticks + cs/int 累计）。
     */
    public final CpuCounters cpu;

    /**
     * 网卡计数：name -&gt; 累计收发字节（差分）。
     */
    public final Map<String, NetIfCounters> netCounters;

    /**
     * 磁盘计数：name -&gt; 计数（5 项累计差分 + queueLength 即时值供上报骨架）。
     */
    public final Map<String, DiskCounters> diskCounters;

    /**
     * @param monoNanos   单调时钟纳秒
     * @param cpu         CPU 边界计数
     * @param netCounters 网卡计数
     * @param diskCounters 磁盘计数
     */
    public HostBoundaryCounters(long monoNanos, CpuCounters cpu,
                                Map<String, NetIfCounters> netCounters, Map<String, DiskCounters> diskCounters) {
        this.monoNanos = monoNanos;
        this.cpu = cpu;
        this.netCounters = netCounters;
        this.diskCounters = diskCounters;
    }
}
