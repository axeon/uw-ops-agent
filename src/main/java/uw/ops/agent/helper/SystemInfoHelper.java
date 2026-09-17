package uw.ops.agent.helper;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.hardware.VirtualMemory;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import uw.common.util.DigestUtils;
import uw.common.util.JsonUtils;
import uw.ops.agent.helper.vo.CpuCounters;
import uw.ops.agent.helper.vo.DiskCounters;
import uw.ops.agent.helper.vo.NetIfCounters;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.sub.DiskInfo;
import uw.ops.agent.vo.sub.FsStats;
import uw.ops.agent.vo.sub.InternetStats;
import uw.ops.agent.vo.sub.LoadStats;
import uw.ops.agent.vo.sub.MemStats;
import uw.ops.agent.vo.sub.NetworkInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统信息采集助手。基于 OSHI 采集主机硬件信息(CPU/内存/磁盘/网络/文件系统)，供 Agent 上报使用。
 * <p>
 * 结构按消费节奏分组：
 * <ol>
 *   <li>主机标识：进程级不变量（首次计算后锁定，不随重采漂移）；</li>
 *   <li>静态信息：每小时 HostInfo 上报（硬件信息开机基本不变，重报价值=IP/热插盘/改名自愈）；</li>
 *   <li>主轮快照：60 秒窗口边界计数与末值（供 HostStatsCollector 两点差分）。</li>
 * </ol>
 * 全部非阻塞读取：指标（CPU ticks/cs/int/网卡/磁盘）仅在主轮每次激发时采一次边界快照，
 * 与上次激发做两点差分——累计计数的差值即精确满窗均值，无需多轮高频采样；
 * 均值/速率/占比在 Agent 端算好再报，本类无任何 sleep 阻塞采样。
 * Docker 容器采集走 {@link DockerInfoHelper}（本机 Engine API）。
 */
public class SystemInfoHelper {

    //===== OSHI 单例依赖 =====

    private static final SystemInfo systemInfo = new SystemInfo();

    private static final HardwareAbstractionLayer hal = systemInfo.getHardware();

    private static final OperatingSystem os = systemInfo.getOperatingSystem();

    //===== 主机标识（进程级不变量） =====

    /**
     * 主机唯一标识（进程级不变量，首次获取时计算并锁定）：machineModel$serialNumber$首选网卡MAC
     * 的 SHA-256。锁定后进程生命周期内不再重算——网卡枚举抖动（USB/虚拟网卡增减改变"首个"的判定）
     * 不得漂移主机身份，否则 Center 端被识别为新主机，台账/鉴权/容器归属分裂。
     */
    private static volatile String hostHash;

    /**
     * 获取主机唯一标识（首次调用时计算一次，此后恒定不变）。
     *
     * @return hostHash
     */
    public static String getHostHash() {
        String hash = hostHash;
        if (hash == null) {
            synchronized (SystemInfoHelper.class) {
                if (hostHash == null) {
                    ComputerSystem computerSystem = hal.getComputerSystem();
                    hostHash = DigestUtils.signHex(computerSystem.getModel() + "$" + computerSystem.getSerialNumber() + "$" + primaryNicMac(), DigestUtils.Algorithm.SHA_256);
                }
                hash = hostHash;
            }
        }
        return hash;
    }

    /**
     * 主机标识用首选网卡 MAC：物理网卡中排除虚拟与可移动命名后，按名字序取首个——
     * 机载网卡的 predictable name（eno、enp、eth、ens 前缀）跨重启恒定，而 ifindex 随接口创建
     * 顺序变化不作依据；无候选返回空串（model$serial 已基本唯一，MAC 为 VM 克隆场景
     * 的加强项，virtio 网卡名为 eth、enp 前缀不在排除名单）。
     */
    private static String primaryNicMac() {
        String chosenName = null;
        String chosenMac = "";
        for (NetworkIF net : hal.getNetworkIFs(false)) {
            String name = net.getName();
            //标识选择与是否配置 IPv4 无关（未启用/IP 变动不得改变主机身份）。
            if (isVirtualNicName(name) || isRemovableNicName(name)) {
                continue;
            }
            if (chosenName == null || name.compareTo(chosenName) < 0) {
                chosenName = name;
                chosenMac = net.getMacaddr();
            }
        }
        return chosenMac;
    }

