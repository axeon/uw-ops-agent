package uw.ops.agent.helper;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.*;
import oshi.hardware.CentralProcessor.TickType;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.util.Util;
import uw.common.util.DigestUtils;
import uw.common.util.JsonUtils;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.cmd.DockerPsCmd;
import uw.ops.agent.vo.cmd.DockerStatsCmd;
import uw.ops.agent.vo.sub.*;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;

/**
 * A demonstration of access to many of OSHI's capabilities
 */
public class SystemInfoHelper {

    private static final Logger logger = LoggerFactory.getLogger(SystemInfoHelper.class);

    private static final FastDateFormat dateFormat = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss");

    private static final SystemInfo systemInfo = new SystemInfo();

    private static final HardwareAbstractionLayer hal = systemInfo.getHardware();

    private static final OperatingSystem os = systemInfo.getOperatingSystem();

    private static String HOST_HASH = "";

    public static void main(String[] args) {
        System.out.println(JsonUtils.toString(getDiskStats()));
    }

    /**
     * 构建系统信息。
     */
    public static HostInfo buildHostInfo() throws Exception {
        HostInfo hostInfo = new HostInfo();
        ComputerSystem computerSystem = hal.getComputerSystem();
        hostInfo.setHostName(os.getNetworkParams().getHostName());
        hostInfo.setManufacturer(computerSystem.getManufacturer());
        hostInfo.setMachineModel(computerSystem.getModel());
        hostInfo.setSerialNumber(computerSystem.getSerialNumber());
        hostInfo.setOsInfo(os.toString());
        hostInfo.setCpuInfo(getProcessorInfo(hal.getProcessor()));
        hostInfo.setMemInfo(getMemoryInfo(hal.getMemory()));
        List<DiskStats> diskStatsList = getDiskStats();
        hostInfo.setDiskInfo(JsonUtils.toString(diskStatsList));
        List<FsStats> fsStatsList = getFsStats(os.getFileSystem());
        hostInfo.setFsInfo(JsonUtils.toString(fsStatsList));
        List<NetworkStats> networkStatsList = getNetworkInterfaceStats(1000L);
        hostInfo.setNetworkInfo(JsonUtils.toString(networkStatsList));
        hostInfo.setBootDate(new Date(os.getSystemBootTime() * 1000L));
        //hash出hostHash
        String mac0 = "";
        if (!networkStatsList.isEmpty()) {
            mac0 = networkStatsList.getFirst().getMac();
        }
        HOST_HASH = DigestUtils.signHex(hostInfo.getMachineModel() + "$" + hostInfo.getSerialNumber() + "$" + mac0, DigestUtils.Algorithm.SHA_256);
        hostInfo.setHostHash(HOST_HASH);
        return hostInfo;
    }

    /**
     * 构建系统统计信息。
     */
    public static HostStats buildHostStats() throws SocketException {
        HostStats hostStats = new HostStats();
        hostStats.setHostHash(HOST_HASH);
        hostStats.setCpuStats(getCpuStats(hal.getProcessor(), 3000L));
        hostStats.setMemStats(getMemoryStats(hal.getMemory()));
        hostStats.setLoadStats(getLoadStats());
        hostStats.setDiskStatsList(getDiskStats());
        hostStats.setFsStatsList(getFsStats(os.getFileSystem()));
        hostStats.setNetworkStatsList(getNetworkInterfaceStats(3000L));
        hostStats.setInternetStats(getInternetProtocolStats());
        hostStats.setDockerPsList(getDockerPsList());
        hostStats.setDockerStatsList(getDockerStatsList());
        return hostStats;
    }

    /**
     * 获取服务器功率。
     *
     * @return
     */
    private static LoadStats getLoadStats() {
        //计算电源功率
        double power = 0.0d;
        for (PowerSource ps : hal.getPowerSources()) {
            power += ps.getPowerUsageRate();
        }
        //计算负载计数
        double[] loadAverage = hal.getProcessor().getSystemLoadAverage(3);
        return new LoadStats(os.getSystemUptime(), Math.round(100 * loadAverage[0]) / 100d, Math.round(100 * loadAverage[1]) / 100d, Math.round(100 * loadAverage[2]) / 100d, (long) power, os.getProcessCount(), os.getThreadCount());
    }

