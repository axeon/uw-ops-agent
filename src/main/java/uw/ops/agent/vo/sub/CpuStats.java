package uw.ops.agent.vo.sub;

/**
 * 主机 CPU 窗口统计（60 秒两点差分：各态占比为窗口均值，cs/int 为窗口速率）。
 * 窗口无效或 tick 回退时整组 NULL（见 HostStatsCollector.diffHostCpu）。
 */
public class CpuStats {

    /**
     * 窗口内上下文切换次数（差分 ÷ 窗口秒数）。
     */
    private long contextSwitches;

    /**
     * 窗口内中断次数（差分 ÷ 窗口秒数）。
     */
    private long interrupts;

    /**
     * 用户态使用率(%)。
     */
    private double user;

    /**
     * 低优先级进程(nice)使用率(%)。
     */
    private double nice;

    /**
     * 内核态(sys)使用率(%)。
     */
    private double sys;

    /**
     * 空闲(idle)使用率(%)。
     */
    private double idle;

    /**
     * IO 等待(iowait)使用率(%)。
     */
    private double ioWait;

    /**
     * 硬中断占用率(%)。
     */
    private double hardIrq;

    /**
     * 软中断占用率(%)。
     */
    private double softIrq;

    /**
     * 虚拟化被宿主偷占(steal)使用率(%)。
     */
    private double steal;

    public CpuStats(long contextSwitches, long interrupts, double user, double nice, double sys, double idle, double ioWait, double hardIrq, double softIrq, double steal) {
        this.contextSwitches = contextSwitches;
        this.interrupts = interrupts;
        this.user = user;
        this.nice = nice;
        this.sys = sys;
        this.idle = idle;
        this.ioWait = ioWait;
        this.hardIrq = hardIrq;
        this.softIrq = softIrq;
        this.steal = steal;
    }

    public CpuStats() {
    }

    public long getContextSwitches() {
        return contextSwitches;
    }

    public void setContextSwitches(long contextSwitches) {
        this.contextSwitches = contextSwitches;
    }

    public long getInterrupts() {
        return interrupts;
    }

    public void setInterrupts(long interrupts) {
        this.interrupts = interrupts;
    }

    public double getUser() {
        return user;
    }

    public void setUser(double user) {
        this.user = user;
    }

    public double getNice() {
        return nice;
    }

    public void setNice(double nice) {
        this.nice = nice;
    }

    public double getSys() {
        return sys;
    }

    public void setSys(double sys) {
        this.sys = sys;
    }

    public double getIdle() {
        return idle;
    }

    public void setIdle(double idle) {
        this.idle = idle;
    }

    public double getIoWait() {
        return ioWait;
    }

    public void setIoWait(double ioWait) {
        this.ioWait = ioWait;
    }

    public double getHardIrq() {
        return hardIrq;
    }

    public void setHardIrq(double hardIrq) {
        this.hardIrq = hardIrq;
    }

    public double getSoftIrq() {
        return softIrq;
    }

    public void setSoftIrq(double softIrq) {
        this.softIrq = softIrq;
    }

    public double getSteal() {
        return steal;
    }

    public void setSteal(double steal) {
        this.steal = steal;
    }
}
