package uw.ops.agent.helper.vo;

/**
 * CPU 边界计数快照（差分输入，非上报 VO）：8 状态累计 ticks + 上下文切换/中断累计，
 * 由 SystemInfoHelper.buildCpuCounters() 同刻一次采集，HostBoundaryCounters 持有两份做两点差分。
 */
public class CpuCounters {

    /**
     * CPU ticks（OSHI getSystemCpuLoadTicks 原样，8 个状态累计）。
     */
    public final long[] cpuTicks;

    /**
     * 上下文切换累计。
     */
    public final long contextSwitches;

    /**
     * 中断累计。
     */
    public final long interrupts;

    /**
     * @param cpuTicks        CPU ticks（8 状态累计）
     * @param contextSwitches 上下文切换累计
     * @param interrupts      中断累计
     */
    public CpuCounters(long[] cpuTicks, long contextSwitches, long interrupts) {
        this.cpuTicks = cpuTicks;
        this.contextSwitches = contextSwitches;
        this.interrupts = interrupts;
    }
}
