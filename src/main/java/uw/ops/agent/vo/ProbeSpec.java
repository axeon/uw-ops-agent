package uw.ops.agent.vo;

import java.util.List;
import java.util.Map;

/**
 * 探针配置终值形态（与 center 侧 uw.ops.center.vo.ProbeSpec 同构的协议契约）：
 * tcpProbes/httpProbes/cmdProbes 三组探测并存，每组为对象数组（同类多条、跨类同时生效，
 * 全部通过才算就绪）。
 * <p>
 * 两种到达路径：仅 tcp/http（无 secret）经容器 label {@code uw.ops.probe}（create 携带、
 * inventory 收敛，Docker 元数据即持久化真相源，agent 重启后零恢复动作）；含 cmd（或含
 * secret 占位符的 tcp/http）经 START taskData（docker.probeConfig）下发并落盘。
 * tcp=http=JVM 原生探测（零进程 spawn、毫秒级），cmd=/bin/sh 进程（默认 3s 超时）。
 */
public class ProbeSpec {

    /**
     * tcp 探测组（可多条；全部通过才算就绪）。
     */
    private List<TcpProbe> tcpProbes;

    /**
     * http 探测组（可多条；全部通过才算就绪）。
     */
    private List<HttpProbe> httpProbes;

    /**
     * cmd 探测组（可多条；/bin/sh 脚本，默认 3s 超时）。
     */
    private List<CmdProbe> cmdProbes;

    public List<TcpProbe> getTcpProbes() {
        return tcpProbes;
    }

    public void setTcpProbes(List<TcpProbe> tcpProbes) {
        this.tcpProbes = tcpProbes;
    }

    public List<HttpProbe> getHttpProbes() {
        return httpProbes;
    }

    public void setHttpProbes(List<HttpProbe> httpProbes) {
        this.httpProbes = httpProbes;
    }

    public List<CmdProbe> getCmdProbes() {
        return cmdProbes;
    }

    public void setCmdProbes(List<CmdProbe> cmdProbes) {
        this.cmdProbes = cmdProbes;
    }

    /**
     * 探测总数（三组合计；空组安全）。
     */
    public int probeCount() {
        int count = 0;
        if (tcpProbes != null) {
            count += tcpProbes.size();
        }
        if (httpProbes != null) {
            count += httpProbes.size();
        }
        if (cmdProbes != null) {
            count += cmdProbes.size();
        }
        return count;
    }

    /**
     * tcp 端口连通探测（默认超时 2000ms）。
     */
    public static class TcpProbe {

        /**
         * 目标主机（默认 127.0.0.1）。
         */
        private String host;

        /**
         * 目标端口。
         */
        private Integer port;

        /**
         * 单次探测超时毫秒（缺省 2000）。
         */
        private Integer timeoutMs;

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

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * http 期望码+响应体探测（默认超时 2000ms；状态码默认期望 200，命中任一即成功；
     * 响应体子串命中任一即成功）。
     */
    public static class HttpProbe {

        /**
         * 完整 URL。
         */
        private String url;

        /**
         * 期望状态码列表（默认 200）。
         */
        private List<Integer> expectCode;

        /**
         * 期望响应体子串列表（非空时状态码通过后响应体须包含其中任一子串才算成功）。
         */
        private List<String> expectBody;

        /**
         * 自定义请求头。
         */
        private Map<String, String> headers;

        /**
         * 单次探测超时毫秒（缺省 2000）。
         */
        private Integer timeoutMs;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public List<Integer> getExpectCode() {
            return expectCode;
        }

        public void setExpectCode(List<Integer> expectCode) {
            this.expectCode = expectCode;
        }

        public List<String> getExpectBody() {
            return expectBody;
        }

        public void setExpectBody(List<String> expectBody) {
            this.expectBody = expectBody;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * cmd 脚本探测（/bin/sh 进程，默认超时 3000ms；退出码 0 且可选 expectOutput 输出子串命中才算成功）。
     */
    public static class CmdProbe {

        /**
         * 脚本内容。
         */
        private String script;

        /**
         * 期望输出子串列表（非空时退出码 0 后 stdout 须包含其中任一子串才算成功）。
         */
        private List<String> expectOutput;

        /**
         * 单次探测超时毫秒（缺省 3000）。
         */
        private Integer timeoutMs;

        public String getScript() {
            return script;
        }

        public void setScript(String script) {
            this.script = script;
        }

        public List<String> getExpectOutput() {
            return expectOutput;
        }

        public void setExpectOutput(List<String> expectOutput) {
            this.expectOutput = expectOutput;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
