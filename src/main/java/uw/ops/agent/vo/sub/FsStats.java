package uw.ops.agent.vo.sub;

import org.apache.commons.lang3.builder.ToStringBuilder;

import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;

/**
 * 文件系统信息
 */
public class FsStats {

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
