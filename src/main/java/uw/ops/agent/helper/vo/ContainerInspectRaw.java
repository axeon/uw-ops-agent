package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Docker Engine API GET /containers/{id}/json（inspect）响应映射。
 * <p>
 * 仅声明采集所需字段：RestartCount/State.OOMKilled 递增检测、HostConfig 配额核对。
 */
public class ContainerInspectRaw {

    /**
     * restart policy 自动拉起次数（不含发布/手动重启）。
     */
    @JsonProperty("RestartCount")
    private Long restartCount;

    /**
     * 容器当前状态。
     */
    @JsonProperty("State")
    private StateSection state;

    /**
     * 宿主机侧配置（配额）。
     */
    @JsonProperty("HostConfig")
    private HostConfigSection hostConfig;

    public Long getRestartCount() {
        return restartCount;
    }

    public void setRestartCount(Long restartCount) {
        this.restartCount = restartCount;
    }

    public StateSection getState() {
        return state;
    }

    public void setState(StateSection state) {
        this.state = state;
    }

    public HostConfigSection getHostConfig() {
        return hostConfig;
    }

    public void setHostConfig(HostConfigSection hostConfig) {
        this.hostConfig = hostConfig;
    }

    /**
     * 容器状态段。
     */
    public static class StateSection {

        /**
         * 当前实例是否因 OOM 被杀。
         */
        @JsonProperty("OOMKilled")
        private Boolean oomKilled;

        public Boolean getOomKilled() {
            return oomKilled;
        }

        public void setOomKilled(Boolean oomKilled) {
            this.oomKilled = oomKilled;
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
