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
     * 容器id。
     */
    private String id;

    /**
     * 容器名。
     */
    private String name;

    /**
     * 镜像。
     */
    private String image;

    /**
     * 创建时间。2023-03-27 14:37:43 +0800 CST
     */
    private Date createDate;

    /**
     * 执行的命令。
     */
    private String command;

    /**
     * 网络模式。
     */
    private String network;

    /**
     * 运行状态。running
     */
    private String state;

    /**
     * 启动时间。Up 5 weeks
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

    /**
     * 容器窗口统计（60 秒两点差分结果，hash 由外层 ContainerInfo 承载）。
     * <p>
     * 字段使用基本类型：差分失效（无基准/gap 越界）输出 0，未采集由行级存在性表达（statsList=null）。
     * <ul>
     *   <li>cpuPercent：单核口径百分比（可超 100%，×10 = milliCores），窗口平均；</li>
     *   <li>networkIn/networkOut/diskRead/diskWrite：窗口平均速率（字节/秒），host 网络模式容器网络项为 0；</li>
     *   <li>memPercent：working set 占配额百分比（v1 扣 cache / v2 扣 inactive_file）。</li>
     * </ul>
     */
    public static class Stats implements Serializable {

        /**
         * cpu占用率：单核口径百分比，可超100，两位小数。
         */
        private double cpuPercent;

        /**
         * cpu被配额限流时长占比(百分比,两位小数,0-100)。落库 ops_container_stats.cpu_throttle。
         * NULL=未设配额/计数回退/不适用；0=设了配额且没被掐。
         */
        private double cpuThrottle;

        /**
         * 内存使用率(working set 占配额百分比,两位小数)。
         */
        private double memPercent;

        /**
         * 内存使用(working set字节)，不落库（ops_container_stats 无对应列），随热信号缓存对象保留。
         */
        private long memUsage;

        /**
         * 内存限制(字节)。
         */
        private long memTotal;

        /**
         * 磁盘写速率(字节/秒)。
         */
        private long diskWrite;

        /**
         * 磁盘读速率(字节/秒)。
         */
        private long diskRead;

        /**
         * 网络入站速率(字节/秒)。host网络模式容器无networks数据，置NULL不是0。
         */
        private long networkIn;

        /**
         * 网络出站速率(字节/秒)。host网络模式为NULL。
         */
        private long networkOut;

        /**
         * 进程/线程数(窗口末值)。
         */
        private int pidCount;

        /**
         * throttle频率(throttled_periods/periods窗口比,百分比)——尖峰画像，不落库。
         */
        private double cpuThrottleFreq;

        /**
         * 窗口丢包增量(rx_dropped)，不落库，Center写缓存供告警消费。
         */
        private long rxDropped;

        /**
         * 窗口丢包增量(tx_dropped)，不落库。
         */
        private long txDropped;

        /**
         * 窗口错包增量(rx_errors)，不落库。
         */
        private long rxErrors;

        /**
         * 窗口错包增量(tx_errors)，不落库。
         */
        private long txErrors;

        /**
         * pids cgroup限额(0=未设)，不落库，逼近限额告警用。
         */
        private long pidsLimit;

        public double getCpuPercent() {
            return cpuPercent;
        }

        public void setCpuPercent(double cpuPercent) {
            this.cpuPercent = cpuPercent;
        }

        public double getCpuThrottle() {
            return cpuThrottle;
        }

        public void setCpuThrottle(double cpuThrottle) {
            this.cpuThrottle = cpuThrottle;
        }

        public double getMemPercent() {
            return memPercent;
        }

        public void setMemPercent(double memPercent) {
            this.memPercent = memPercent;
        }

        public long getMemUsage() {
            return memUsage;
        }

        public void setMemUsage(long memUsage) {
            this.memUsage = memUsage;
        }

        public long getMemTotal() {
            return memTotal;
        }

        public void setMemTotal(long memTotal) {
            this.memTotal = memTotal;
        }

        public long getDiskWrite() {
            return diskWrite;
        }

        public void setDiskWrite(long diskWrite) {
            this.diskWrite = diskWrite;
        }

        public long getDiskRead() {
            return diskRead;
        }

        public void setDiskRead(long diskRead) {
            this.diskRead = diskRead;
        }

        public long getNetworkIn() {
            return networkIn;
        }

        public void setNetworkIn(long networkIn) {
            this.networkIn = networkIn;
        }

        public long getNetworkOut() {
            return networkOut;
        }

        public void setNetworkOut(long networkOut) {
            this.networkOut = networkOut;
        }

        public int getPidCount() {
            return pidCount;
        }

        public void setPidCount(int pidCount) {
            this.pidCount = pidCount;
        }

        public double getCpuThrottleFreq() {
            return cpuThrottleFreq;
        }

        public void setCpuThrottleFreq(double cpuThrottleFreq) {
            this.cpuThrottleFreq = cpuThrottleFreq;
        }

        public long getRxDropped() {
            return rxDropped;
        }

        public void setRxDropped(long rxDropped) {
            this.rxDropped = rxDropped;
        }

        public long getTxDropped() {
            return txDropped;
        }

        public void setTxDropped(long txDropped) {
            this.txDropped = txDropped;
        }

        public long getRxErrors() {
            return rxErrors;
        }

        public void setRxErrors(long rxErrors) {
            this.rxErrors = rxErrors;
        }

        public long getTxErrors() {
            return txErrors;
        }

        public void setTxErrors(long txErrors) {
            this.txErrors = txErrors;
        }

        public long getPidsLimit() {
            return pidsLimit;
        }

        public void setPidsLimit(long pidsLimit) {
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
