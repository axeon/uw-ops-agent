package uw.ops.agent.vo.sub;

/**
 * 磁盘信息
 */
public class DiskStats {

    /**
     * 名称。
     */
    private String name;

    /**
     * 型号。
     */
    private String model;

    /**
     * 序列号。
     */
    private String serial;

    /**
     * 大小。
     */
    private long size;

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
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
