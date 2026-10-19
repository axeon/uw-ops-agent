package uw.ops.agent.vo.sub;

/**
 * 主机内存统计（窗口结束时快照：物理内存/交换/虚拟内存的容量与用量）。
 */
public class MemStats {

    /**
     * 总内存数(字节)。
     */
    private long total;

    /**
     * 可用内存数(字节)。
     */
    private long usable;

    /**
     * swap 总大小(字节)。
     */
    private long swapTotal;

    /**
     * swap 已用(字节)。
     */
    private long swapUsed;

    /**
     * 虚拟内存大小(字节)。
     */
    private long virtualMax;

    /**
     * 虚拟内存已用(字节)。
     */
    private long virtualInUse;


    public MemStats(long total, long usable, long swapTotal, long swapUsed, long virtualMax, long virtualInUse) {
        this.total = total;
        this.usable = usable;
        this.swapTotal = swapTotal;
        this.swapUsed = swapUsed;
        this.virtualMax = virtualMax;
        this.virtualInUse = virtualInUse;
    }

    public MemStats() {
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getUsable() {
        return usable;
    }

    public void setUsable(long usable) {
        this.usable = usable;
    }

    public long getSwapTotal() {
        return swapTotal;
    }

    public void setSwapTotal(long swapTotal) {
        this.swapTotal = swapTotal;
    }

    public long getSwapUsed() {
        return swapUsed;
    }

    public void setSwapUsed(long swapUsed) {
        this.swapUsed = swapUsed;
    }

    public long getVirtualMax() {
        return virtualMax;
    }

    public void setVirtualMax(long virtualMax) {
        this.virtualMax = virtualMax;
    }

    public long getVirtualInUse() {
        return virtualInUse;
    }

    public void setVirtualInUse(long virtualInUse) {
        this.virtualInUse = virtualInUse;
    }
}
