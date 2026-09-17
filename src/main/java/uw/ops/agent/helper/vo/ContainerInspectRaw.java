package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Docker Engine API GET /containers/{id}/json（inspect）响应映射。
 * <p>
 * 映射范围（对照 moby Engine API v1.56 swagger）：ContainerState 全字段（Health 仅取 Status/FailingStreak，
 * 检查日志 Log 排除）；顶层除以下 6 项外全映射——SizeRw/SizeRootFs（需 ?size=1 才返回）、
 * ExecIDs/Storage/GraphDriver（daemon 内部状态，无消费）、ImageManifestDescriptor（OCI 描述符，registry 域）。
 * 消费方：采集（RestartCount/OOMKilled 递增检测、配额核对）与结构化任务
 * （幂等判定：目标容器是否存在、是否 running、镜像与 uw.ops.* labels 是否一致）。
 */
public class ContainerInspectRaw {

    /**
     * 容器完整 64 位 Id。
     */
    @JsonProperty("Id")
    private String id;

    /**
     * 创建时间（ISO8601）。
     */
    @JsonProperty("Created")
    private String created;

    /**
     * 入口路径（如 /entrypoint.sh）。
     */
    @JsonProperty("Path")
    private String path;

    /**
     * 入口参数。
     */
    @JsonProperty("Args")
    private List<String> args;

    /**
     * 容器名（带前导斜杠，如 /uw-ops-123）。
     */
    @JsonProperty("Name")
    private String name;

    /**
     * restart policy 自动拉起次数（不含发布/手动重启）。
     */
    @JsonProperty("RestartCount")
    private Long restartCount;

    /**
     * 镜像引用（daemon 侧归一结果，与 taskData.image 比对）。
     */
    @JsonProperty("Image")
    private String image;

    /**
     * 平台（如 linux/amd64）。
     */
    @JsonProperty("Platform")
    private String platform;

    /**
     * AppArmor 安全配置名。
     */
    @JsonProperty("AppArmorProfile")
    private String appArmorProfile;

    /**
     * SELinux 进程标签。
     */
    @JsonProperty("ProcessLabel")
    private String processLabel;

    /**
     * SELinux 挂载标签。
     */
    @JsonProperty("MountLabel")
    private String mountLabel;

    /**
     * 存储驱动名。
     */
    @JsonProperty("Driver")
    private String driver;

    /**
     * resolv.conf 宿主侧路径。
     */
    @JsonProperty("ResolvConfPath")
    private String resolvConfPath;

    /**
     * /etc/hosts 宿主侧路径。
     */
    @JsonProperty("HostnamePath")
    private String hostnamePath;

    /**
     * /etc/hosts 宿主侧路径（与 HostnamePath 同源的遗留字段）。
     */
    @JsonProperty("HostsPath")
    private String hostsPath;

    /**
     * 容器日志文件宿主侧路径。
     */
    @JsonProperty("LogPath")
    private String logPath;

    /**
     * 容器当前状态（API 的 ContainerState）。
     */
    @JsonProperty("State")
    private StateSection state;

    /**
     * 挂载点列表（API 的 MountPoint）。
     */
    @JsonProperty("Mounts")
    private List<MountPointSection> mounts;

    /**
     * 网络设置（网络名 -> 端点，端点结构复用 CreateContainerParam.EndpointSettingsSection）。
     */
    @JsonProperty("NetworkSettings")
    private NetworkSettingsSection networkSettings;

    /**
     * 容器配置段（镜像与 labels）。
     */
    @JsonProperty("Config")
    private ConfigSection config;

    /**
     * 宿主机侧配置（配额）。
     */
    @JsonProperty("HostConfig")
    private HostConfigSection hostConfig;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getRestartCount() {
        return restartCount;
    }

