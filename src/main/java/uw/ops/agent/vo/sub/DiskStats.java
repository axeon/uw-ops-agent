package uw.ops.agent.vo.sub;

import org.apache.commons.lang3.builder.ToStringBuilder;

import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;

/**
 * 磁盘信息
 */
public class DiskStats {

    /**
     * 名称。
     */
    private String name;

    /**
     * volume。
     */
    private String volume;

    /**
     * 文件系统。
     */
    private String fsType;

    /**
     * 挂载点。
     */
    private String mount;

    /**
     * 大小
     */
    private long total;

    /**
     * 可用大小。
     */
    private long usable;

    @Override
    public String toString() {
        return new ToStringBuilder(this, SHORT_PREFIX_STYLE)
                .append("name", name)
                .append("volume", volume)
                .append("fsType", fsType)
                .append("mount", mount)
                .append("total", total)
                .append("usable", usable)
                .toString();
    }

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
