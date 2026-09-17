package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Docker Engine API GET /containers/json 响应条目映射（inventory 输入）。
 * <p>
 * 字段名与协议 vo {@link uw.ops.agent.vo.sub.ContainerInfo} 不同（本类映射 API 原始 PascalCase/snake 字段，
 * ContainerInfo 是 Agent 上报协议的 camelCase 字段，二者不可混用注解，由 DockerInfoHelper 完成转换）。
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

    public HostConfigSection getHostConfig() {
        return hostConfig;
    }

    public void setHostConfig(HostConfigSection hostConfig) {
        this.hostConfig = hostConfig;
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