    public void setRestartCount(Long restartCount) {
        this.restartCount = restartCount;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getAppArmorProfile() {
        return appArmorProfile;
    }

    public void setAppArmorProfile(String appArmorProfile) {
        this.appArmorProfile = appArmorProfile;
    }

    public String getProcessLabel() {
        return processLabel;
    }

    public void setProcessLabel(String processLabel) {
        this.processLabel = processLabel;
    }

    public String getMountLabel() {
        return mountLabel;
    }

    public void setMountLabel(String mountLabel) {
        this.mountLabel = mountLabel;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getResolvConfPath() {
        return resolvConfPath;
    }

    public void setResolvConfPath(String resolvConfPath) {
        this.resolvConfPath = resolvConfPath;
    }

    public String getHostnamePath() {
        return hostnamePath;
    }

    public void setHostnamePath(String hostnamePath) {
        this.hostnamePath = hostnamePath;
    }

    public String getHostsPath() {
        return hostsPath;
    }

    public void setHostsPath(String hostsPath) {
        this.hostsPath = hostsPath;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public StateSection getState() {
        return state;
    }

    public void setState(StateSection state) {
        this.state = state;
    }

    public List<MountPointSection> getMounts() {
        return mounts;
    }

    public void setMounts(List<MountPointSection> mounts) {
        this.mounts = mounts;
    }

    public NetworkSettingsSection getNetworkSettings() {
        return networkSettings;
    }

    public void setNetworkSettings(NetworkSettingsSection networkSettings) {
        this.networkSettings = networkSettings;
    }

    public ConfigSection getConfig() {
        return config;
    }

    public void setConfig(ConfigSection config) {
        this.config = config;
    }

    public HostConfigSection getHostConfig() {
        return hostConfig;
    }

    public void setHostConfig(HostConfigSection hostConfig) {
        this.hostConfig = hostConfig;
    }

    /**
     * 网络设置段（inspect 响应的 NetworkSettings）。
     */
    public static class NetworkSettingsSection {

        /**
         * 网络名 -> 端点设置。
         */
        @JsonProperty("Networks")
        private Map<String, CreateContainerParam.EndpointSettingsSection> networks;

        public Map<String, CreateContainerParam.EndpointSettingsSection> getNetworks() {
            return networks;
        }

        public void setNetworks(Map<String, CreateContainerParam.EndpointSettingsSection> networks) {
            this.networks = networks;
        }
    }

    /**
     * 容器状态段（API 的 ContainerState 全字段，Health 的检查日志嵌套除外）。
     */
    public static class StateSection {

        /**
         * 运行状态：running/created/exited/paused/restarting/dead。
         */
        @JsonProperty("Status")
        private String status;

        /**
         * 是否运行中。
         */
        @JsonProperty("Running")
        private Boolean running;

        /**
         * 是否暂停。
         */
        @JsonProperty("Paused")
        private Boolean paused;

        /**
         * 是否重启中。
         */
        @JsonProperty("Restarting")
        private Boolean restarting;

        /**
         * 当前实例是否因 OOM 被杀。
         */
        @JsonProperty("OOMKilled")
        private Boolean oomKilled;

        /**
         * 是否死锁态（daemon 无法停止）。
         */
        @JsonProperty("Dead")
        private Boolean dead;

        /**
         * 主进程 PID（未运行为 0）。
         */
        @JsonProperty("Pid")
        private Long pid;

        /**
         * 最近一次退出码。
         */
        @JsonProperty("ExitCode")
        private Long exitCode;

        /**
         * 最近一次错误信息。
         */
        @JsonProperty("Error")
        private String error;

        /**
         * 最近一次启动时间（ISO8601；0001-01-01T00:00:00Z 表示未启动过）。
         */
        @JsonProperty("StartedAt")
        private String startedAt;

        /**
         * 最近一次退出时间（ISO8601；0001-01-01T00:00:00Z 表示未退出过）。
         */
        @JsonProperty("FinishedAt")
        private String finishedAt;

        /**
         * Docker 原生健康检查状态（无健康检查时为 null；检查日志 Log 不映射）。
         */
        @JsonProperty("Health")
        private HealthSection health;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Boolean getRunning() {
            return running;
        }

        public void setRunning(Boolean running) {
            this.running = running;
        }

        public Boolean getPaused() {
            return paused;
        }

        public void setPaused(Boolean paused) {
            this.paused = paused;
        }

        public Boolean getRestarting() {
            return restarting;
        }

        public void setRestarting(Boolean restarting) {
            this.restarting = restarting;
        }

        public Boolean getOomKilled() {
            return oomKilled;
        }

        public void setOomKilled(Boolean oomKilled) {
            this.oomKilled = oomKilled;
        }

        public Boolean getDead() {
            return dead;
        }

        public void setDead(Boolean dead) {
            this.dead = dead;
        }

        public Long getPid() {
            return pid;
        }

        public void setPid(Long pid) {
            this.pid = pid;
        }

        public Long getExitCode() {
            return exitCode;
        }

        public void setExitCode(Long exitCode) {
            this.exitCode = exitCode;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(String startedAt) {
            this.startedAt = startedAt;
        }

        public String getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(String finishedAt) {
            this.finishedAt = finishedAt;
        }

        public HealthSection getHealth() {
            return health;
        }

        public void setHealth(HealthSection health) {
            this.health = health;
        }
    }

    /**
     * Docker 原生健康检查状态段（检查日志 Log 不映射）。
     */
    public static class HealthSection {

        /**
         * 健康状态：starting/healthy/unhealthy/none。
         */
        @JsonProperty("Status")
        private String status;

        /**
         * 连续失败次数（unhealthy 时）。
         */
        @JsonProperty("FailingStreak")
        private Long failingStreak;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getFailingStreak() {
            return failingStreak;
        }

        public void setFailingStreak(Long failingStreak) {
            this.failingStreak = failingStreak;
        }
    }

    /**
     * 挂载点段（API 的 MountPoint）。
     */
    public static class MountPointSection {

        /**
         * 挂载类型：bind/volume/tmpfs。
         */
        @JsonProperty("Type")
        private String type;

        /**
         * 卷名（volume 类型）。
         */
        @JsonProperty("Name")
        private String name;

        /**
         * 宿主侧源路径。
         */
        @JsonProperty("Source")
        private String source;

        /**
         * 容器内目标路径。
         */
        @JsonProperty("Destination")
        private String destination;

        /**
         * 卷驱动（volume 类型）。
         */
        @JsonProperty("Driver")
        private String driver;

        /**
         * 挂载模式（ro/rw 等）。
         */
        @JsonProperty("Mode")
        private String mode;

        /**
         * 是否读写。
         */
        @JsonProperty("RW")
        private Boolean rw;

        /**
         * 传播模式。
         */
        @JsonProperty("Propagation")
        private String propagation;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = driver;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Boolean getRw() {
            return rw;
        }

        public void setRw(Boolean rw) {
            this.rw = rw;
        }

        public String getPropagation() {
            return propagation;
        }

        public void setPropagation(String propagation) {
            this.propagation = propagation;
        }
    }

    /**
     * 容器配置段（幂等判定的镜像与 labels 比对源）。
     */
    public static class ConfigSection {

        /**
         * 镜像引用（与 taskData.image 比对，digest/tag 形态以 daemon 归一结果为准）。
         */
        @JsonProperty("Image")
        private String image;

        /**
         * 容器 labels（uw.ops.container-id / uw.ops.plan-id）。
         */
        @JsonProperty("Labels")
        private Map<String, String> labels;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }
    }

    /**
     * 宿主机配置段。
     */
    public static class HostConfigSection {

        /**
         * CPU 配额（纳秒核，0=未设）。
         */
        @JsonProperty("NanoCpus")
        private Long nanoCpus;

        /**
         * 内存配额（字节，0=未设）。
         */
        @JsonProperty("Memory")
        private Long memory;

        public Long getNanoCpus() {
            return nanoCpus;
        }

        public void setNanoCpus(Long nanoCpus) {
            this.nanoCpus = nanoCpus;
        }

        public Long getMemory() {
            return memory;
        }

        public void setMemory(Long memory) {
            this.memory = memory;
        }
    }
}
