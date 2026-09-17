package uw.ops.agent.helper.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Docker 事件（GET /events 窗口查询响应的单条事件，NDJSON 逐行）。
 * <p>
 * 完整映射 moby 的 events.Message / Actor 结构体：无 json tag 的字段原样输出 PascalCase
 * （Type/Action/Actor/Actor.ID），有 tag 的为小写（status/id/from/scope/time/timeNano）。
 * 全部字段一律显式 {@code @JsonProperty}（即便与 Java 驼峰恰好同名也标注，杜绝"疑似缺注解"的误判）。
 * status/id/from 是 JSONMessage 兼容字段（与 Action/Actor.ID/Attributes.image 同值），新代码请用后者。
 * <p>
 * Agent 当前只消费：Action、Actor.ID 与 Actor.Attributes.exitCode（在 HostStatsCollector 投影为
 * 上报模型 ContainerEvent 后批量上报，本类不直接透传协议）；其余字段全量映射备用，防止后续消费
 * 新字段时再踩字段名映射的坑。
 */
public class ContainerEventRaw {

    /**
     * 兼容字段：动作名（与 Action 同值）。
     */
    @JsonProperty("status")
    private String status;

    /**
     * 兼容字段：事件主体 Id（与 Actor.ID 同值）。
     */
    @JsonProperty("id")
    private String id;

    /**
     * 兼容字段：主体来源镜像（与 Actor.Attributes.image 同值）。
     */
    @JsonProperty("from")
    private String from;

    /**
     * 事件对象类型（container/image/network/volume/plugin/daemon/builder，请求已按 container 过滤）。
     */
    @JsonProperty("Type")
    private String type;

    /**
     * 动作（container：create/start/die/stop/destroy/restart/oom/pause/health_status/update...）。
     */
    @JsonProperty("Action")
    private String action;

    /**
     * 事件主体。
     */
    @JsonProperty("Actor")
    private ActorSection actor;

    /**
     * 事件范围（local/swarm）。
     */
    @JsonProperty("scope")
    private String scope;

    /**
     * 事件秒级时间戳（timeNano 的秒截断，排序去重请用 timeNano）。
     */
    @JsonProperty("time")
    private long time;

    /**
     * 事件纳秒时间戳（窗口衔接去重的排序键）。
     */
    @JsonProperty("timeNano")
    private long timeNano;

    /**
     * 事件主体（容器）。
     */
    public static class ActorSection {

        /**
         * 容器完整 Id。
         */
        @JsonProperty("ID")
        private String id;

        /**
         * 主体属性（container 事件：name/image/container_label 等）。
         */
        @JsonProperty("Attributes")
        private Map<String, String> attributes;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }
    }

    /**
     * 容器完整 Id（Actor 缺失时 null）。
     *
     * @return 容器 Id
     */
    public String getActorId() {
        return actor != null ? actor.id : null;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public ActorSection getActor() {
        return actor;
    }

    public void setActor(ActorSection actor) {
        this.actor = actor;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public long getTimeNano() {
        return timeNano;
    }

    public void setTimeNano(long timeNano) {
        this.timeNano = timeNano;
    }
}