    /**
     * 获取处理器信息。
     *
     * @param processor
     * @return
     */
    private static String getProcessorInfo(CentralProcessor processor) {
        CentralProcessor.ProcessorIdentifier pi = processor.getProcessorIdentifier();
        StringBuilder sb = new StringBuilder();
        sb.append(pi.getName());
//        sb.append(" ").append(pi.getVendorFreq() / 100000000L / 10d).append("Ghz ");
        sb.append(processor.getPhysicalPackageCount()).append("CPU").append(processor.getPhysicalProcessorCount()).append("C").append(processor.getLogicalProcessorCount()).append("T");
        return sb.toString();
    }

    /**
     * 获取内存信息。
     *
     * @param memory
     * @return
     */
    private static String getMemoryInfo(GlobalMemory memory) {
        return (memory.getTotal() >> 30) + "G";
    }

    /**
     * 获取内存统计信息。
     *
     * @param memory
     * @return
     */
    private static MemStats getMemoryStats(GlobalMemory memory) {
        VirtualMemory vm = memory.getVirtualMemory();
        return new MemStats(memory.getTotal(), memory.getAvailable(), memory.getPageSize(), vm.getSwapTotal(), vm.getSwapUsed(), vm.getVirtualMax(), vm.getVirtualInUse(), vm.getSwapPagesIn(), vm.getSwapPagesOut());
    }

    /**
     * 获取cpu信息。
     *
     * @param processor
     * @return
     */
    private static CpuStats getCpuStats(CentralProcessor processor, long interval) {
        long[] prevTicks = processor.getSystemCpuLoadTicks();
        long prevCs = processor.getContextSwitches();
        long prevIn = processor.getInterrupts();
        Util.sleep(interval);
        long cs = (processor.getContextSwitches() - prevCs) / (interval / 1000);
        long in = (processor.getInterrupts() - prevIn) / (interval / 1000);
        long[] ticks = processor.getSystemCpuLoadTicks();
        long user = ticks[TickType.USER.getIndex()] - prevTicks[TickType.USER.getIndex()];
        long nice = ticks[TickType.NICE.getIndex()] - prevTicks[TickType.NICE.getIndex()];
        long sys = ticks[TickType.SYSTEM.getIndex()] - prevTicks[TickType.SYSTEM.getIndex()];
        long idle = ticks[TickType.IDLE.getIndex()] - prevTicks[TickType.IDLE.getIndex()];
        long iowait = ticks[TickType.IOWAIT.getIndex()] - prevTicks[TickType.IOWAIT.getIndex()];
        long irq = ticks[TickType.IRQ.getIndex()] - prevTicks[TickType.IRQ.getIndex()];
        long softirq = ticks[TickType.SOFTIRQ.getIndex()] - prevTicks[TickType.SOFTIRQ.getIndex()];
        long steal = ticks[TickType.STEAL.getIndex()] - prevTicks[TickType.STEAL.getIndex()];
        long totalCpu = user + nice + sys + idle + iowait + irq + softirq + steal;
        return new CpuStats(cs, in, Math.round(10000 * user / totalCpu) / 100d, Math.round(10000 * nice / totalCpu) / 100d, Math.round(10000 * sys / totalCpu) / 100d, Math.round(10000 * idle / totalCpu) / 100d, Math.round(10000 * iowait / totalCpu) / 100d, Math.round(10000 * irq / totalCpu) / 100d, Math.round(10000 * softirq / totalCpu) / 100d, Math.round(10000 * steal / totalCpu) / 100d);
    }


    /**
     * 获取磁盘信息。
     */
    private static List<DiskStats> getDiskStats() {
        List<DiskStats> list = new ArrayList<>();
        for (HWDiskStore hd : hal.getDiskStores()) {
            DiskStats diskStats = new DiskStats();
            list.add(diskStats);
            hd.updateAttributes();
            diskStats.setName(hd.getName());
            diskStats.setModel(hd.getModel());
            diskStats.setSerial(hd.getSerial());
            diskStats.setSize(hd.getSize());
            diskStats.setReads(hd.getReads());
            diskStats.setReadBytes(hd.getReadBytes());
            diskStats.setWrites(hd.getWrites());
            diskStats.setWriteBytes(hd.getWriteBytes());
            diskStats.setQueueLength(hd.getCurrentQueueLength());
            diskStats.setTransferTime(hd.getTransferTime());
        }
        return list;
    }

