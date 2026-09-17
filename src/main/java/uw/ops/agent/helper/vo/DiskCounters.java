package uw.ops.agent.helper.vo;

/**
 * 磁盘边界计数快照（差分输入，非上报 VO）：前 5 项为累计计数（两点差 = 窗口增量），
 * queueLength 为即时值（上报骨架直传，不参与差分），
 * 由 SystemInfoHelper.buildDiskCounters() 一次 updateAttributes 同刻采集。
 */
public class DiskCounters {

    /**
     * 累计读取次数。
     */
    public final long reads;

    /**
     * 累计读取字节数。
     */
    public final long readBytes;

    /**
     * 累计写入次数。
     */
    public final long writes;

    /**
     * 累计写入字节数。
     */
    public final long writeBytes;

    /**
     * 累计传输时间(ms)。
     */
    public final long transferTime;

    /**
     * 即时队列长度（非累计，不参与差分）。
     */
    public final long queueLength;

    /**
     * @param reads        累计读取次数
     * @param readBytes    累计读取字节数
     * @param writes       累计写入次数
     * @param writeBytes   累计写入字节数
     * @param transferTime 累计传输时间(ms)
     * @param queueLength  即时队列长度
     */
    public DiskCounters(long reads, long readBytes, long writes, long writeBytes, long transferTime, long queueLength) {
        this.reads = reads;
        this.readBytes = readBytes;
        this.writes = writes;
        this.writeBytes = writeBytes;
        this.transferTime = transferTime;
        this.queueLength = queueLength;
    }
}
