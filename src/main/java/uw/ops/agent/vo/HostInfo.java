package uw.ops.agent.vo;

import uw.ops.agent.AgentInfo;

import java.util.Date;

/**
 * 设备信息。
 * 启动时候更新一次，
 */
public class HostInfo {

    /**
     * 唯一id。通过对机器序列号和mac地址做sha1获取。
     */
    private String hostHash;

    /**
     * 主机名
     */
    private String hostName;

    /**
     * 设备厂商。
     */
    private String manufacturer;

    /**
     * 设备型号。
     */
    private String machineModel;

    /**
     * 设备序号。
     */
    private String serialNumber;

    /**
     * agentInfo。
     */
    private String agentInfo = AgentInfo.AGENT_INFO;

    /**
     * osInfo。
     */
    private String osInfo;

    /**
     * cpu相关信息。
     */
    private String cpuInfo;

    /**
     * 内存信息。
     */
    private String memInfo;

    /**
     * 磁盘信息。
     */
    private String diskInfo;

    /**
     * 文件系统信息。
     */
    private String fsInfo;

    /**
     * ip信息。
     */
    private String networkInfo;

    /**
     * 启动时间。
     */
    private Date bootDate;

    public String getHostHash() {
        return hostHash;
    }

    public void setHostHash(String hostHash) {
        this.hostHash = hostHash;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getMachineModel() {
        return machineModel;
    }

    public void setMachineModel(String machineModel) {
        this.machineModel = machineModel;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getAgentInfo() {
        return agentInfo;
    }

    public void setAgentInfo(String agentInfo) {
        this.agentInfo = agentInfo;
    }

    public String getOsInfo() {
        return osInfo;
    }

    public void setOsInfo(String osInfo) {
        this.osInfo = osInfo;
    }

    public String getCpuInfo() {
        return cpuInfo;
    }

    public void setCpuInfo(String cpuInfo) {
        this.cpuInfo = cpuInfo;
    }

    public String getMemInfo() {
        return memInfo;
    }

    public void setMemInfo(String memInfo) {
        this.memInfo = memInfo;
    }

    public String getDiskInfo() {
        return diskInfo;
    }

    public void setDiskInfo(String diskInfo) {
        this.diskInfo = diskInfo;
    }

    public String getFsInfo() {
        return fsInfo;
    }

    public void setFsInfo(String fsInfo) {
        this.fsInfo = fsInfo;
    }

    public String getNetworkInfo() {
        return networkInfo;
    }

    public void setNetworkInfo(String networkInfo) {
        this.networkInfo = networkInfo;
    }

    public Date getBootDate() {
        return bootDate;
    }

    public void setBootDate(Date bootDate) {
        this.bootDate = bootDate;
    }
}
