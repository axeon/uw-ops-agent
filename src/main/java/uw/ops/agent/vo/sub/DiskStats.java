package uw.ops.agent.vo.sub;

/**
 * 磁盘窗口统计（每分钟 HostStats 上报，差分值 + 即时队列长度）。
 * 静态属性（model/serial/size）见 {@link DiskInfo}，不随本类传输。
 */
public class DiskStats {

    /**
     * 名称。
     */
    private String name;

    /**
     * 读取次数。
     */
    private long reads;

    /**
     * 读取字节数。
     */
    private long readBytes;

    /**
     * 写入次数。
     */
    private long writes;

    /**
     * 写入字节数。
     */
    private long writeBytes;

    /**
     * 传输时间
     */
    private long transferTime;

    /**
     * 队列长度
     */
    private long queueLength;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getReads() {
        return reads;
    }

    public void setReads(long reads) {
        this.reads = reads;
    }

    public long getReadBytes() {
        return readBytes;
    }

    public void setReadBytes(long readBytes) {
        this.readBytes = readBytes;
    }

    public long getWrites() {
        return writes;
    }

    public void setWrites(long writes) {
        this.writes = writes;
    }

    public long getWriteBytes() {
        return writeBytes;
    }

    public void setWriteBytes(long writeBytes) {
        this.writeBytes = writeBytes;
    }

    public long getTransferTime() {
        return transferTime;
    }

    public void setTransferTime(long transferTime) {
        this.transferTime = transferTime;
    }

    public long getQueueLength() {
        return queueLength;
    }

    public void setQueueLength(long queueLength) {
        this.queueLength = queueLength;
    }
}
