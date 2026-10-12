package uw.ops.agent.vo.sub;

/**
 * 网卡静态信息（每小时 HostInfo 上报，见 HostInfo.networkInfo；按 index 升序）。
 * 与 {@link NetworkStats} 分离：静态属性不随每分钟 stats 重复传输。
 */
public class NetworkInfo {

    /**
     * 索引位置。
     */
    private int index;

    /**
     * 网卡设备名。
     */
    private String name;

    /**
     * 设备mac地址。
     */
    private String mac;

    /**
     * 协商速率。
     */
    private long speed;

    /**
     * IPv4 地址列表的字符串形式（如 "[10.0.0.2, 10.0.0.3]"）。
     */
    private String ip;

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

}
