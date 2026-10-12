package uw.ops.agent.vo;

import java.util.List;
import java.util.Map;

/**
 * 探针配置终值形态（与 center 侧 uw.ops.center.vo.ProbeSpec 同构的协议契约）。
 * <p>
 * 两种到达路径：tcp/http（无 secret）经容器 label {@code uw.ops.probe}（create 携带、
 * inventory 收敛，Docker 元数据即持久化真相源，agent 重启后零恢复动作）；exec/含 secret 经
 * START taskData（docker.probeConfig）下发并落盘。tcp=http=JVM 原生探测（零进程 spawn、
 * 毫秒级），exec=/bin/sh 进程（默认 3s 超时）。
 */
public class ProbeSpec {

    /**
     * 探针类型：tcp | http | exec。
     */
    private String type;

    /**
     * tcp/http 目标主机（默认 127.0.0.1）。
     */
    private String host;

    /**
     * tcp 目标端口。
     */
    private Integer port;

    /**
     * http 完整 URL。
     */
    private String url;

    /**
     * http 期望状态码列表（默认 200）。
     */
    private List<Integer> expect;

    /**
     * http 自定义请求头。
     */
    private Map<String, String> headers;

    /**
     * exec 脚本。
     */
    private String script;

    /**
     * 单次探测超时毫秒（缺省按类型取默认：tcp=2000 / http=2000 / exec=3000）。
     */
    private Integer timeoutMs;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<Integer> getExpect() {
        return expect;
    }

    public void setExpect(List<Integer> expect) {
        this.expect = expect;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
