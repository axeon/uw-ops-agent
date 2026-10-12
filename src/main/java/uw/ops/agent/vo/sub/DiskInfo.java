package uw.ops.agent.vo.sub;

/**
 * 磁盘静态信息（每小时 HostInfo 上报，见 HostInfo.diskInfo）。
 * 与 {@link DiskStats} 分离：静态属性不随每分钟 stats 重复传输。
 */
public class DiskInfo {

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
     * 容量(字节)。
     */
    private long size;

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
}
