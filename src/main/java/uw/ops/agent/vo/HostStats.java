package uw.ops.agent.vo;

import uw.ops.agent.vo.sub.*;

import java.util.List;

/**
 * 设备运行统计信息。
 * 定时更新。
 */
public class HostStats {

    /**
     * 设备唯一标识。
     */
    private String hostHash;

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
     * 网络统计信息。
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
     * Docker进程列表。
     */
    private List<DockerPs> dockerPsList;

    /**
     * Docker进程列表。
     */
    private List<DockerStats> dockerStatsList;

    public String getHostHash() {
        return hostHash;
    }

    public void setHostHash(String hostHash) {
        this.hostHash = hostHash;
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

    public List<DockerPs> getDockerPsList() {
        return dockerPsList;
    }

    public void setDockerPsList(List<DockerPs> dockerPsList) {
        this.dockerPsList = dockerPsList;
    }

    public List<DockerStats> getDockerStatsList() {
        return dockerStatsList;
    }

    public void setDockerStatsList(List<DockerStats> dockerStatsList) {
        this.dockerStatsList = dockerStatsList;
    }
}
