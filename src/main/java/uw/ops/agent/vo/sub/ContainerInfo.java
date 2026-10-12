package uw.ops.agent.vo.sub;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 容器视图（60s 窗口三合一：observed + 窗口统计 statsList + 事件 eventList，容器 hash 只传一遍）。
 */
public class ContainerInfo {

    /**
     * 兜底状态（agent 合成，非 daemon 原生值）：本窗有事件但已不在 inventory 存活快照中
     * （--rm 秒退/已销毁/快照间隙）——observed 字段全空，Center 只处理其事件不动台账。
     */
    public static final String STATE_DESTROYED = "destroyed";

    /**
     * 容器完整 Id（containerHash 台账身份键）。
     */
    private String id;

    /**
     * 容器名（剥离前导斜杠后的主名）。
     */
    private String name;

    /**
     * 镜像。
     */
    private String image;

    /**
     * 创建时间（unix 秒转 Date；异常值置 null 由消费方兜底）。
     */
    private Date createDate;

    /**
     * 执行的命令（受管容器与标记敏感的容器不回传）。
     */
    private String command;

    /**
     * 网络模式（bridge/host/none...）。
     */
    private String network;

    /**
     * 运行状态（daemon 原生值：running/created/exited，兜底容器为 destroyed）。
     */
    private String state;

    /**
     * 人类可读状态（如 Up 5 weeks）。
     */
    private String status;

    /**
     * 窗口统计列表（60 秒差分结果）。null=未采集（exited 容器不在 running 采集集/本轮 stats 失败）；
     * List 形状前瞻多段统计（追赶轮拆段/未来窗口内加密采样），当前每窗一条。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Stats> statsList;

    /**
     * 本窗口事件（嵌套免重复传输容器hash；null=本窗无事件，序列化省略）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Event> eventList;

    /**
     * 探测摘要（ReadinessProber 聚合，随主轮上报；null=本容器不在探测集合——
     * 无 label 配置且无 taskData 落盘条目，Center 不动台账就绪字段）。
     * 协议值：0=UNKNOWN(未探测)、1=SUCCESS(最近探测成功)、2=FAILED(最近探测失败)。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer probeState;

    /**
     * 探测序号（每容器单调递增；Center 据此做乱序/重放屏障，时钟漂移免疫）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long probeSeq;

    /**
     * 最近一次探测时间（毫秒时间戳；展示与新鲜度判定用，不作为新旧判据）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long probeDate;

    /**
     * 连续失败次数（agent 本地聚合的无判断事实；Center 据此分级标记 NOT_READY）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer probeFails;

    /**
     * 连续成功毫秒数（agent 本地聚合；Center 发布门禁直接与 minReadySeconds 比较，
     * 精度=探测粒度而非上报粒度）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long probeStreak;

    /**
     * 最近失败原因短码（tcp-refused/tcp-timeout/http-status-503/exec-exit-3 等，≤32字符；成功后保留上一失败原因供排障）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String probeReason;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
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

    public List<Stats> getStatsList() {
        return statsList;
    }

    public void setStatsList(List<Stats> statsList) {
        this.statsList = statsList;
    }


    public List<Event> getEventList() {
        return eventList;
    }

    public void setEventList(List<Event> eventList) {
        this.eventList = eventList;
    }

    public Integer getProbeState() {
        return probeState;
    }

    public void setProbeState(Integer probeState) {
        this.probeState = probeState;
    }

    public Long getProbeSeq() {
        return probeSeq;
    }

    public void setProbeSeq(Long probeSeq) {
        this.probeSeq = probeSeq;
    }

    public Long getProbeDate() {
        return probeDate;
    }

    public void setProbeDate(Long probeDate) {
        this.probeDate = probeDate;
    }

    public Integer getProbeFails() {
        return probeFails;
    }

    public void setProbeFails(Integer probeFails) {
        this.probeFails = probeFails;
    }

    public Long getProbeStreak() {
        return probeStreak;
    }

    public void setProbeStreak(Long probeStreak) {
        this.probeStreak = probeStreak;
    }

    public String getProbeReason() {
        return probeReason;
    }

    public void setProbeReason(String probeReason) {
        this.probeReason = probeReason;
    }

    /**
     * 容器窗口统计（60 秒两点差分结果，hash 由外层 ContainerInfo 承载）。
     * <p>
     * 字段级 NULL 表示未采集、差分失效或不适用；0 仅表示有效采样测得零值。
     * <ul>
     *   <li>cpuPercent：单核口径百分比（可超 100%，×10 = milliCores），窗口平均；</li>
     *   <li>networkIn/networkOut/diskRead/diskWrite：窗口平均速率（字节/秒），host 网络模式容器网络项为 NULL；</li>
     *   <li>memPercent：working set 占配额百分比（v1 扣 cache / v2 扣 inactive_file）。</li>
     * </ul>
     */
    public static class Stats implements Serializable {

        /**
         * cpu占用率：单核口径百分比，可超100，两位小数。
         */
        private Double cpuPercent;

        /**
         * cpu被配额限流时长占比(百分比,两位小数,0-100)。落库 ops_container_stats.cpu_throttle。
         * NULL=未设配额/计数回退/不适用；0=设了配额且没被掐。
         */
        private Double cpuThrottle;

