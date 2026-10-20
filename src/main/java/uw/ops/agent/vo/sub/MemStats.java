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
     * 内存页大小(字节)。
     */
    private long pageSize;

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

    /**
     * swap 换入页数。
     */
    private long swapPagesIn;

    /**
     * swap 换出页数。
     */
    private long swapPagesOut;


    public MemStats(long total, long usable, long pageSize, long swapTotal, long swapUsed, long virtualMax, long virtualInUse, long swapPagesIn, long swapPagesOut) {
        this.total = total;
        this.usable = usable;
        this.pageSize = pageSize;
        this.swapTotal = swapTotal;
        this.swapUsed = swapUsed;
        this.virtualMax = virtualMax;
        this.virtualInUse = virtualInUse;
        this.swapPagesIn = swapPagesIn;
        this.swapPagesOut = swapPagesOut;
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

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
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

    public long getSwapPagesIn() {
        return swapPagesIn;
    }

    public void setSwapPagesIn(long swapPagesIn) {
        this.swapPagesIn = swapPagesIn;
    }

    public long getSwapPagesOut() {
        return swapPagesOut;
    }

    public void setSwapPagesOut(long swapPagesOut) {
        this.swapPagesOut = swapPagesOut;
    }
}
