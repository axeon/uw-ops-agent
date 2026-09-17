package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Docker Engine API POST /containers/create 响应映射（daemon 原始 vo）。
 * <p>
 * {@code Id} 即 Docker 返回的容器 ID——结构化任务的 containerHash 台账唯一来源，
 * 取代旧 shell 通道从 taskResult 末行解析的约定。
 */
public class CreateContainerRaw {

    /**
     * 容器完整 64 位 Id。
     */
    @JsonProperty("Id")
    private String id;

    /**
     * daemon 创建告警（可空）。
     */
    @JsonProperty("Warnings")
    private List<String> warnings;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
