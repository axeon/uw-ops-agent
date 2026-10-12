package uw.ops.agent.vo;

/**
 * 探测脚本条目落盘模型（单容器一个 JSON 文件，文件名 {dockerId}.json）。
 * <p>
 * {@link uw.ops.agent.helper.ReadinessProber} 探测集合的持久化形态：结构化 START 成功时随
 * cacheStartScript 落盘，Agent 自升级/重启后经 {@link uw.ops.agent.helper.ReadinessProber#restoreFromDisk()}
 * 重建集合（不落盘则受管容器永久失去探测）。位于 vo 包以纳入 Native Image 自动反射注册
 * （AgentReflectionFeature 按包递归扫描，随 --verify-native-metadata 验证绑定路径）。
 */
public class ProbeScriptEntry {

    /**
     * Docker 容器 ID，同时作为持久化文件名。
     */
    private String dockerId;

    /**
     * Center 容器台账 ID。
     */
    private long containerId;

    /**
     * 受信 START 任务下发的探测脚本。
     */
    private String script;

    /**
     * Jackson 反序列化用无参构造器。
     */
    public ProbeScriptEntry() {
    }

    /**
     * 全参构造器（落盘序列化用）。
     *
     * @param dockerId    Docker 容器 ID（持久化文件名）
     * @param containerId Center 容器台账 ID
     * @param script      探测脚本
     */
    public ProbeScriptEntry(String dockerId, long containerId, String script) {
        this.dockerId = dockerId;
        this.containerId = containerId;
        this.script = script;
    }

    public String getDockerId() {
        return dockerId;
    }

    public void setDockerId(String dockerId) {
        this.dockerId = dockerId;
    }

    public long getContainerId() {
        return containerId;
    }

    public void setContainerId(long containerId) {
        this.containerId = containerId;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }
}
