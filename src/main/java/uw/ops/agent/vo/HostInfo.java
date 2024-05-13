package uw.ops.agent.vo;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import uw.ops.agent.AgentInfo;

import java.util.Date;

/**
 * 设备信息。
 * 启动时候更新一次，
 */
public class HostInfo {

    /**
     * 唯一id。通过对机器序列号和mac地址做sha1获得。
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
     * ip信息。
     */
    private String networkInfo;

    /**
     * 启动时间。
     */
    private Date bootDate;

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("hostHash", hostHash)
                .append("hostName", hostName)
                .append("manufacturer", manufacturer)
                .append("machineModel", machineModel)
                .append("serialNumber", serialNumber)
                .append("agentInfo", agentInfo)
                .append("osInfo", osInfo)
                .append("cpuInfo", cpuInfo)
                .append("memInfo", memInfo)
                .append("diskInfo", diskInfo)
                .append("networkInfo", networkInfo)
                .append("bootDate", bootDate)
                .toString();
    }

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