        /**
         * 内存使用率(working set 占配额百分比,两位小数)。
         */
        private Double memPercent;

        /**
         * 内存使用(working set字节)，不落库（ops_container_stats 无对应列），随热信号缓存对象保留。
         */
        private Long memUsage;

        /**
         * 内存限制(字节)。
         */
        private Long memTotal;

        /**
         * 磁盘写速率(字节/秒)。
         */
        private Long diskWrite;

        /**
         * 磁盘读速率(字节/秒)。
         */
        private Long diskRead;

        /**
         * 网络入站速率(字节/秒)。host网络模式容器无networks数据，置NULL不是0。
         */
        private Long networkIn;

        /**
         * 网络出站速率(字节/秒)。host网络模式为NULL。
         */
        private Long networkOut;

        /**
         * 进程/线程数(窗口末值)。
         */
        private Integer pidCount;

        /**
         * throttle频率(throttled_periods/periods窗口比,百分比)——尖峰画像，不落库。
         */
        private Double cpuThrottleFreq;

        /**
         * 窗口丢包增量(rx_dropped)，不落库，Center写缓存供告警消费。
         */
        private Long rxDropped;

        /**
         * 窗口丢包增量(tx_dropped)，不落库。
         */
        private Long txDropped;

        /**
         * 窗口错包增量(rx_errors)，不落库。
         */
        private Long rxErrors;

        /**
         * 窗口错包增量(tx_errors)，不落库。
         */
        private Long txErrors;

        /**
         * pids cgroup限额(0=未设)，不落库，逼近限额告警用。
         */
        private Long pidsLimit;

        public Double getCpuPercent() {
            return cpuPercent;
        }

        public void setCpuPercent(Double cpuPercent) {
            this.cpuPercent = cpuPercent;
        }

        public Double getCpuThrottle() {
            return cpuThrottle;
        }

        public void setCpuThrottle(Double cpuThrottle) {
            this.cpuThrottle = cpuThrottle;
        }

        public Double getMemPercent() {
            return memPercent;
        }

        public void setMemPercent(Double memPercent) {
            this.memPercent = memPercent;
        }

        public Long getMemUsage() {
            return memUsage;
        }

        public void setMemUsage(Long memUsage) {
            this.memUsage = memUsage;
        }

        public Long getMemTotal() {
            return memTotal;
        }

        public void setMemTotal(Long memTotal) {
            this.memTotal = memTotal;
        }

        public Long getDiskWrite() {
            return diskWrite;
        }

        public void setDiskWrite(Long diskWrite) {
            this.diskWrite = diskWrite;
        }

        public Long getDiskRead() {
            return diskRead;
        }

        public void setDiskRead(Long diskRead) {
            this.diskRead = diskRead;
        }

        public Long getNetworkIn() {
            return networkIn;
        }

        public void setNetworkIn(Long networkIn) {
            this.networkIn = networkIn;
        }

        public Long getNetworkOut() {
            return networkOut;
        }

        public void setNetworkOut(Long networkOut) {
            this.networkOut = networkOut;
        }

        public Integer getPidCount() {
            return pidCount;
        }

        public void setPidCount(Integer pidCount) {
            this.pidCount = pidCount;
        }

        public Double getCpuThrottleFreq() {
            return cpuThrottleFreq;
        }

        public void setCpuThrottleFreq(Double cpuThrottleFreq) {
            this.cpuThrottleFreq = cpuThrottleFreq;
        }

        public Long getRxDropped() {
            return rxDropped;
        }

        public void setRxDropped(Long rxDropped) {
            this.rxDropped = rxDropped;
        }

        public Long getTxDropped() {
            return txDropped;
        }

        public void setTxDropped(Long txDropped) {
            this.txDropped = txDropped;
        }

        public Long getRxErrors() {
            return rxErrors;
        }

        public void setRxErrors(Long rxErrors) {
            this.rxErrors = rxErrors;
        }

        public Long getTxErrors() {
            return txErrors;
        }

        public void setTxErrors(Long txErrors) {
            this.txErrors = txErrors;
        }

        public Long getPidsLimit() {
            return pidsLimit;
        }

        public void setPidsLimit(Long pidsLimit) {
            this.pidsLimit = pidsLimit;
        }
    }

    /**
     * 容器事件（hash 由外层 ContainerInfo 承载）。
     * <p>
     * Engine API 原始事件（helper.vo.ContainerEventRaw）的最小投影：仅保留 Center 落库与
     * 异常判定所需字段，映射在 HostStatsCollector 灌入时完成（原始形状不做协议透传）。
     */
    public static class Event implements Serializable {

        /**
         * 事件动作(die/oom/restart/start/stop/create/destroy/pause...全量)
         */
        private String action;

        /**
         * daemon纳秒时间戳(unix nano，窗口衔接去重与Center幂等键的排序键)
         */
        private long timeNano;

        /**
         * die事件的退出码(其他事件null；缺失/非法为null，Center按0处理=非异常退出)
         */
        private Integer exitCode;

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public long getTimeNano() {
            return timeNano;
        }

        public void setTimeNano(long timeNano) {
            this.timeNano = timeNano;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public void setExitCode(Integer exitCode) {
            this.exitCode = exitCode;
        }
    }
}
