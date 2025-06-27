package uw.ops.agent.vo.sub;

/**
 * 内存信息。
 */
public class MemStats {

    /**
     * 总内存数
     */
    long total;

    /**
     * 可用内存数。
     */
    long usable;

    /**
     * 页大小。
     */
    long pageSize;

    /**
     * swap大小
     */
    long swapTotal;

    /**
     * 已用的swap
     */
    long swapUsed;

    /**
     * 虚拟内存大小
     */
    long virtualMax;

    /**
     * 虚拟内存已用。
     */
    long virtualInUse;

    /**
     * swap page in
     */
    long swapPagesIn;

    /**
     * swap page out
     */
    long swapPagesOut;


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
