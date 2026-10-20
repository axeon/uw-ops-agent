package uw.ops.agent.vo.sub;

/**
 * 文件系统用量统计（窗口结束时快照；已过滤容器运行时挂载）。
 */
public class FsStats {

    /**
     * 名称。
     */
    private String name;

    /**
     * 卷标识。
     */
    private String volume;

    /**
     * 文件系统类型(ext4/xfs...)。
     */
    private String fsType;

    /**
     * 挂载点。
     */
    private String mount;

    /**
     * 总容量(字节)。
     */
    private long total;

    /**
     * 可用容量(字节)。
     */
    private long usable;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public String getFsType() {
        return fsType;
    }

    public void setFsType(String fsType) {
        this.fsType = fsType;
    }

    public String getMount() {
        return mount;
    }

    public void setMount(String mount) {
        this.mount = mount;
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
}
