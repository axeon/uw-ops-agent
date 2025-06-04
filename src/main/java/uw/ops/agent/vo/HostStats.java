package uw.ops.agent.vo;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import uw.ops.agent.vo.sub.*;

import java.util.List;

/**
 * 设备运行统计信息。
 * 定时更新。
 */
public class HostStats {

    private String hostHash;

    private CpuStats cpuStats;

    private MemStats memStats;

    private LoadStats loadStats;

    private InternetStats internetStats;

    private List<DiskStats> diskStatsList;

    private List<NetworkStats> networkStatsList;

    private List<DockerPs> dockerPsList;

    private List<DockerStats> dockerStatsList;


    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("hostHash", hostHash)
                .append("cpuStats", cpuStats)
                .append("memStats", memStats)
                .append("loadStats", loadStats)
                .append("diskStatsList", diskStatsList)
                .append("networkStatsList", networkStatsList)
                .append("internetStats", internetStats)
                .append("dockerPsList", dockerPsList)
                .append("dockerStatsList", dockerStatsList)
                .toString();
    }

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
