package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Docker Engine API POST /containers/create 请求体（结构化任务通道出站参数）。
 * <p>
 * 字段映射 moby Engine API v1.56 swagger 的 {@code ContainerConfig}（顶层）、{@code HostConfig}
 * （HostConfig 段）与 {@code NetworkingConfig} 全集，全部强类型（空对象结构使用显式空类，不用 Object）；
 * 字段名保持 API 原始 PascalCase，null 字段序列化时省略。由 {@code StructuredTaskExecutor} 依
 * taskData 组装，{@code DockerInfoHelper#createContainer} 序列化下发。
 * <p>
 * 环境变量（含 profileEnv 密钥）直接经本请求体 Env 数组传递：UDS 请求体不落盘、不进进程列表，
 * 取代 shell 时代的临时 env-file 方案。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateContainerParam {

    /**
     * 主机名（--hostname）。
     */
    @JsonProperty("Hostname")
    private String hostname;

    /**
     * 域名（--domainname）。
     */
    @JsonProperty("Domainname")
    private String domainname;

    /**
     * 容器内用户（--user）。
     */
    @JsonProperty("User")
    private String user;

    /**
     * 是否禁用网络（--network none 的等价标记，极少用）。
     */
    @JsonProperty("NetworkDisabled")
    private Boolean networkDisabled;

    /**
     * 附加 tty（-t）。
     */
    @JsonProperty("Tty")
    private Boolean tty;

    /**
     * 保持标准输入打开（-i）。
     */
    @JsonProperty("OpenStdin")
    private Boolean openStdin;

    /**
     * attach 时只读一次标准输入（配合 -i）。
     */
    @JsonProperty("StdinOnce")
    private Boolean stdinOnce;

    /**
     * attach 标准输入。
     */
    @JsonProperty("AttachStdin")
    private Boolean attachStdin;

    /**
     * attach 标准输出。
     */
    @JsonProperty("AttachStdout")
    private Boolean attachStdout;

    /**
     * attach 标准错误。
     */
    @JsonProperty("AttachStderr")
    private Boolean attachStderr;

    /**
     * 命令是否已被转义（Windows cmdline 场景，极少用）。
     */
    @JsonProperty("ArgsEscaped")
    private Boolean argsEscaped;

    /**
     * 镜像引用（registry/imageName@sha256:... 或 registry/imageName:tag）。
     */
    @JsonProperty("Image")
    private String image;

    /**
     * 环境变量（"KEY=VALUE" 字符串列表，含 TZ 与 profileEnv，密钥随 UDS 请求体直传 daemon 不落盘）。
     */
    @JsonProperty("Env")
    private List<String> env;

    /**
     * 命令覆盖（docker run 镜像名后的命令与参数）。
     */
    @JsonProperty("Cmd")
    private List<String> cmd;

    /**
     * 命令/参数转义用的 shell（如 ["/bin/sh","-c"]，极少用）。
     */
    @JsonProperty("Shell")
    private List<String> shell;

    /**
     * 入口点覆盖（--entrypoint）。
     */
    @JsonProperty("Entrypoint")
    private List<String> entrypoint;

    /**
     * 受管标记（uw.ops.plan-id / uw.ops.container-id，探测识别与管理分组）。
     */
    @JsonProperty("Labels")
    private Map<String, String> labels;

    /**
     * 声明挂载点（"/data" -> 空结构；实际数据卷由 HostConfig.Binds/Mounts 提供）。
     */
    @JsonProperty("Volumes")
    private Map<String, VolumeDeclaration> volumes;

    /**
     * 宿主机侧安全路径白名单覆盖（--read-only 场景配合，极少用）。
     */
    @JsonProperty("ExposedPorts")
    private Map<String, ExposedPort> exposedPorts;

    /**
     * 工作目录（--workdir）。
     */
    @JsonProperty("WorkingDir")
    private String workingDir;

    /**
     * 入口标签（Dockerfile ONBUILD 遗留字段，运行容器创建无实际意义，完整映射保留）。
     */
    @JsonProperty("OnBuild")
    private List<String> onBuild;

    /**
     * 停止信号（--stop-signal，默认 SIGTERM）。
     */
    @JsonProperty("StopSignal")
    private String stopSignal;

    /**
     * 停止超时秒数（--stop-timeout）。
     */
    @JsonProperty("StopTimeout")
    private Integer stopTimeout;

    /**
     * Docker 原生健康检查（--health-*；ops 就绪探测在 Agent 侧 statusScript，本字段供需要时使用）。
     */
    @JsonProperty("Healthcheck")
    private HealthSection healthcheck;

    /**
     * 宿主机侧配置（挂载/端口绑定/资源/CPU/重启策略/网络/安全/日志）。
     */
    @JsonProperty("HostConfig")
    private HostConfigSection hostConfig;

    /**
     * 网络配置（--network 指定网络时的端点设置：别名/固定 IP/DNS 名等）。
     */
    @JsonProperty("NetworkingConfig")
    private NetworkingConfigSection networkingConfig;

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getDomainname() {
        return domainname;
    }

    public void setDomainname(String domainname) {
        this.domainname = domainname;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Boolean getNetworkDisabled() {
        return networkDisabled;
    }

    public void setNetworkDisabled(Boolean networkDisabled) {
        this.networkDisabled = networkDisabled;
    }

    public Boolean getTty() {
        return tty;
    }

    public void setTty(Boolean tty) {
        this.tty = tty;
    }

    public Boolean getOpenStdin() {
        return openStdin;
    }

    public void setOpenStdin(Boolean openStdin) {
        this.openStdin = openStdin;
    }

    public Boolean getStdinOnce() {
        return stdinOnce;
    }

    public void setStdinOnce(Boolean stdinOnce) {
        this.stdinOnce = stdinOnce;
    }

    public Boolean getAttachStdin() {
        return attachStdin;
    }

    public void setAttachStdin(Boolean attachStdin) {
        this.attachStdin = attachStdin;
    }

    public Boolean getAttachStdout() {
        return attachStdout;
    }

    public void setAttachStdout(Boolean attachStdout) {
        this.attachStdout = attachStdout;
    }

    public Boolean getAttachStderr() {
        return attachStderr;
    }

    public void setAttachStderr(Boolean attachStderr) {
        this.attachStderr = attachStderr;
    }

    public Boolean getArgsEscaped() {
        return argsEscaped;
    }

    public void setArgsEscaped(Boolean argsEscaped) {
        this.argsEscaped = argsEscaped;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getEnv() {
        return env;
    }

    public void setEnv(List<String> env) {
        this.env = env;
    }

    public List<String> getCmd() {
        return cmd;
    }

    public void setCmd(List<String> cmd) {
        this.cmd = cmd;
    }

    public List<String> getShell() {
        return shell;
    }

    public void setShell(List<String> shell) {
        this.shell = shell;
    }

    public List<String> getEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(List<String> entrypoint) {
        this.entrypoint = entrypoint;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public Map<String, VolumeDeclaration> getVolumes() {
        return volumes;
    }

    public void setVolumes(Map<String, VolumeDeclaration> volumes) {
        this.volumes = volumes;
    }

    public Map<String, ExposedPort> getExposedPorts() {
        return exposedPorts;
    }

    public void setExposedPorts(Map<String, ExposedPort> exposedPorts) {
        this.exposedPorts = exposedPorts;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public List<String> getOnBuild() {
        return onBuild;
    }

    public void setOnBuild(List<String> onBuild) {
        this.onBuild = onBuild;
    }

    public String getStopSignal() {
        return stopSignal;
    }

    public void setStopSignal(String stopSignal) {
        this.stopSignal = stopSignal;
    }

    public Integer getStopTimeout() {
        return stopTimeout;
    }

    public void setStopTimeout(Integer stopTimeout) {
        this.stopTimeout = stopTimeout;
    }

    public HealthSection getHealthcheck() {
        return healthcheck;
    }

    public void setHealthcheck(HealthSection healthcheck) {
        this.healthcheck = healthcheck;
    }

    public HostConfigSection getHostConfig() {
        return hostConfig;
    }

    public void setHostConfig(HostConfigSection hostConfig) {
        this.hostConfig = hostConfig;
    }

    public NetworkingConfigSection getNetworkingConfig() {
        return networkingConfig;
    }

    public void setNetworkingConfig(NetworkingConfigSection networkingConfig) {
        this.networkingConfig = networkingConfig;
    }

    /**
     * 宿主机配置段（create 请求的 HostConfig，映射 API v1.56 全部 70 字段：docker run 宿主侧参数全集）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HostConfigSection {

        /**
         * 附加非标识性元数据，透传给运行时（annotations）。
         */
        @JsonProperty("Annotations")
        private Map<String, String> annotations;

        /**
         * 退出即自动删除容器（--rm，与重启策略互斥）。
         */
        @JsonProperty("AutoRemove")
        private Boolean autoRemove;

        /**
         * 挂载（"src:dst[:ro]" 列表，如 /etc/localtime:/etc/localtime:ro）。
         */
        @JsonProperty("Binds")
        private List<String> binds;

        /**
         * 块设备读限速（字节/秒，--device-read-bps）。
         */
        @JsonProperty("BlkioDeviceReadBps")
        private List<ThrottleDeviceItem> blkioDeviceReadBps;

        /**
         * 块设备读限速（IO/秒，--device-read-iops）。
         */
        @JsonProperty("BlkioDeviceReadIOps")
        private List<ThrottleDeviceItem> blkioDeviceReadIOps;

        /**
         * 块设备写限速（字节/秒，--device-write-bps）。
         */
        @JsonProperty("BlkioDeviceWriteBps")
        private List<ThrottleDeviceItem> blkioDeviceWriteBps;

        /**
         * 块设备写限速（IO/秒，--device-write-iops）。
         */
        @JsonProperty("BlkioDeviceWriteIOps")
        private List<ThrottleDeviceItem> blkioDeviceWriteIOps;

        /**
         * 块 IO 相对权重（10-1000，--blkio-weight）。
         */
        @JsonProperty("BlkioWeight")
        private Long blkioWeight;

        /**
         * 块 IO 按设备权重（--blkio-weight-device）。
         */
        @JsonProperty("BlkioWeightDevice")
        private List<BlkioWeightDeviceItem> blkioWeightDevice;

        /**
         * 追加内核能力（--cap-add，如 NET_ADMIN）。
         */
        @JsonProperty("CapAdd")
        private List<String> capAdd;

        /**
         * 移除内核能力（--cap-drop）。
         */
        @JsonProperty("CapDrop")
        private List<String> capDrop;

        /**
         * cgroup 路径覆盖（极少用）。
         */
        @JsonProperty("Cgroup")
        private String cgroup;

        /**
         * 父 cgroup（--cgroup-parent）。
         */
        @JsonProperty("CgroupParent")
        private String cgroupParent;

        /**
         * cgroup 命名空间模式（--cgroupns：host/private）。
         */
        @JsonProperty("CgroupnsMode")
        private String cgroupnsMode;

        /**
         * 初始控制台尺寸 [height, width]（配合 -t，极少用）。
         */
        @JsonProperty("ConsoleSize")
        private List<Long> consoleSize;

        /**
         * 写容器 ID 到文件（--cidfile）。
         */
        @JsonProperty("ContainerIDFile")
        private String containerIdFile;

        /**
         * CPU 核数（Windows 场景）。
         */
        @JsonProperty("CpuCount")
        private Long cpuCount;

        /**
         * CPU 可用百分比（Windows 场景）。
         */
        @JsonProperty("CpuPercent")
        private Long cpuPercent;

        /**
         * CFS 周期（微秒，--cpu-period）。
         */
        @JsonProperty("CpuPeriod")
        private Long cpuPeriod;

        /**
         * CFS 配额（微秒，--cpu-quota）。
         */
        @JsonProperty("CpuQuota")
        private Long cpuQuota;

        /**
         * 实时调度周期（微秒，--cpu-rt-period）。
         */
        @JsonProperty("CpuRealtimePeriod")
        private Long cpuRealtimePeriod;

        /**
         * 实时调度时长（微秒，--cpu-rt-runtime）。
         */
        @JsonProperty("CpuRealtimeRuntime")
        private Long cpuRealtimeRuntime;

        /**
         * CPU 相对权重（--cpu-shares，默认 0）。
         */
        @JsonProperty("CpuShares")
        private Long cpuShares;

        /**
         * 绑定 CPU 核集合（--cpuset-cpus，如 "0-3"）。
         */
        @JsonProperty("CpusetCpus")
        private String cpusetCpus;

        /**
         * 绑定内存节点集合（--cpuset-mems）。
         */
        @JsonProperty("CpusetMems")
        private String cpusetMems;

        /**
         * 设备 cgroup 规则（--device-cgroup-rule，如 "c 13:* rwm"）。
         */
        @JsonProperty("DeviceCgroupRules")
        private List<String> deviceCgroupRules;

        /**
         * 设备请求（--gpus/--device 的结构化形态：驱动/数量/能力）。
         */
        @JsonProperty("DeviceRequests")
        private List<DeviceRequestSection> deviceRequests;

        /**
         * 设备映射（--device）。
         */
        @JsonProperty("Devices")
        private List<Device> devices;

        /**
         * 自定义 DNS（--dns）。
         */
        @JsonProperty("Dns")
        private List<String> dns;

        /**
         * DNS 选项（--dns-opt）。
         */
        @JsonProperty("DnsOptions")
        private List<String> dnsOptions;

        /**
         * DNS 搜索域（--dns-search）。
         */
        @JsonProperty("DnsSearch")
        private List<String> dnsSearch;

        /**
         * 追加 hosts 记录（--add-host，"name:ip"）。
         */
        @JsonProperty("ExtraHosts")
        private List<String> extraHosts;

        /**
         * 追加用户组（--group-add）。
         */
        @JsonProperty("GroupAdd")
        private List<String> groupAdd;

        /**
         * 注入 init 进程（--init）。
         */
        @JsonProperty("Init")
        private Boolean init;

        /**
         * IO 最大带宽（字节/秒）。
         */
        @JsonProperty("IOMaximumBandwidth")
        private Long ioMaximumBandwidth;

        /**
         * IO 最大 IO/秒。
         */
        @JsonProperty("IOMaximumIOps")
        private Long ioMaximumIops;

        /**
         * IPC 命名空间模式（--ipc）。
         */
        @JsonProperty("IpcMode")
        private String ipcMode;

        /**
         * 隔离模式（--isolation，Windows/hyperv）。
         */
        @JsonProperty("Isolation")
        private String isolation;

        /**
         * 链接其他容器（--link，遗留特性）。
         */
        @JsonProperty("Links")
        private List<String> links;

        /**
         * 日志配置（--log-driver / --log-opt）。
         */
        @JsonProperty("LogConfig")
        private LogConfigSection logConfig;

        /**
         * 掩码路径（/proc 掩码，覆盖默认）。
         */
        @JsonProperty("MaskedPaths")
        private List<String> maskedPaths;

        /**
         * 内存上限（字节，-m）。
         */
        @JsonProperty("Memory")
        private Long memory;

        /**
         * 内存软限制（字节，--memory-reservation）。
         */
        @JsonProperty("MemoryReservation")
        private Long memoryReservation;

        /**
         * 内存交换总量（字节，--memory-swap；-1=不限 swap）。
         */
        @JsonProperty("MemorySwap")
        private Long memorySwap;

        /**
         * 内存交换倾向（0-100，--memory-swappiness）。
         */
        @JsonProperty("MemorySwappiness")
        private Long memorySwappiness;

        /**
         * 结构化挂载（--mount，比 Binds 更完整的现代形态）。
         */
        @JsonProperty("Mounts")
        private List<MountSection> mounts;

        /**
         * CPU 配额（纳秒核，--cpus：1 核=1000000000）。
         */
        @JsonProperty("NanoCpus")
        private Long nanoCpus;

        /**
         * 网络模式（host / bridge / none / container:&lt;id&gt; / 网络名）。
         */
        @JsonProperty("NetworkMode")
        private String networkMode;

        /**
         * 禁用 OOM 杀容器（--oom-kill-disable）。
         */
        @JsonProperty("OomKillDisable")
        private Boolean oomKillDisable;

        /**
         * OOM 评分调整（--oom-score-adj，-1000~1000）。
         */
        @JsonProperty("OomScoreAdj")
        private Long oomScoreAdj;

        /**
         * PID 命名空间模式（--pid）。
         */
        @JsonProperty("PidMode")
        private String pidMode;

        /**
         * 进程数上限（--pids-limit）。
         */
        @JsonProperty("PidsLimit")
        private Long pidsLimit;

        /**
         * 端口绑定（容器端口/协议 -> 宿主端口绑定列表；host 网络下为 null）。
         */
        @JsonProperty("PortBindings")
        private Map<String, List<PortBinding>> portBindings;

        /**
         * 特权模式（--privileged）。
         */
        @JsonProperty("Privileged")
        private Boolean privileged;

        /**
         * 发布全部暴露端口（-P）。
         */
        @JsonProperty("PublishAllPorts")
        private Boolean publishAllPorts;

        /**
         * 只读路径（/proc 白名单，覆盖默认）。
         */
        @JsonProperty("ReadonlyPaths")
        private List<String> readonlyPaths;

        /**
         * 只读根文件系统（--read-only）。
         */
        @JsonProperty("ReadonlyRootfs")
        private Boolean readonlyRootfs;

        /**
         * 重启策略（unless-stopped / on-failure 等）。
         */
        @JsonProperty("RestartPolicy")
        private RestartPolicySection restartPolicy;

        /**
         * 运行时（--runtime，如 sysbox/nvidia）。
         */
        @JsonProperty("Runtime")
        private String runtime;

        /**
         * 安全选项（--security-opt，如 no-new-privileges）。
         */
        @JsonProperty("SecurityOpt")
        private List<String> securityOpt;

        /**
         * /dev/shm 大小（字节，--shm-size）。
         */
        @JsonProperty("ShmSize")
        private Long shmSize;

        /**
         * 存储驱动选项（--storage-opt，如 {"size":"120G"}）。
         */
        @JsonProperty("StorageOpt")
        private Map<String, String> storageOpt;

        /**
         * 内核参数（--sysctl）。
         */
        @JsonProperty("Sysctls")
        private Map<String, String> sysctls;

        /**
         * tmpfs 挂载（--tmpfs，路径 -> 挂载选项如 "rw,size=64m"）。
         */
        @JsonProperty("Tmpfs")
        private Map<String, String> tmpfs;

        /**
         * UTS 命名空间模式（--uts）。
         */
        @JsonProperty("UTSMode")
        private String utsMode;

        /**
         * ulimit（--ulimit）。
         */
        @JsonProperty("Ulimits")
        private List<Ulimit> ulimits;

        /**
         * umask（十进制表示，如 0022 -> 18）。
         */
        @JsonProperty("Umask")
        private Long umask;

        /**
         * 用户命名空间模式（--userns）。
         */
        @JsonProperty("UsernsMode")
        private String usernsMode;

        /**
         * 卷驱动（--volume-driver，遗留特性）。
         */
        @JsonProperty("VolumeDriver")
        private String volumeDriver;

        /**
         * 继承其他容器卷（--volumes-from）。
         */
        @JsonProperty("VolumesFrom")
        private List<String> volumesFrom;

        public Map<String, String> getAnnotations() {
            return annotations;
        }

        public void setAnnotations(Map<String, String> annotations) {
            this.annotations = annotations;
        }

        public Boolean getAutoRemove() {
            return autoRemove;
        }

        public void setAutoRemove(Boolean autoRemove) {
            this.autoRemove = autoRemove;
        }

        public List<String> getBinds() {
            return binds;
        }

        public void setBinds(List<String> binds) {
            this.binds = binds;
        }

        public List<ThrottleDeviceItem> getBlkioDeviceReadBps() {
            return blkioDeviceReadBps;
        }

        public void setBlkioDeviceReadBps(List<ThrottleDeviceItem> blkioDeviceReadBps) {
            this.blkioDeviceReadBps = blkioDeviceReadBps;
        }

        public List<ThrottleDeviceItem> getBlkioDeviceReadIOps() {
            return blkioDeviceReadIOps;
        }

        public void setBlkioDeviceReadIOps(List<ThrottleDeviceItem> blkioDeviceReadIOps) {
            this.blkioDeviceReadIOps = blkioDeviceReadIOps;
        }

        public List<ThrottleDeviceItem> getBlkioDeviceWriteBps() {
            return blkioDeviceWriteBps;
        }

        public void setBlkioDeviceWriteBps(List<ThrottleDeviceItem> blkioDeviceWriteBps) {
            this.blkioDeviceWriteBps = blkioDeviceWriteBps;
        }

        public List<ThrottleDeviceItem> getBlkioDeviceWriteIOps() {
            return blkioDeviceWriteIOps;
        }

        public void setBlkioDeviceWriteIOps(List<ThrottleDeviceItem> blkioDeviceWriteIOps) {
            this.blkioDeviceWriteIOps = blkioDeviceWriteIOps;
        }

        public Long getBlkioWeight() {
            return blkioWeight;
        }

        public void setBlkioWeight(Long blkioWeight) {
            this.blkioWeight = blkioWeight;
        }

        public List<BlkioWeightDeviceItem> getBlkioWeightDevice() {
            return blkioWeightDevice;
        }

        public void setBlkioWeightDevice(List<BlkioWeightDeviceItem> blkioWeightDevice) {
            this.blkioWeightDevice = blkioWeightDevice;
        }

        public List<String> getCapAdd() {
            return capAdd;
        }

        public void setCapAdd(List<String> capAdd) {
            this.capAdd = capAdd;
        }

        public List<String> getCapDrop() {
            return capDrop;
        }

        public void setCapDrop(List<String> capDrop) {
            this.capDrop = capDrop;
        }

        public String getCgroup() {
            return cgroup;
        }

        public void setCgroup(String cgroup) {
            this.cgroup = cgroup;
        }

        public String getCgroupParent() {
            return cgroupParent;
        }

        public void setCgroupParent(String cgroupParent) {
            this.cgroupParent = cgroupParent;
        }

        public String getCgroupnsMode() {
            return cgroupnsMode;
        }

        public void setCgroupnsMode(String cgroupnsMode) {
            this.cgroupnsMode = cgroupnsMode;
        }

        public List<Long> getConsoleSize() {
            return consoleSize;
        }

        public void setConsoleSize(List<Long> consoleSize) {
            this.consoleSize = consoleSize;
        }

        public String getContainerIdFile() {
            return containerIdFile;
        }

        public void setContainerIdFile(String containerIdFile) {
            this.containerIdFile = containerIdFile;
        }

        public Long getCpuCount() {
            return cpuCount;
        }

        public void setCpuCount(Long cpuCount) {
            this.cpuCount = cpuCount;
        }

        public Long getCpuPercent() {
            return cpuPercent;
        }

        public void setCpuPercent(Long cpuPercent) {
            this.cpuPercent = cpuPercent;
        }

        public Long getCpuPeriod() {
            return cpuPeriod;
        }

        public void setCpuPeriod(Long cpuPeriod) {
            this.cpuPeriod = cpuPeriod;
        }

        public Long getCpuQuota() {
            return cpuQuota;
        }

        public void setCpuQuota(Long cpuQuota) {
            this.cpuQuota = cpuQuota;
        }

        public Long getCpuRealtimePeriod() {
            return cpuRealtimePeriod;
        }

        public void setCpuRealtimePeriod(Long cpuRealtimePeriod) {
            this.cpuRealtimePeriod = cpuRealtimePeriod;
        }

        public Long getCpuRealtimeRuntime() {
            return cpuRealtimeRuntime;
        }

        public void setCpuRealtimeRuntime(Long cpuRealtimeRuntime) {
            this.cpuRealtimeRuntime = cpuRealtimeRuntime;
        }

        public Long getCpuShares() {
            return cpuShares;
        }

        public void setCpuShares(Long cpuShares) {
            this.cpuShares = cpuShares;
        }

        public String getCpusetCpus() {
            return cpusetCpus;
        }

        public void setCpusetCpus(String cpusetCpus) {
            this.cpusetCpus = cpusetCpus;
        }

        public String getCpusetMems() {
            return cpusetMems;
        }

        public void setCpusetMems(String cpusetMems) {
            this.cpusetMems = cpusetMems;
        }

        public List<String> getDeviceCgroupRules() {
            return deviceCgroupRules;
        }

        public void setDeviceCgroupRules(List<String> deviceCgroupRules) {
            this.deviceCgroupRules = deviceCgroupRules;
        }

        public List<DeviceRequestSection> getDeviceRequests() {
            return deviceRequests;
        }

        public void setDeviceRequests(List<DeviceRequestSection> deviceRequests) {
            this.deviceRequests = deviceRequests;
        }

        public List<Device> getDevices() {
            return devices;
        }

        public void setDevices(List<Device> devices) {
            this.devices = devices;
        }

        public List<String> getDns() {
            return dns;
        }

        public void setDns(List<String> dns) {
            this.dns = dns;
        }

        public List<String> getDnsOptions() {
            return dnsOptions;
        }

        public void setDnsOptions(List<String> dnsOptions) {
            this.dnsOptions = dnsOptions;
        }

        public List<String> getDnsSearch() {
            return dnsSearch;
        }

        public void setDnsSearch(List<String> dnsSearch) {
            this.dnsSearch = dnsSearch;
        }

        public List<String> getExtraHosts() {
            return extraHosts;
        }

        public void setExtraHosts(List<String> extraHosts) {
            this.extraHosts = extraHosts;
        }

        public List<String> getGroupAdd() {
            return groupAdd;
        }

        public void setGroupAdd(List<String> groupAdd) {
            this.groupAdd = groupAdd;
        }

        public Boolean getInit() {
            return init;
        }

        public void setInit(Boolean init) {
            this.init = init;
        }

        public Long getIoMaximumBandwidth() {
            return ioMaximumBandwidth;
        }

        public void setIoMaximumBandwidth(Long ioMaximumBandwidth) {
            this.ioMaximumBandwidth = ioMaximumBandwidth;
        }

        public Long getIoMaximumIops() {
            return ioMaximumIops;
        }

        public void setIoMaximumIops(Long ioMaximumIops) {
            this.ioMaximumIops = ioMaximumIops;
        }

        public String getIpcMode() {
            return ipcMode;
        }

        public void setIpcMode(String ipcMode) {
            this.ipcMode = ipcMode;
        }

        public String getIsolation() {
            return isolation;
        }

        public void setIsolation(String isolation) {
            this.isolation = isolation;
        }

        public List<String> getLinks() {
            return links;
        }

        public void setLinks(List<String> links) {
            this.links = links;
        }

        public LogConfigSection getLogConfig() {
            return logConfig;
        }

        public void setLogConfig(LogConfigSection logConfig) {
            this.logConfig = logConfig;
        }

        public List<String> getMaskedPaths() {
            return maskedPaths;
        }

        public void setMaskedPaths(List<String> maskedPaths) {
            this.maskedPaths = maskedPaths;
        }

        public Long getMemory() {
            return memory;
        }

        public void setMemory(Long memory) {
            this.memory = memory;
        }

        public Long getMemoryReservation() {
            return memoryReservation;
        }

        public void setMemoryReservation(Long memoryReservation) {
            this.memoryReservation = memoryReservation;
        }

        public Long getMemorySwap() {
            return memorySwap;
        }

        public void setMemorySwap(Long memorySwap) {
            this.memorySwap = memorySwap;
        }

        public Long getMemorySwappiness() {
            return memorySwappiness;
        }

        public void setMemorySwappiness(Long memorySwappiness) {
            this.memorySwappiness = memorySwappiness;
        }

        public List<MountSection> getMounts() {
            return mounts;
        }

        public void setMounts(List<MountSection> mounts) {
            this.mounts = mounts;
        }

        public Long getNanoCpus() {
            return nanoCpus;
        }

        public void setNanoCpus(Long nanoCpus) {
            this.nanoCpus = nanoCpus;
        }

        public String getNetworkMode() {
            return networkMode;
        }

        public void setNetworkMode(String networkMode) {
            this.networkMode = networkMode;
        }

        public Boolean getOomKillDisable() {
            return oomKillDisable;
        }

        public void setOomKillDisable(Boolean oomKillDisable) {
            this.oomKillDisable = oomKillDisable;
        }

        public Long getOomScoreAdj() {
            return oomScoreAdj;
        }

        public void setOomScoreAdj(Long oomScoreAdj) {
            this.oomScoreAdj = oomScoreAdj;
        }

        public String getPidMode() {
            return pidMode;
        }

        public void setPidMode(String pidMode) {
            this.pidMode = pidMode;
        }

        public Long getPidsLimit() {
            return pidsLimit;
        }

        public void setPidsLimit(Long pidsLimit) {
            this.pidsLimit = pidsLimit;
        }

        public Map<String, List<PortBinding>> getPortBindings() {
            return portBindings;
        }

        public void setPortBindings(Map<String, List<PortBinding>> portBindings) {
            this.portBindings = portBindings;
        }

        public Boolean getPrivileged() {
            return privileged;
        }

        public void setPrivileged(Boolean privileged) {
            this.privileged = privileged;
        }

        public Boolean getPublishAllPorts() {
            return publishAllPorts;
        }

        public void setPublishAllPorts(Boolean publishAllPorts) {
            this.publishAllPorts = publishAllPorts;
        }

        public List<String> getReadonlyPaths() {
            return readonlyPaths;
        }

        public void setReadonlyPaths(List<String> readonlyPaths) {
            this.readonlyPaths = readonlyPaths;
        }

        public Boolean getReadonlyRootfs() {
            return readonlyRootfs;
        }

        public void setReadonlyRootfs(Boolean readonlyRootfs) {
            this.readonlyRootfs = readonlyRootfs;
        }

        public RestartPolicySection getRestartPolicy() {
            return restartPolicy;
        }

        public void setRestartPolicy(RestartPolicySection restartPolicy) {
            this.restartPolicy = restartPolicy;
        }

        public String getRuntime() {
            return runtime;
        }

        public void setRuntime(String runtime) {
            this.runtime = runtime;
        }

        public List<String> getSecurityOpt() {
            return securityOpt;
        }

        public void setSecurityOpt(List<String> securityOpt) {
            this.securityOpt = securityOpt;
        }

        public Long getShmSize() {
            return shmSize;
        }

        public void setShmSize(Long shmSize) {
            this.shmSize = shmSize;
        }

        public Map<String, String> getStorageOpt() {
            return storageOpt;
        }

        public void setStorageOpt(Map<String, String> storageOpt) {
            this.storageOpt = storageOpt;
        }

        public Map<String, String> getSysctls() {
            return sysctls;
        }

        public void setSysctls(Map<String, String> sysctls) {
            this.sysctls = sysctls;
        }

        public Map<String, String> getTmpfs() {
            return tmpfs;
        }

        public void setTmpfs(Map<String, String> tmpfs) {
            this.tmpfs = tmpfs;
        }

        public String getUtsMode() {
            return utsMode;
        }

        public void setUtsMode(String utsMode) {
            this.utsMode = utsMode;
        }

        public List<Ulimit> getUlimits() {
            return ulimits;
        }

        public void setUlimits(List<Ulimit> ulimits) {
            this.ulimits = ulimits;
        }

        public Long getUmask() {
            return umask;
        }

        public void setUmask(Long umask) {
            this.umask = umask;
        }

        public String getUsernsMode() {
            return usernsMode;
        }

        public void setUsernsMode(String usernsMode) {
            this.usernsMode = usernsMode;
        }

        public String getVolumeDriver() {
            return volumeDriver;
        }

        public void setVolumeDriver(String volumeDriver) {
            this.volumeDriver = volumeDriver;
        }

        public List<String> getVolumesFrom() {
            return volumesFrom;
        }

        public void setVolumesFrom(List<String> volumesFrom) {
            this.volumesFrom = volumesFrom;
        }
    }

    /**
     * 暴露端口空结构（Docker API 约定值恒为空对象，显式类型化取代 Object；键如 "10010/tcp"）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExposedPort {
    }

    /**
     * 声明挂载点空结构（Volumes 值恒为空对象；实际数据由 HostConfig.Binds/Mounts 提供）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VolumeDeclaration {
    }

    /**
     * 重启策略段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RestartPolicySection {

        /**
         * 策略名：unless-stopped / on-failure / always / no。
         */
        @JsonProperty("Name")
        private String name;

        /**
         * on-failure 的最大重试次数（其他策略为 null）。
         */
        @JsonProperty("MaximumRetryCount")
        private Integer maximumRetryCount;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getMaximumRetryCount() {
            return maximumRetryCount;
        }

        public void setMaximumRetryCount(Integer maximumRetryCount) {
            this.maximumRetryCount = maximumRetryCount;
        }
    }

    /**
     * 单条端口绑定（容器端口 -> 宿主端口）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortBinding {

        /**
         * 宿主端口（字符串形式）。
         */
        @JsonProperty("HostPort")
        private String hostPort;

        /**
         * 宿主 IP（缺省 0.0.0.0）。
         */
        @JsonProperty("HostIp")
        private String hostIp;

        public PortBinding() {
        }

        /**
         * @param hostPort 宿主端口
         */
        public PortBinding(String hostPort) {
            this.hostPort = hostPort;
        }

        public String getHostPort() {
            return hostPort;
        }

        public void setHostPort(String hostPort) {
            this.hostPort = hostPort;
        }

        public String getHostIp() {
            return hostIp;
        }

        public void setHostIp(String hostIp) {
            this.hostIp = hostIp;
        }
    }

    /**
     * 设备映射段（--device，API 的 DeviceMapping）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Device {

        /**
         * 宿主机设备路径（如 /dev/dri）。
         */
        @JsonProperty("PathOnHost")
        private String pathOnHost;

        /**
         * 容器内设备路径。
         */
        @JsonProperty("PathInContainer")
        private String pathInContainer;

        /**
         * cgroup 权限（如 rwm）。
         */
        @JsonProperty("CgroupPermissions")
        private String cgroupPermissions;

        public String getPathOnHost() {
            return pathOnHost;
        }

        public void setPathOnHost(String pathOnHost) {
            this.pathOnHost = pathOnHost;
        }

        public String getPathInContainer() {
            return pathInContainer;
        }

        public void setPathInContainer(String pathInContainer) {
            this.pathInContainer = pathInContainer;
        }

        public String getCgroupPermissions() {
            return cgroupPermissions;
        }

        public void setCgroupPermissions(String cgroupPermissions) {
            this.cgroupPermissions = cgroupPermissions;
        }
    }

    /**
     * ulimit 段（--ulimit）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Ulimit {

        /**
         * 资源名（如 nofile）。
         */
        @JsonProperty("Name")
        private String name;

        /**
         * 软限制。
         */
        @JsonProperty("Soft")
        private Long soft;

        /**
         * 硬限制。
         */
        @JsonProperty("Hard")
        private Long hard;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getSoft() {
            return soft;
        }

        public void setSoft(Long soft) {
            this.soft = soft;
        }

        public Long getHard() {
            return hard;
        }

        public void setHard(Long hard) {
            this.hard = hard;
        }
    }

    /**
     * 日志配置段（--log-driver / --log-opt）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LogConfigSection {

        /**
         * 日志驱动（json-file / local / none 等）。
         */
        @JsonProperty("Type")
        private String type;

        /**
         * 驱动选项（如 max-size / max-file）。
         */
        @JsonProperty("Config")
        private Map<String, String> config;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public void setConfig(Map<String, String> config) {
            this.config = config;
        }
    }

    /**
     * 块 IO 按设备权重项（--blkio-weight-device）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BlkioWeightDeviceItem {

        /**
         * 设备路径。
         */
        @JsonProperty("Path")
        private String path;

        /**
         * 权重（10-1000）。
         */
        @JsonProperty("Weight")
        private Long weight;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Long getWeight() {
            return weight;
        }

        public void setWeight(Long weight) {
            this.weight = weight;
        }
    }

    /**
     * 块设备限速项（--device-read/write-bps / --device-read/write-iops，API 的 ThrottleDevice）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ThrottleDeviceItem {

        /**
         * 设备路径。
         */
        @JsonProperty("Path")
        private String path;

        /**
         * 限速值（bps 或 iops）。
         */
        @JsonProperty("Rate")
        private Long rate;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Long getRate() {
            return rate;
        }

        public void setRate(Long rate) {
            this.rate = rate;
        }
    }

    /**
     * 设备请求段（--gpus 等的结构化形态）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeviceRequestSection {

        /**
         * 设备驱动（如 nvidia）。
         */
        @JsonProperty("Driver")
        private String driver;

        /**
         * 设备数量（-1=全部）。
         */
        @JsonProperty("Count")
        private Long count;

        /**
         * 指定设备 ID 列表（与 Count 二选一）。
         */
        @JsonProperty("DeviceIDs")
        private List<String> deviceIds;

        /**
         * 能力位（如 [["gpu"]]/[["nvidia","compute"]])。
         */
        @JsonProperty("Capabilities")
        private List<List<String>> capabilities;

        /**
         * 驱动选项。
         */
        @JsonProperty("Options")
        private Map<String, String> options;

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = driver;
        }

        public Long getCount() {
            return count;
        }

        public void setCount(Long count) {
            this.count = count;
        }

        public List<String> getDeviceIds() {
            return deviceIds;
        }

        public void setDeviceIds(List<String> deviceIds) {
            this.deviceIds = deviceIds;
        }

        public List<List<String>> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<List<String>> capabilities) {
            this.capabilities = capabilities;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options;
        }
    }

    /**
     * 结构化挂载段（--mount）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MountSection {

        /**
         * 挂载类型：bind / volume / tmpfs / npipe / image。
         */
        @JsonProperty("Type")
        private String type;

        /**
         * 挂载源（bind=宿主路径，volume=卷名）。
         */
        @JsonProperty("Source")
        private String source;

        /**
         * 容器内挂载目标路径。
         */
        @JsonProperty("Target")
        private String target;

        /**
         * 只读挂载。
         */
        @JsonProperty("ReadOnly")
        private Boolean readOnly;

        /**
         * 挂载一致性（Docker Desktop 场景：default/consistent/cached/delegated）。
         */
        @JsonProperty("Consistency")
        private String consistency;

        /**
         * bind 类型选项。
         */
        @JsonProperty("BindOptions")
        private MountBindOptionsSection bindOptions;

        /**
         * volume 类型选项。
         */
        @JsonProperty("VolumeOptions")
        private MountVolumeOptionsSection volumeOptions;

        /**
         * tmpfs 类型选项。
         */
        @JsonProperty("TmpfsOptions")
        private MountTmpfsOptionsSection tmpfsOptions;

        /**
         * image 类型选项。
         */
        @JsonProperty("ImageOptions")
        private MountImageOptionsSection imageOptions;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public Boolean getReadOnly() {
            return readOnly;
        }

        public void setReadOnly(Boolean readOnly) {
            this.readOnly = readOnly;
        }

        public String getConsistency() {
            return consistency;
        }

        public void setConsistency(String consistency) {
            this.consistency = consistency;
        }

        public MountBindOptionsSection getBindOptions() {
            return bindOptions;
        }

        public void setBindOptions(MountBindOptionsSection bindOptions) {
            this.bindOptions = bindOptions;
        }

        public MountVolumeOptionsSection getVolumeOptions() {
            return volumeOptions;
        }

        public void setVolumeOptions(MountVolumeOptionsSection volumeOptions) {
            this.volumeOptions = volumeOptions;
        }

        public MountTmpfsOptionsSection getTmpfsOptions() {
            return tmpfsOptions;
        }

        public void setTmpfsOptions(MountTmpfsOptionsSection tmpfsOptions) {
            this.tmpfsOptions = tmpfsOptions;
        }

        public MountImageOptionsSection getImageOptions() {
            return imageOptions;
        }

        public void setImageOptions(MountImageOptionsSection imageOptions) {
            this.imageOptions = imageOptions;
        }
    }

    /**
     * mount 的 bind 类型选项段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MountBindOptionsSection {

        /**
         * 传播模式：private / rprivate / shared / rshared / slave / rslave。
         */
        @JsonProperty("Propagation")
        private String propagation;

        /**
         * 非递归挂载（只挂源路径自身）。
         */
        @JsonProperty("NonRecursive")
        private Boolean nonRecursive;

        /**
         * 源不存在时自动创建挂载点。
         */
        @JsonProperty("CreateMountpoint")
        private Boolean createMountpoint;

        /**
         * 只读非递归挂载。
         */
        @JsonProperty("ReadOnlyNonRecursive")
        private Boolean readOnlyNonRecursive;

        /**
         * 只读强制递归挂载。
         */
        @JsonProperty("ReadOnlyForceRecursive")
        private Boolean readOnlyForceRecursive;

        public String getPropagation() {
            return propagation;
        }

        public void setPropagation(String propagation) {
            this.propagation = propagation;
        }

        public Boolean getNonRecursive() {
            return nonRecursive;
        }

        public void setNonRecursive(Boolean nonRecursive) {
            this.nonRecursive = nonRecursive;
        }

        public Boolean getCreateMountpoint() {
            return createMountpoint;
        }

        public void setCreateMountpoint(Boolean createMountpoint) {
            this.createMountpoint = createMountpoint;
        }

        public Boolean getReadOnlyNonRecursive() {
            return readOnlyNonRecursive;
        }

        public void setReadOnlyNonRecursive(Boolean readOnlyNonRecursive) {
            this.readOnlyNonRecursive = readOnlyNonRecursive;
        }

        public Boolean getReadOnlyForceRecursive() {
            return readOnlyForceRecursive;
        }

        public void setReadOnlyForceRecursive(Boolean readOnlyForceRecursive) {
            this.readOnlyForceRecursive = readOnlyForceRecursive;
        }
    }

    /**
     * mount 的 volume 类型选项段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MountVolumeOptionsSection {

        /**
         * 禁止从卷向挂载点复制数据。
         */
        @JsonProperty("NoCopy")
        private Boolean noCopy;

        /**
         * 卷 labels。
         */
        @JsonProperty("Labels")
        private Map<String, String> labels;

        /**
         * 卷驱动配置。
         */
        @JsonProperty("DriverConfig")
        private VolumeDriverConfigSection driverConfig;

        /**
         * 卷内子路径。
         */
        @JsonProperty("Subpath")
        private String subpath;

        public Boolean getNoCopy() {
            return noCopy;
        }

        public void setNoCopy(Boolean noCopy) {
            this.noCopy = noCopy;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }

        public VolumeDriverConfigSection getDriverConfig() {
            return driverConfig;
        }

        public void setDriverConfig(VolumeDriverConfigSection driverConfig) {
            this.driverConfig = driverConfig;
        }

        public String getSubpath() {
            return subpath;
        }

        public void setSubpath(String subpath) {
            this.subpath = subpath;
        }
    }

    /**
     * 卷驱动配置段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VolumeDriverConfigSection {

        /**
         * 卷驱动名。
         */
        @JsonProperty("Name")
        private String name;

        /**
         * 驱动选项。
         */
        @JsonProperty("Options")
        private Map<String, String> options;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options;
        }
    }

    /**
     * mount 的 tmpfs 类型选项段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MountTmpfsOptionsSection {

        /**
         * tmpfs 大小（字节）。
         */
        @JsonProperty("SizeBytes")
        private Long sizeBytes;

        /**
         * 权限模式（八进制值的十进制表示，如 755 -> 493）。
         */
        @JsonProperty("Mode")
        private Long mode;

        /**
         * 挂载选项（如 [["noexec"]]，每项 1-2 长度）。
         */
        @JsonProperty("Options")
        private List<List<String>> options;

        public Long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public Long getMode() {
            return mode;
        }

        public void setMode(Long mode) {
            this.mode = mode;
        }

        public List<List<String>> getOptions() {
            return options;
        }

        public void setOptions(List<List<String>> options) {
            this.options = options;
        }
    }

    /**
     * mount 的 image 类型选项段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MountImageOptionsSection {

        /**
         * 子路径。
         */
        @JsonProperty("Subpath")
        private String subpath;

        public String getSubpath() {
            return subpath;
        }

        public void setSubpath(String subpath) {
            this.subpath = subpath;
        }
    }

    /**
     * Docker 原生健康检查段（--health-*；ops 就绪探测在 Agent 侧 statusScript，本段供需要时使用）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HealthSection {

        /**
         * 探测命令（["CMD", ...] / ["CMD-SHELL", ...]，NONE 表示禁用）。
         */
        @JsonProperty("Test")
        private List<String> test;

        /**
         * 探测间隔（纳秒）。
         */
        @JsonProperty("Interval")
        private Long interval;

        /**
         * 单次超时（纳秒）。
         */
        @JsonProperty("Timeout")
        private Long timeout;

        /**
         * 判定失败的连续次数。
         */
        @JsonProperty("Retries")
        private Long retries;

        /**
         * 启动宽限期（纳秒，期内失败不计入）。
         */
        @JsonProperty("StartPeriod")
        private Long startPeriod;

        /**
         * 启动探测间隔（纳秒，容器启动初期使用）。
         */
        @JsonProperty("StartInterval")
        private Long startInterval;

        public List<String> getTest() {
            return test;
        }

        public void setTest(List<String> test) {
            this.test = test;
        }

        public Long getInterval() {
            return interval;
        }

        public void setInterval(Long interval) {
            this.interval = interval;
        }

        public Long getTimeout() {
            return timeout;
        }

        public void setTimeout(Long timeout) {
            this.timeout = timeout;
        }

        public Long getRetries() {
            return retries;
        }

        public void setRetries(Long retries) {
            this.retries = retries;
        }

        public Long getStartPeriod() {
            return startPeriod;
        }

        public void setStartPeriod(Long startPeriod) {
            this.startPeriod = startPeriod;
        }

        public Long getStartInterval() {
            return startInterval;
        }

        public void setStartInterval(Long startInterval) {
            this.startInterval = startInterval;
        }
    }

    /**
     * 网络配置段（--network 指定网络时的端点设置）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NetworkingConfigSection {

        /**
         * 网络名 -> 端点设置（别名/固定 IP/DNS 名等）。
         */
        @JsonProperty("EndpointsConfig")
        private Map<String, EndpointSettingsSection> endpointsConfig;

        public Map<String, EndpointSettingsSection> getEndpointsConfig() {
            return endpointsConfig;
        }

        public void setEndpointsConfig(Map<String, EndpointSettingsSection> endpointsConfig) {
            this.endpointsConfig = endpointsConfig;
        }
    }

    /**
     * 网络端点设置段（API 的 EndpointSettings）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EndpointSettingsSection {

        /**
         * IPAM 配置（固定 IP 场景）。
         */
        @JsonProperty("IPAMConfig")
        private EndpointIpamConfigSection ipamConfig;

        /**
         * 端点 links。
         */
        @JsonProperty("Links")
        private List<String> links;

        /**
         * 网络别名（--network-alias）。
         */
        @JsonProperty("Aliases")
        private List<String> aliases;

        /**
         * MAC 地址。
         */
        @JsonProperty("MacAddress")
        private String macAddress;

        /**
         * 驱动选项。
         */
        @JsonProperty("DriverOpts")
        private Map<String, String> driverOpts;

        /**
         * 网络 ID（响应字段，创建时可空）。
         */
        @JsonProperty("NetworkID")
        private String networkId;

        /**
         * 端点 ID（响应字段）。
         */
        @JsonProperty("EndpointID")
        private String endpointId;

        /**
         * 网关（响应字段）。
         */
        @JsonProperty("Gateway")
        private String gateway;

        /**
         * IPv4 地址（响应字段）。
         */
        @JsonProperty("IPAddress")
        private String ipAddress;

        /**
         * IPv4 掩码长度（响应字段）。
         */
        @JsonProperty("IPPrefixLen")
        private Long ipPrefixLen;

        /**
         * IPv6 网关（响应字段）。
         */
        @JsonProperty("IPv6Gateway")
        private String ipv6Gateway;

        /**
         * 全局 IPv6 地址（响应字段）。
         */
        @JsonProperty("GlobalIPv6Address")
        private String globalIpv6Address;

        /**
         * 全局 IPv6 掩码长度（响应字段）。
         */
        @JsonProperty("GlobalIPv6PrefixLen")
        private Long globalIpv6PrefixLen;

        /**
         * DNS 名（响应字段）。
         */
        @JsonProperty("DNSNames")
        private List<String> dnsNames;

        public EndpointIpamConfigSection getIpamConfig() {
            return ipamConfig;
        }

        public void setIpamConfig(EndpointIpamConfigSection ipamConfig) {
            this.ipamConfig = ipamConfig;
        }

        public List<String> getLinks() {
            return links;
        }

        public void setLinks(List<String> links) {
            this.links = links;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public void setAliases(List<String> aliases) {
            this.aliases = aliases;
        }

        public String getMacAddress() {
            return macAddress;
        }

        public void setMacAddress(String macAddress) {
            this.macAddress = macAddress;
        }

        public Map<String, String> getDriverOpts() {
            return driverOpts;
        }

        public void setDriverOpts(Map<String, String> driverOpts) {
            this.driverOpts = driverOpts;
        }

        public String getNetworkId() {
            return networkId;
        }

        public void setNetworkId(String networkId) {
            this.networkId = networkId;
        }

        public String getEndpointId() {
            return endpointId;
        }

        public void setEndpointId(String endpointId) {
            this.endpointId = endpointId;
        }

        public String getGateway() {
            return gateway;
        }

        public void setGateway(String gateway) {
            this.gateway = gateway;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public Long getIpPrefixLen() {
            return ipPrefixLen;
        }

        public void setIpPrefixLen(Long ipPrefixLen) {
            this.ipPrefixLen = ipPrefixLen;
        }

        public String getIpv6Gateway() {
            return ipv6Gateway;
        }

        public void setIpv6Gateway(String ipv6Gateway) {
            this.ipv6Gateway = ipv6Gateway;
        }

        public String getGlobalIpv6Address() {
            return globalIpv6Address;
        }

        public void setGlobalIpv6Address(String globalIpv6Address) {
            this.globalIpv6Address = globalIpv6Address;
        }

        public Long getGlobalIpv6PrefixLen() {
            return globalIpv6PrefixLen;
        }

        public void setGlobalIpv6PrefixLen(Long globalIpv6PrefixLen) {
            this.globalIpv6PrefixLen = globalIpv6PrefixLen;
        }

        public List<String> getDnsNames() {
            return dnsNames;
        }

        public void setDnsNames(List<String> dnsNames) {
            this.dnsNames = dnsNames;
        }
    }

    /**
     * 端点 IPAM 配置段（固定 IP）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EndpointIpamConfigSection {

        /**
         * IPv4 地址（如 172.20.0.5）。
         */
        @JsonProperty("IPv4Address")
        private String ipv4Address;

        /**
         * IPv6 地址。
         */
        @JsonProperty("IPv6Address")
        private String ipv6Address;

        public String getIpv4Address() {
            return ipv4Address;
        }

        public void setIpv4Address(String ipv4Address) {
            this.ipv4Address = ipv4Address;
        }

        public String getIpv6Address() {
            return ipv6Address;
        }

        public void setIpv6Address(String ipv6Address) {
            this.ipv6Address = ipv6Address;
        }
    }
}
