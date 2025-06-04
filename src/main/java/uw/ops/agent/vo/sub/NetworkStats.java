package uw.ops.agent.vo.sub;

import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * 网络相关信息。
 */
public class NetworkStats {

    /**
     * 索引位置。
     */
    private int index;

    /**
     * 网卡设备
     */
    private String name;

    /**
     * 设备mac地址。
     */
    private String mac;

    /**
     * 网速
     */
    private long speed;

    /**
     * ip地址。
     */
    private String ip;

    /**
     * 发送速率
     */
    private long txRate;

    /**
     * 接收速率
     */
    private long rxRate;

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("index",index)
                .append("name", name)
                .append("mac", mac)
                .append("speed", speed)
                .append("ip", ip)
                .append("txRate", txRate)
                .append("rxRate", rxRate)
                .toString();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public long getSpeed() {
        return speed;
    }

    public void setSpeed(long speed) {
        this.speed = speed;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public long getTxRate() {
        return txRate;
    }

    public void setTxRate(long txRate) {
        this.txRate = txRate;
    }

    public long getRxRate() {
        return rxRate;
    }

    public void setRxRate(long rxRate) {
        this.rxRate = rxRate;
    }

}
