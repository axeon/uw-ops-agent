package uw.ops.agent.vo;

import uw.ops.agent.vo.sub.*;

import java.util.Date;
import java.util.List;

/**
 * 主机运行统计（60 秒窗口两点差分结果 + 窗口末值快照）。
 * 比率类两位小数；NULL=失败/不完整/不适用，0=真实低值。
 */
public class HostStats {

    /**
     * 设备唯一标识。
     */
    private String hostHash;

    /**
     * 统计时间（窗口结束时间，协议字段）：Center 落为 create_date，参与 (host_id, create_date) 幂等唯一键；
     * 重试复用原 payload 保证不重复落库。本轮 inventory 失败时列表仅含事件兜底容器
     * （Center 据成员构成推断快照成败：存在非 destroyed 成员即成功，失败时不动台账仅处理事件）。
     */
    private Date statsDate;

    /**
     * CPU统计信息。
     */
    private CpuStats cpuStats;

    /**
     * 内存统计信息。
     */
    private MemStats memStats;

    /**
     * 负载统计信息。
     */
    private LoadStats loadStats;

    /**
     * TCP 连接状态统计信息。
     */
    private InternetStats internetStats;

    /**
     * 磁盘统计信息。
     */
    private List<DiskStats> diskStatsList;

    /**
     * 文件系统统计信息。
     */
    private List<FsStats> fsStatsList;

    /**
     * 网络统计信息。
     */
    private List<NetworkStats> networkStatsList;

    /**
     * 容器视图列表（inventory 全量快照 + 窗口统计 + 事件三合一；inventory 失败时仅含事件兜底容器）。
     */
    private List<ContainerInfo> containerInfoList;

    public String getHostHash() {
        return hostHash;
    }

    public void setHostHash(String hostHash) {
        this.hostHash = hostHash;
    }

    public Date getStatsDate() {
        return statsDate;
    }

    public void setStatsDate(Date statsDate) {
        this.statsDate = statsDate;
    }

    public CpuStats getCpuStats() {
        return cpuStats;
    }

    public void setCpuStats(CpuStats cpuStats) {
        this.cpuStats = cpuStats;
    }

    public MemStats getMemStats() {
        return memStats;
    }

    public void setMemStats(MemStats memStats) {
        this.memStats = memStats;
    }

    public LoadStats getLoadStats() {
        return loadStats;
    }

    public void setLoadStats(LoadStats loadStats) {
        this.loadStats = loadStats;
    }

    public List<DiskStats> getDiskStatsList() {
        return diskStatsList;
    }

    public void setDiskStatsList(List<DiskStats> diskStatsList) {
        this.diskStatsList = diskStatsList;
    }

    public List<FsStats> getFsStatsList() {
        return fsStatsList;
    }

    public void setFsStatsList(List<FsStats> fsStatsList) {
        this.fsStatsList = fsStatsList;
    }

    public List<NetworkStats> getNetworkStatsList() {
        return networkStatsList;
    }

    public void setNetworkStatsList(List<NetworkStats> networkStatsList) {
        this.networkStatsList = networkStatsList;
    }

    public InternetStats getInternetStats() {
        return internetStats;
    }

    public void setInternetStats(InternetStats internetStats) {
        this.internetStats = internetStats;
    }

    public List<ContainerInfo> getContainerInfoList() {
        return containerInfoList;
    }

    public void setContainerInfoList(List<ContainerInfo> containerInfoList) {
        this.containerInfoList = containerInfoList;
    }


}
