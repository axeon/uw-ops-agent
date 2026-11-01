package uw.ops.agent.vo;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 任务统一载荷协议（task_data），结构与部署模板 deploy_data 同构。
 * <p>
 * center 与 agent 共用的任务协议契约：ops-center 生成任务时引用，
 * agent 的 OpsTask 内嵌引用并按 {@code ExecType} 分发执行器。
 * 协议键名统一 camelCase（默认 bean 命名，无 @JsonProperty 注解），
 * 字段名为契约的一部分，两侧必须逐字一致。
 */
public class TaskData {

    /**
     * sensitiveOutput 任务的对外固定结果文案（三层脱敏共用：runTaskScript/runOpsTask/resultPayload，
     * 禁止拼接执行输出——输出可能含展开后的秘密）。
     */
    public static final String SENSITIVE_RESULT_WITHHELD = "result withheld: task uses secrets.";

    /**
     * sensitiveOutput 任务的对外固定错误文案（同上，三层脱敏共用）。
     */
    public static final String SENSITIVE_ERROR_WITHHELD = "task execution failed.";

    /**
     * 任务协议版本，当前固定 2。
     */
    private int taskVersion = 2;

    /**
     * 执行类型："SHELL" / "DOCKER"，执行器分发的唯一依据（有 taskData 即结构化任务，
     * 不再以隐式约定判别）。
     */
    private String execType;

    /** 任务领取时使用过秘密，执行输出不得回传或落日志。 */
    private boolean sensitiveOutput;

    /** 任务模板引用的秘密键名；只含名称，不携带值。 */
    private List<String> secretKeys;

    /**
     * SHELL 执行段，ExecType=SHELL 时有效。
     */
    private ShellSection shell;

    /**
     * DOCKER 执行段，ExecType=DOCKER 时有效。
     */
    private ContainerSection container;

    public int getTaskVersion() {
        return taskVersion;
    }

    public void setTaskVersion(int taskVersion) {
        this.taskVersion = taskVersion;
    }

    public String getExecType() {
        return execType;
    }

    public void setExecType(String execType) {
        this.execType = execType;
    }

    public List<String> getSecretKeys() {
        return secretKeys;
    }

    public void setSecretKeys(List<String> secretKeys) {
        this.secretKeys = secretKeys;
    }

    public boolean isSensitiveOutput() {
        return sensitiveOutput;
    }

    public void setSensitiveOutput(boolean sensitiveOutput) {
        this.sensitiveOutput = sensitiveOutput;
    }

    public ShellSection getShell() {
        return shell;
    }

    public void setShell(ShellSection shell) {
        this.shell = shell;
    }

    public ContainerSection getContainer() {
        return container;
    }

    public void setContainer(ContainerSection container) {
        this.container = container;
    }

    /**
     * 任务统一载荷协议（task_data）的 SHELL 执行段，结构与部署模板 deploy_data 同构。
     */
    public static class ShellSection {

        /**
         * 变量替换完成后的最终脚本（center 侧已完成端口、镜像引用等变量替换）。
         */
        private String script;

        /**
         * center 分配的具体端口：key 为端口用途名，值为具体端口号字符串。
         */
        private Map<String, String> ports;

        /**
         * 执行超时秒（长任务分级上限由调度方按任务性质下发；null=默认 600s）。
         */
        private Integer timeoutSec;

        public String getScript() {
            return script;
        }

        public void setScript(String script) {
            this.script = script;
        }

        public Integer getTimeoutSec() {
            return timeoutSec;
        }

        public void setTimeoutSec(Integer timeoutSec) {
            this.timeoutSec = timeoutSec;
        }

        public Map<String, String> getPorts() {
            return ports;
        }

        public void setPorts(Map<String, String> ports) {
            this.ports = ports;
        }
    }

    /**
     * 任务统一载荷协议（task_data）的 DOCKER 执行段，结构与部署模板 deploy_data 同构。
     */
    public static class ContainerSection {

        /**
         * 容器操作动作："start" / "stop"。
         */
        private String action;

        /**
         * 镜像引用："registry/name@sha256:..." 摘要优先，无摘要时 ":tag" 兜底。
         */
        private String image;

        /**
         * 容器名：start 为 uw-ops-{containerId}（center 生成）；stop 为台账实际容器名。
         */
        private String containerName;

        /**
         * 目标容器 hash：stop 时按 hash 幂等定位容器（防止同名误伤）。
         */
        private String targetContainerHash;