    /**
     * 获取文件系统。
     *
     * @param fileSystem
     * @return
     */
    private static List<FsStats> getFsStats(FileSystem fileSystem) {
        List<FsStats> list = new ArrayList<>();
        for (OSFileStore fs : fileSystem.getFileStores()) {
            FsStats diskStats = new FsStats();
            list.add(diskStats);
            diskStats.setName(fs.getName());
            diskStats.setFsType(fs.getType());
            diskStats.setMount(fs.getMount());
            diskStats.setVolume(fs.getVolume());
            diskStats.setUsable(fs.getUsableSpace());
            diskStats.setTotal(fs.getTotalSpace());
            diskStats.setUsable(fs.getUsableSpace());
            diskStats.setTotal(fs.getTotalSpace());
        }
        return list;
    }

    /**
     * 获取网络接口信息。
     *
     * @return
     */
    private static List<NetworkStats> getNetworkInterfaceStats(long interval) throws SocketException {
        List<NetworkIF> prevList = null;
        if (interval > 0) {
            prevList = hal.getNetworkIFs(false);
            Util.sleep(interval);
        }
        List<NetworkIF> list = hal.getNetworkIFs(false);
        List<NetworkStats> dataList = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            NetworkIF net = list.get(i);
            NetworkInterface ni = net.queryNetworkInterface();
            if (net.getSpeed() == 0 || net.getIPv4addr().length == 0) {
                continue;
            }
            if (net.getName().startsWith("docker")) {
                continue;
            }
            NetworkStats networkStats = new NetworkStats();
            networkStats.setName(net.getName());
            networkStats.setIp(Arrays.toString(net.getIPv4addr()));
            networkStats.setMac(net.getMacaddr());
            networkStats.setSpeed(net.getSpeed());
            networkStats.setIndex(net.getIndex());
            if (interval > 0) {
                NetworkIF prevNet = prevList.get(i);
                double timeDiff = (net.getTimeStamp() - prevNet.getTimeStamp()) / 1000d;
                long txRate = Math.round((net.getBytesSent() - prevNet.getBytesSent()) / timeDiff);
                long rxRate = Math.round((net.getBytesRecv() - prevNet.getBytesRecv()) / timeDiff);
                networkStats.setTxRate(txRate);
                networkStats.setRxRate(rxRate);
            }
            dataList.add(networkStats);
        }
        Collections.sort(dataList, Comparator.comparingInt(NetworkStats::getIndex));
        return dataList;
    }

    /**
     * 查询联网协议统计。
     */
    private static InternetStats getInternetProtocolStats() {
        String[] cmdToRunWithArgs = new String[]{"/bin/sh", "-c", "ss -t | awk '{++S[$1]} END {for(a in S) print a, S[a]}'"};
        List<String> dataList = ShellCmdUtils.runNative(cmdToRunWithArgs, null, false);
        InternetStats is = new InternetStats();
        for (String line : dataList) {
            if (line.startsWith("ESTAB")) {
                is.setEstablished(Integer.parseInt(line.substring(6)));
            } else if (line.startsWith("CLOSE-WAIT")) {
                is.setCloseWait(Integer.parseInt(line.substring(11)));
            } else if (line.startsWith("TIME-WAIT")) {
                is.setTimeWait(Integer.parseInt(line.substring(10)));
            } else if (line.startsWith("SYN-SENT")) {
                is.setSynSent(Integer.parseInt(line.substring(9)));
            } else if (line.startsWith("SYN-RECV")) {
                is.setSynSent(Integer.parseInt(line.substring(9)));
            }
        }
        return is;
    }

    /**
     * 获取docker ps指令列表。
     *
     * @return
     */
    private static List<DockerPs> getDockerPsList() {
        List<DockerPs> psList = new ArrayList<>();
        try {
            List<String> dataList = ShellCmdUtils.runNative(new String[]{"docker", "ps", "--no-trunc", "--format", "{\"ID\":\"{{.ID}}\",\"Name\":\"{{.Names}}\",\"Image\":\"{{.Image}}\",\"Command\":{{json .Command}},\"CreatedAt\":\"{{.CreatedAt}}\",\"State\":\"{{.State}}\",\"Status\":\"{{.Status}}\",\"Mounts\":\"{{.Mounts}}\",\"Networks\":\"{{.Networks}}\",\"Ports\":\"{{.Ports}}\"}"});
            DockerPsCmd[] psCmds = JsonUtils.parse("[" + StringUtils.join(dataList, ",") + "]", DockerPsCmd[].class);
            for (DockerPsCmd cmd : psCmds) {
                DockerPs dp = new DockerPs();
                psList.add(dp);
                dp.setId(cmd.getID());
                dp.setName(cmd.getName());
                dp.setImage(cmd.getImage());
                dp.setCommand(cmd.getCommand());
                dp.setCreateDate(dateFormat.parse(cmd.getCreatedAt()));
                dp.setNetwork(cmd.getNetworks());
                dp.setState(cmd.getState());
                dp.setStatus(cmd.getStatus());
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return psList;
    }

    /**
     * 获取docker stats指令列表。
     *
     * @return
     */
    private static List<DockerStats> getDockerStatsList() {
        List<DockerStats> statsList = new ArrayList<>();
        try {
            List<String> dataList = ShellCmdUtils.runNative(new String[]{"docker", "stats", "--no-trunc", "--no-stream", "--format", "{\"ID\":\"{{.ID}}\",\"Name\":\"{{.Name}}\",\"CPUPerc\":\"{{.CPUPerc}}\",\"MemPerc\":\"{{.MemPerc}}\",\"MemUsage\":\"{{.MemUsage}}\",\"BlockIO\":\"{{.BlockIO}}\",\"NetIO\":\"{{.NetIO}}\",\"PIDs\":{{.PIDs}}}"});
            DockerStatsCmd[] stCmds = JsonUtils.parse("[" + StringUtils.join(dataList, ",") + "]", DockerStatsCmd[].class);
            for (DockerStatsCmd cmd : stCmds) {
                DockerStats ds = new DockerStats();
                statsList.add(ds);
                ds.setId(cmd.getID());
                String cpuPerc = cmd.getCPUPerc();
                ds.setCpuPercent(Double.parseDouble(cpuPerc.substring(0, cpuPerc.length() - 1)));
                String memPerc = cmd.getMemPerc();
                ds.setMemPercent(Double.parseDouble(memPerc.substring(0, memPerc.length() - 1)));
                String[] mems = cmd.getMemUsage().split("/");
                ds.setMemUsage(parseKMGTData(mems[0].trim()));
                ds.setMemTotal(parseKMGTData(mems[1].trim()));
                String[] blocks = cmd.getBlockIO().split("/");
                ds.setDiskWrite(parseKMGTData(blocks[0].trim()));
                ds.setDiskRead(parseKMGTData(blocks[1].trim()));
                String[] nets = cmd.getNetIO().split("/");
                ds.setNetworkIn(parseKMGTData(nets[0].trim()));
                ds.setNetworkIn(parseKMGTData(nets[1].trim()));
                ds.setPidCount(Integer.parseInt(cmd.getPIDs()));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return statsList;
    }

    /**
     * 解析k，m，g，t的数据。
     * 当前支持mib和mb的转换。
     *
     * @param data
     * @return
     */
    private static long parseKMGTData(String data) {
        data = data.toLowerCase();
        int len = data.length();
        //最后一位不是b的，直接返回-1
        if (len < 2 || data.charAt(len - 1) != 'b') {
            return -1;
        }
        char p2 = data.charAt(len - 2);
        if (p2 >= '0' && p2 <= '9') {
            //直接截取数字返回
            return Long.parseLong(data.substring(0, len - 1));
        }
        if (p2 == 'i' && len > 3) {
            char p3 = data.charAt(len - 3);
            double d = Double.parseDouble(data.substring(0, len - 3));
            if (p3 == 'k') {
                d *= 1 << 10;
            } else if (p3 == 'm') {
                d *= 1 << 20;
            } else if (p3 == 'g') {
                d *= 1 << 30;
            } else if (p3 == 't') {
                d *= 1 << 40;
            }
            return (long) d;
            //2进制
        } else {
            double d = Double.parseDouble(data.substring(0, len - 2));
            if (p2 == 'k') {
                d *= 1000;
            } else if (p2 == 'm') {
                d *= 1.0E6;
            } else if (p2 == 'g') {
                d *= 1.0E9;
            } else if (p2 == 't') {
                d *= 1.0E12;
            }
            return (long) d;
        }
    }

}