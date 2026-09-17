package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Docker Engine API GET /containers/json 响应条目映射（inventory 输入）。
 * <p>
 * 字段名与协议 vo {@link uw.ops.agent.vo.sub.ContainerInfo} 不同（本类映射 API 原始 PascalCase/snake 字段，
 * ContainerInfo 是 Agent 上报协议的 camelCase 字段，二者不可混用注解，由 DockerInfoHelper 完成转换）。
 * 映射范围对照 moby v1.56 swagger：除 SizeRw/SizeRootFs（需 ?size=1）、NetworkSettings、
 * ImageManifestDescriptor（OCI 描述符）外全字段。
 */
public class ContainerSummaryRaw {

    /**
     * 容器完整 64 位 Id。
     */
    @JsonProperty("Id")
    private String id;

    /**
     * 容器名列表（首个为主名，带前导斜杠）。
     */
    @JsonProperty("Names")
    private List<String> names;

    /**
     * 镜像。
     */
    @JsonProperty("Image")
    private String image;

    /**
     * 镜像完整 ID（sha256:...，inventory 精确镜像身份）。
     */
    @JsonProperty("ImageID")
    private String imageId;

    /**
     * 启动命令。
     */
    @JsonProperty("Command")
    private String command;

    /**
     * 创建时间（unix 秒）。
     */
    @JsonProperty("Created")
    private Long created;

    /**
     * 运行状态：running/created/exited...
     */
    @JsonProperty("State")
    private String state;

    /**
     * 人类可读状态：Up 2 hours。
     */
    @JsonProperty("Status")
    private String status;

    /**
     * 容器 labels（受管容器识别：uw.ops.container-id / uw.ops.plan-id；手工容器无该标记）。
     */
    @JsonProperty("Labels")
    private Map<String, String> labels;

    /**
     * 端口映射列表（API 的 PortSummary：容器端口与已发布宿主端口，actualPorts 核对数据源）。
     */
    @JsonProperty("Ports")
    private List<PortSummarySection> ports;

    /**
     * 挂载点列表（API 的 MountPoint，结构同 inspect 的 Mounts）。
     */
    @JsonProperty("Mounts")
    private List<ContainerInspectRaw.MountPointSection> mounts;

    /**
     * Docker 原生健康检查状态（无健康检查为 null，结构同 inspect 的 State.Health）。
     */
    @JsonProperty("Health")
    private ContainerInspectRaw.HealthSection health;

    /**
     * 宿主机配置（网络模式）。
     */
    @JsonProperty("HostConfig")
    private HostConfigSection hostConfig;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getImageId() {
        return imageId;
    }

    public void setImageId(String imageId) {
        this.imageId = imageId;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public List<PortSummarySection> getPorts() {
        return ports;
    }

    public void setPorts(List<PortSummarySection> ports) {
        this.ports = ports;
    }

    public List<ContainerInspectRaw.MountPointSection> getMounts() {
        return mounts;
    }

    public void setMounts(List<ContainerInspectRaw.MountPointSection> mounts) {
        this.mounts = mounts;
    }

    public ContainerInspectRaw.HealthSection getHealth() {
        return health;
    }

    public void setHealth(ContainerInspectRaw.HealthSection health) {
        this.health = health;
    }

    public HostConfigSection getHostConfig() {
        return hostConfig;
    }

    public void setHostConfig(HostConfigSection hostConfig) {
        this.hostConfig = hostConfig;
    }

    /**
     * 端口映射段（API 的 PortSummary）。
     */
    public static class PortSummarySection {

        /**
         * 宿主 IP（未发布端口为 null）。
         */
        @JsonProperty("IP")
        private String ip;

        /**
         * 容器端口（私有端口）。
         */
        @JsonProperty("PrivatePort")
        private Long privatePort;

        /**
         * 宿主端口（未发布为 null）。
         */
        @JsonProperty("PublicPort")
        private Long publicPort;

        /**
         * 协议：tcp/udp/sctp。
         */
        @JsonProperty("Type")
        private String type;

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public Long getPrivatePort() {
            return privatePort;
        }

        public void setPrivatePort(Long privatePort) {
            this.privatePort = privatePort;
        }

        public Long getPublicPort() {
            return publicPort;
        }

        public void setPublicPort(Long publicPort) {
            this.publicPort = publicPort;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * 宿主机配置段（网络模式）。
     */
    public static class HostConfigSection {

        /**
         * 网络模式：bridge/host/none/container:xxx。
         */
        @JsonProperty("NetworkMode")
        private String networkMode;

        public String getNetworkMode() {
            return networkMode;
        }

        public void setNetworkMode(String networkMode) {
            this.networkMode = networkMode;
        }
    }
}