        /**
         * 环境变量 K=V 字符串数组：与 Docker API Env 同构、保序；落库版本须剔除 secret。
         */
        private List<String> env;

        /**
         * 挂载声明：Binds 语法 "src:dst[:ro]"。
         */
        private List<String> mounts;

        /**
         * 启动命令（docker Cmd；空=镜像默认 ENTRYPOINT/CMD）。
         */
        private List<String> cmd;

        /**
         * Docker 网络模式（如 bridge / host）。
         */
        private String networkMode;

        /**
         * 重启策略，如 unless-stopped（数据字段，非常量：值来自台账配置）。
         */
        private String restartPolicy;

        /**
         * 运行身份（Config.User 终值）。
         */
        private String user;

        /**
         * 容器主机名（缺省=容器名：host 网络下 erlang 长名等以容器名为节点名的服务依赖此对齐）。
         */
        private String hostname;

        /**
         * 特权模式（全能力开关；单能力走 extra CapAdd）。
         */
        private Boolean privileged;

        /**
         * 命名空间内核参数（终值）。
         */
        private Map<String, String> sysctls;

        /**
         * 资源限制列表（如 nofile 句柄数）。
         */
        private List<Ulimit> ulimits;

        /**
         * 日志驱动（json-file/local/none 等；空=随 daemon 默认）。
         */
        private String logDriver;

        /**
         * 日志打标（LogConfig tag）。
         */
        private String logTag;

        /**
         * 单日志文件上限（如 50m）。
         */
        private String logMaxSize;

        /**
         * 日志文件保留份数（如 3）。
         */
        private String logMaxFile;

        /**
         * center 分配的具体端口：key 为端口用途名，值为具体端口号。
         * agent 结构化 start 前做本机 bind 校验（台账空闲≠本机可用），被占时从分配值
         * 递增偏移并改写为实际值，actualPorts 回传实际使用端口（center 台账比对不符告警）。
         */
        private Map<String, Integer> hostPorts;

        /**
         * DOCKER create 前宿主落盘文件段（bind 挂载源；落盘失败 fail-closed 中止任务——
         * 防目录挂载源缺失时 daemon 自动建空目录导致服务以默认配置静默启动）。
         */
        private List<ProvisionFile> files;

        /**
         * create 前宿主落盘文件（bind 挂载源）。
         */
        public static class ProvisionFile {

            /**
             * 容器内绝对路径（如 /etc/mysql/conf.d/uw-tuning.cnf）——必须落在本任务 mounts
             * 声明的某挂载点内（agent 按 create 参数反查宿主源落盘；无匹配挂载=载荷错误，
             * fail-closed 立即暴露：写宿主裸路径对容器不可见）。
             */
            private String name;

            /**
             * 文件内容（全量静态生成）。
             */
            private String content;

            /**
             * 权限位（如 0600；null=默认）。
             */
            private Integer mode;

            /**
             * 属主 uid（null=默认）。
             */
            private Integer uid;

            /**
             * 属主 gid（null=默认）。
             */
            private Integer gid;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getContent() {
                return content;
            }

            public void setContent(String content) {
                this.content = content;
            }

            public Integer getMode() {
                return mode;
            }

            public void setMode(Integer mode) {
                this.mode = mode;
            }

            public Integer getUid() {
                return uid;
            }

            public void setUid(Integer uid) {
                this.uid = uid;
            }

            public Integer getGid() {
                return gid;
            }

            public void setGid(Integer gid) {
                this.gid = gid;
            }
        }

        /**
         * 容器内存上限，单位 MB。
         */
        private Long containerMem;

        /**
         * CPU 配额（纳秒核，Docker NanoCPUs），0 = 不限制。
         */
        private Long nanoCpus;

        /**
         * CreateContainerParam.HostConfig 字段直通片段：超出 ContainerSection 显式字段的
         * 高级 HostConfig 参数原样透传（JsonNode 保持原始结构，不做二次建模）。
         */
        private JsonNode extra;

        /**
         * 探针配置（exec/敏感路径的终值 DSL JSON）：随 START 载荷下发，agent 缓存并落盘供本地就绪探测循环消费；为空表示无此路径配置。
         */
        private String probeTaskData;

        /**
         * 探针 label 载荷（tcp/http 无 secret 的终值 DSL JSON）：create 时写入容器 label
         * uw.ops.probe（声明式，随 inventory 收敛）；与 probeTaskData 互斥（label 优先）。
         */
        private String probeLabel;

