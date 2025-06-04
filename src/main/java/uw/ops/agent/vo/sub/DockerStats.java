package uw.ops.agent.vo.sub;

import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * docker容器统计
 */
public class DockerStats {

    /**
     * 容器id。
     */
    public String id;

    /**
     * cpu占用率。
     */
    public double cpuPercent;

    /**
     * 内存使用率。
     */
    public double memPercent;

    /**
     * 内存使用。
     */
    public long memUsage;

    /**
     * 内存限制。
     */
    public long memTotal;

    /**
     * 磁盘写。
     */
    public long diskWrite;

    /**
     * 磁盘读。
     */
    public long diskRead;

    /**
     * 网络上行速率。
     */
    public long networkIn;

    /**
     * 网络下行速率。
     */
    public long networkOut;

    /**
     * 进程/线程数。
     */
    private int pidCount;

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("id", id)
                .append("cpuPercent", cpuPercent)
                .append("memPercent", memPercent)
                .append("memUsage", memUsage)
                .append("memTotal", memTotal)
                .append("diskWrite", diskWrite)
                .append("diskRead", diskRead)
                .append("networkIn", networkIn)
                .append("networkOut", networkOut)
                .append("pidCount", pidCount)
                .toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public double getMemPercent() {
        return memPercent;
    }

    public void setMemPercent(double memPercent) {
        this.memPercent = memPercent;
    }

    public long getMemUsage() {
        return memUsage;
    }

    public void setMemUsage(long memUsage) {
        this.memUsage = memUsage;
    }

    public long getMemTotal() {
        return memTotal;
    }

    public void setMemTotal(long memTotal) {
        this.memTotal = memTotal;
    }

    public long getDiskWrite() {
        return diskWrite;
    }

    public void setDiskWrite(long diskWrite) {
        this.diskWrite = diskWrite;
    }

    public long getDiskRead() {
        return diskRead;
    }

    public void setDiskRead(long diskRead) {
        this.diskRead = diskRead;
    }

    public long getNetworkIn() {
        return networkIn;
    }

    public void setNetworkIn(long networkIn) {
        this.networkIn = networkIn;
    }

    public long getNetworkOut() {
        return networkOut;
    }

    public void setNetworkOut(long networkOut) {
        this.networkOut = networkOut;
    }

    public int getPidCount() {
        return pidCount;
    }

    public void setPidCount(int pidCount) {
        this.pidCount = pidCount;
    }
}