    /**
     * 虚拟网卡命名判定：lo/docker/veth/br-/vmnet/virbr/tun/tap 前缀。
     */
    private static boolean isVirtualNicName(String netName) {
        return netName.startsWith("lo") || netName.startsWith("docker") || netName.startsWith("veth") ||
                netName.startsWith("br-") || netName.startsWith("vmnet") || netName.startsWith("virbr") ||
                netName.startsWith("tun") || netName.startsWith("tap");
    }

    /**
     * 可移动/外接网卡命名前缀（Linux predictable naming：enx&lt;MAC&gt; 为 MAC 命名的 USB 网卡、
     * usb* 为 USB 总线设备）：不参与主机标识——外接网卡热插拔不得漂移 hostHash
     * （监控计数仍全量包含，仅标识选择排除）。
     */
    private static final List<String> REMOVABLE_NIC_PREFIXES = List.of("enx", "usb");

    /**
     * 可移动网卡命名判定（enx&lt;MAC&gt;/usb*，见 {@link #REMOVABLE_NIC_PREFIXES}）。
     */
    private static boolean isRemovableNicName(String netName) {
        for (String prefix : REMOVABLE_NIC_PREFIXES) {
            if (netName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    //===== 静态信息（每小时 HostInfo 上报） =====

    /**
     * 构建系统信息（每小时上报；hostHash 读锁定值不重算）。
     */
    public static HostInfo buildHostInfo() {
        HostInfo hostInfo = new HostInfo();
        ComputerSystem computerSystem = hal.getComputerSystem();
        hostInfo.setHostName(os.getNetworkParams().getHostName());
        hostInfo.setManufacturer(computerSystem.getManufacturer());
        hostInfo.setMachineModel(computerSystem.getModel());
        hostInfo.setSerialNumber(computerSystem.getSerialNumber());
        hostInfo.setOsInfo(os.toString());
        hostInfo.setCpuInfo(getProcessorInfo(hal.getProcessor()));
        hostInfo.setMemInfo(getMemoryInfo(hal.getMemory()));
        List<DiskInfo> diskInfoList = buildDiskInfoList();
        hostInfo.setDiskInfo(JsonUtils.toString(diskInfoList));
        List<FsStats> fsStatsList = buildFsStatsList();
        hostInfo.setFsInfo(JsonUtils.toString(fsStatsList));
        List<NetworkInfo> networkInfoList = buildNetworkInfoList();
        hostInfo.setNetworkInfo(JsonUtils.toString(networkInfoList));
        hostInfo.setHostHash(getHostHash());
        return hostInfo;
    }

    /**
     * 磁盘静态信息列表（每小时 HostInfo 上报）。
     *
     * @return 磁盘信息列表
     */
    public static List<DiskInfo> buildDiskInfoList() {
        List<DiskInfo> list = new ArrayList<>();
        for (HWDiskStore hd : hal.getDiskStores()) {
            DiskInfo diskInfo = new DiskInfo();
            diskInfo.setName(hd.getName());
            diskInfo.setModel(hd.getModel());
            diskInfo.setSerial(hd.getSerial());
            diskInfo.setSize(hd.getSize());
            list.add(diskInfo);
        }
        return list;
    }

    /**
     * 网卡静态信息列表（每小时 HostInfo 上报，全量含外接网卡；主机标识的 MAC 选择独立于本列表，
     * 见 {@link #primaryNicMac()}）。
     *
     * @return 网卡信息列表
     */
    public static List<NetworkInfo> buildNetworkInfoList() {
        List<NetworkInfo> dataList = new ArrayList<>();
        for (NetworkIF net : sortedPhysicalNetworkIFs()) {
            NetworkInfo networkInfo = new NetworkInfo();
            networkInfo.setName(net.getName());
            networkInfo.setIp(Arrays.toString(net.getIPv4addr()));
            networkInfo.setMac(net.getMacaddr());
            networkInfo.setSpeed(net.getSpeed());
            networkInfo.setIndex(net.getIndex());
            dataList.add(networkInfo);
        }
        return dataList;
    }

    /**
     * 获取处理器信息。
     */
    private static String getProcessorInfo(CentralProcessor processor) {
        CentralProcessor.ProcessorIdentifier pi = processor.getProcessorIdentifier();
        return pi.getName() + " " + processor.getPhysicalPackageCount() + "CPU" + processor.getPhysicalProcessorCount() + "C" + processor.getLogicalProcessorCount() + "T";
    }

    /**
     * 获取内存信息。
     */
    private static String getMemoryInfo(GlobalMemory memory) {
        return (memory.getTotal() >> 30) + "G";
    }

    /**
     * 过滤物理网卡：有 IPv4 且排除常见虚拟网卡（见 {@link #isVirtualNicName(String)}）。
     *
     * @return 物理网卡列表
     */
    private static List<NetworkIF> filterPhysicalNetworkIFs() {
        List<NetworkIF> list = new ArrayList<>();
        for (NetworkIF net : hal.getNetworkIFs(false)) {
            // 无 IPv4 的网卡不参与监控计数。
            if (net.getIPv4addr().length == 0) {
                continue;
            }
            // 虚拟网卡不参与监控计数。
            if (isVirtualNicName(net.getName())) {
                continue;
            }
            list.add(net);
        }
        return list;
    }

    /**
     * 物理网卡列表（按 index 升序，计数快照与静态信息列表共用同一排序——
     * 仅监控展示口径，与主机标识的 MAC 选择无关）。
     *
     * @return 排序后的物理网卡列表
     */
    private static List<NetworkIF> sortedPhysicalNetworkIFs() {
        List<NetworkIF> physicalIfs = filterPhysicalNetworkIFs();
        physicalIfs.sort(Comparator.comparingInt(NetworkIF::getIndex));
        return physicalIfs;
    }

    //===== 主轮快照（60 秒窗口边界计数与末值，供 HostStatsCollector 两点差分） =====

    /**
     * CPU 边界计数快照（ticks/cs/int 同刻一次采集，一次 /proc 读取）。
     *
     * @return CPU 边界计数
     */
    public static CpuCounters buildCpuCounters() {
        CentralProcessor processor = hal.getProcessor();
        return new CpuCounters(processor.getSystemCpuLoadTicks(), processor.getContextSwitches(), processor.getInterrupts());
    }

    /**
     * 物理网卡计数快照：name -&gt; 累计收发字节（按 index 升序插入 LinkedHashMap，
     * 差分与骨架共用本快照，不再二次遍历 OSHI——对齐磁盘侧模式；插入序即 net1/net2 位置序）。
     *
     * @return 网卡计数
     */
    public static Map<String, NetIfCounters> buildNetworkCounters() {
        Map<String, NetIfCounters> netCounters = new LinkedHashMap<>();
        for (NetworkIF net : sortedPhysicalNetworkIFs()) {
            netCounters.put(net.getName(), new NetIfCounters(net.getBytesRecv(), net.getBytesSent()));
        }
        return netCounters;
    }

    /**
     * 磁盘计数快照：name -&gt; 计数（5 项累计 + queueLength 即时值）——一次 updateAttributes
     * 同刻取全，差分与骨架共用本快照，不再二次遍历 OSHI。
     *
     * @return 磁盘计数
     */
    public static Map<String, DiskCounters> buildDiskCounters() {
        Map<String, DiskCounters> diskCounters = new HashMap<>();
        for (HWDiskStore hd : hal.getDiskStores()) {
            hd.updateAttributes();
            diskCounters.put(hd.getName(), new DiskCounters(hd.getReads(), hd.getReadBytes(), hd.getWrites(), hd.getWriteBytes(), hd.getTransferTime(), hd.getCurrentQueueLength()));
        }
        return diskCounters;
    }

    /**
     * 内存统计（窗口结束时快照）。
     *
     * @return 内存统计
     */
    public static MemStats buildMemStats() {
        GlobalMemory memory = hal.getMemory();
        VirtualMemory vm = memory.getVirtualMemory();
        return new MemStats(memory.getTotal(), memory.getAvailable(), memory.getPageSize(), vm.getSwapTotal(), vm.getSwapUsed(), vm.getVirtualMax(), vm.getVirtualInUse(), vm.getSwapPagesIn(), vm.getSwapPagesOut());
    }

    /**
     * 负载统计（窗口结束时快照：load average/进程/线程/uptime）。
     *
     * @return 负载统计
     */
    public static LoadStats buildLoadStats() {
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
     * 文件系统统计（窗口结束时快照）：过滤容器运行时挂载（overlay/aufs 联合挂载与
     * docker/podman/kubelet 目录下的 shm/mqueue 等），只上报真实数据盘——
     * 千容器宿主机此类挂载上千个，非数据盘且占每轮上报体积的大头。
     *
     * @return 文件系统列表
     */
    public static List<FsStats> buildFsStatsList() {
        List<FsStats> list = new ArrayList<>();
        for (OSFileStore fs : os.getFileSystem().getFileStores()) {
            if (isContainerMount(fs.getType(), fs.getMount())) {
                continue;
            }
            FsStats diskStats = new FsStats();
            diskStats.setName(fs.getName());
            diskStats.setFsType(fs.getType());
            diskStats.setMount(fs.getMount());
            diskStats.setVolume(fs.getVolume());
            diskStats.setUsable(fs.getUsableSpace());
            diskStats.setTotal(fs.getTotalSpace());
            list.add(diskStats);
        }
        return list;
    }

    /**
     * 容器运行时挂载类型（overlay2/aufs 联合挂载，容器 rootfs）。
     */
    private static final Set<String> CONTAINER_FS_TYPES = Set.of("overlay", "aufs");

    /**
     * 容器运行时挂载点前缀（docker/podman/kubelet 目录下的镜像层、shm、mqueue 挂载）。
     */
    private static final List<String> CONTAINER_MOUNT_PREFIXES = List.of(
            "/var/lib/docker/", "/var/lib/containers/", "/var/lib/kubelet/");

    /**
     * 容器运行时挂载判定：类型为容器联合文件系统，或挂载点位于容器运行时目录下。
     *
     * @param fsType 文件系统类型(ext4/xfs/overlay...)
     * @param mount  挂载点路径
     * @return 属于容器运行时挂载时返回 true（过滤不上报）
     */
    private static boolean isContainerMount(String fsType, String mount) {
        if (fsType != null && CONTAINER_FS_TYPES.contains(fsType)) {
            return true;
        }
        if (mount == null) {
            return false;
        }
        for (String prefix : CONTAINER_MOUNT_PREFIXES) {
            if (mount.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * TCP 连接状态统计（窗口结束时快照）：直读 /proc/net/tcp(/tcp6) 按第 4 列 st 十六进制状态计数
     * （01=ESTAB/02=SYN-SENT/03=SYN-RECV/06=TIME-WAIT/08=CLOSE-WAIT，对齐 ss -t 口径，LISTEN 不计），
     * 进程内单遍读文件、零 fork（精简宿主机可能缺 ss/iproute2 工具）。
     *
     * @return TCP 状态统计（无 /proc 环境时全 0）
     */
    public static InternetStats buildInternetStats() {
        InternetStats stats = new InternetStats();
        countTcpStates("/proc/net/tcp", stats);
        countTcpStates("/proc/net/tcp6", stats);
        return stats;
    }

    /**
     * 单文件 TCP 状态计数：行格式 "sl local_address rem_address st ..."，st 为十六进制状态位。
     */
    private static void countTcpStates(String path, InternetStats stats) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(Paths.get(path)), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 4) {
                    continue;
                }
                switch (fields[3]) {
                    case "01" -> stats.setEstablished(stats.getEstablished() + 1);
                    case "02" -> stats.setSynSent(stats.getSynSent() + 1);
                    case "03" -> stats.setSynRecv(stats.getSynRecv() + 1);
                    case "06" -> stats.setTimeWait(stats.getTimeWait() + 1);
                    case "08" -> stats.setCloseWait(stats.getCloseWait() + 1);
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            //无 /proc 环境(非 Linux 开发机)：该组指标跳过（全 0），不拖垮整个上报窗口。
        }
    }

}