        /**
         * 配置模板编码：agent 领取任务时据此注入对应 profile 的 secret。
         */
        private String profileCode;

        /**
         * registry 拉取凭据（X-Registry-Auth 头值=base64 json：username/password/serveraddress）。
         * center 领取时按镜像域名匹配内存注入（落库版不含），本地无镜像拉取时使用，
         * 用后即弃——不落盘、不进日志、不随上报回传。
         */
        private String registryAuth;

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public String getTargetContainerHash() {
            return targetContainerHash;
        }

        public void setTargetContainerHash(String targetContainerHash) {
            this.targetContainerHash = targetContainerHash;
        }

        public List<String> getEnv() {
            return env;
        }

        public void setEnv(List<String> env) {
            this.env = env;
        }

        public List<String> getMounts() {
            return mounts;
        }

        public List<String> getCmd() {
            return cmd;
        }

        public void setCmd(List<String> cmd) {
            this.cmd = cmd;
        }

        public void setMounts(List<String> mounts) {
            this.mounts = mounts;
        }

        public String getNetworkMode() {
            return networkMode;
        }

        public void setNetworkMode(String networkMode) {
            this.networkMode = networkMode;
        }

        public String getRestartPolicy() {
            return restartPolicy;
        }

        public void setRestartPolicy(String restartPolicy) {
            this.restartPolicy = restartPolicy;
        }

        public String getUser() {
            return user;
        }

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public Boolean getPrivileged() {
            return privileged;
        }

        public void setPrivileged(Boolean privileged) {
            this.privileged = privileged;
        }

        public Map<String, String> getSysctls() {
            return sysctls;
        }

        public void setSysctls(Map<String, String> sysctls) {
            this.sysctls = sysctls;
        }

        public List<Ulimit> getUlimits() {
            return ulimits;
        }

        public void setUlimits(List<Ulimit> ulimits) {
            this.ulimits = ulimits;
        }

        public String getLogDriver() {
            return logDriver;
        }

        public void setLogDriver(String logDriver) {
            this.logDriver = logDriver;
        }

        public String getLogTag() {
            return logTag;
        }

        public void setLogTag(String logTag) {
            this.logTag = logTag;
        }

        public String getLogMaxSize() {
            return logMaxSize;
        }

        public void setLogMaxSize(String logMaxSize) {
            this.logMaxSize = logMaxSize;
        }

        public String getLogMaxFile() {
            return logMaxFile;
        }

        public void setLogMaxFile(String logMaxFile) {
            this.logMaxFile = logMaxFile;
        }

        public List<ProvisionFile> getFiles() {
            return files;
        }

        public void setFiles(List<ProvisionFile> files) {
            this.files = files;
        }

        public Map<String, Integer> getHostPorts() {
            return hostPorts;
        }

        public void setHostPorts(Map<String, Integer> hostPorts) {
            this.hostPorts = hostPorts;
        }

        public Long getContainerMem() {
            return containerMem;
        }

        public void setContainerMem(Long containerMem) {
            this.containerMem = containerMem;
        }

        public Long getNanoCpus() {
            return nanoCpus;
        }

        public void setNanoCpus(Long nanoCpus) {
            this.nanoCpus = nanoCpus;
        }

        public JsonNode getExtra() {
            return extra;
        }

        public void setExtra(JsonNode extra) {
            this.extra = extra;
        }

        public String getProbeTaskData() {
            return probeTaskData;
        }

        public void setProbeTaskData(String probeTaskData) {
            this.probeTaskData = probeTaskData;
        }

        public String getProbeLabel() {
            return probeLabel;
        }

        public void setProbeLabel(String probeLabel) {
            this.probeLabel = probeLabel;
        }

        public String getProfileCode() {
            return profileCode;
        }

        public void setProfileCode(String profileCode) {
            this.profileCode = profileCode;
        }

        public String getRegistryAuth() {
            return registryAuth;
        }

        public void setRegistryAuth(String registryAuth) {
            this.registryAuth = registryAuth;
        }
    }
    /**
     * ulimit 条目（终值；agent 组装为 CreateContainerParam.Ulimit）。
     */
    public static class Ulimit {

        /**
         * 资源名（如 nofile）。
         */
        private String name;

        /**
         * 软限制。
         */
        private Long soft;

        /**
         * 硬限制。
         */
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

}
