package uw.ops.agent.helper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.hardware.CentralProcessor;
import uw.common.util.SystemClock;
import uw.ops.agent.helper.vo.ContainerCounters;
import uw.ops.agent.helper.vo.ContainerEventRaw;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;
import uw.ops.agent.helper.vo.DiskCounters;
import uw.ops.agent.helper.vo.HostBoundaryCounters;
import uw.ops.agent.helper.vo.NetIfCounters;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.sub.ContainerInfo;
import uw.ops.agent.vo.sub.CpuStats;
import uw.ops.agent.vo.sub.DiskStats;
import uw.ops.agent.vo.sub.NetworkStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 60 秒窗口两点差分器（采集→差分→事件合并→payload 组装一体化）。
 * <p>
 * 主轮每次激发调 {@link #buildHostStats()} 完成采集与差分组装，Host 边界快照由其内部调
 * SystemInfoHelper 的 build*Counters 取值方法采集拼装（{@link #buildHostBoundary()}），
 * 与上次激发保存的"当前数值"做差即得刚结束窗口的精确均值，返回上报对象——差分完新值设为当前值。
 * <ul>
 *   <li>累计计数两点差 = 精确满窗均值（非近似）；窗口 = 相邻激发间隔（fixedRate 恒定 60s）；</li>
 *   <li>差分有效条件：gap ∈ [45s, 90s]；计数回退（Δ&lt;0，容器重启）非负钳 0；主机 tick 回退 = 重启，整组 NULL；</li>
 *   <li>容器视图三合一：observed（inventory）+ 窗口统计（stats 差分）+ 事件（10s 轮累积）全部聚合在
 *       ContainerInfo 上，容器 hash 全链路只传一遍；事件容器不在存活快照时以 state=destroyed
 *       兜底 info 承载（--rm 秒退等瞬时容器的事件不丢，状态语义统一走 state 字段）；</li>
 *   <li>inventoryOk=false（本轮快照失败）：payload 仍含事件兜底容器，Center 只处理事件不动台账；</li>
 *   <li>容器计数全基本类型（toContainerCounters 缺失按 0，差分钳 0）：差分失效（无基准/gap 越界）
 *       字段输出 0，未采集由行级存在性表达（statsList=null）；主机侧组级缺失走 NULL：CPU 组无效
 *       整组 NULL、磁盘/网卡组仅骨架。</li>
 * </ul>
 * 线程模型：buildHostStats 主轮单线程；recordContainerEvents 由独立 events 任务并发调用
 * （pendingEvents synchronized 保护跨线程）。
 * <p>
 * 静态单例设计（agent 进程内唯一差分器）：状态为类级 static，方法全 static 直调，调用方无需持有实例。
 */
public class HostStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(HostStatsCollector.class);

    /**
     * 差分间隔下限(ms)：低于视为追赶轮/时钟异常。
     */
    private static final long MIN_GAP_MILLIS = 45_000L;

    /**
     * 差分间隔上限(ms)=窗口×1.5：超界视为停摆，该窗口 NULL。
     */
    private static final long MAX_GAP_MILLIS = 90_000L;

    /**
     * 事件窗口首轮回看时长(纳秒)：覆盖 agent 启动间隙（进程未运行期间的事件不丢）。
     */
    private static final long FIRST_EVENT_WINDOW_LOOKBACK_NANOS = 70_000_000_000L;

    /**
     * 主机差分基准（上次激发的边界快照；启动时由 init 建立基线）。
     */
    private static volatile HostBoundaryCounters lastHostBoundary;

    /**
     * 容器差分基准：containerId -&gt; 上次激发的快照。
     */
    private static final Map<String, ContainerCounters> lastContainerCounters = new ConcurrentHashMap<>();

    /**
     * 待上报事件缓存（平铺 FIFO，10s events 线程写 / 60s 主轮 drain 取走后按容器分组合并，synchronized 保护）：
     * 写入路径只做 append 与截断（ArrayDeque 头删 O(1)，溢出态无 arraycopy），分组合并收敛在 build 阶段。
     */
    private static final ArrayDeque<PendingEvent> pendingEvents = new ArrayDeque<>();

    /**
     * 平铺事件载体（hash + 事件对）：分组前的中间形态，不参与序列化与反射。
     */
    private record PendingEvent(String containerHash, ContainerInfo.Event event) {
    }

    /**
     * 待上报事件缓存上限：发布风暴/Center 长挂防失控，超限丢最旧并告警（每条约 200B，上限约 2MB）。
     */
    private static final int MAX_PENDING_EVENTS = 10_000;

    /**
     * 事件窗口上界（纳秒，最近一次消费的最大 timeNano）：下轮 since 与去重依据。
     */
    private static volatile long lastEventNano;

    /**
     * 每小时静态信息上报入口（薄代理）：主机静态信息无差分无状态，采集直通 SystemInfoHelper；
     * 收口在此使 MainService 只与本类交互。
     *
     * @return 主机静态信息上报对象
     */
    public static HostInfo buildHostInfo() {
        return SystemInfoHelper.buildHostInfo();
    }

    /**
     * 主轮唯一入口（60s 激发）：一次完成 inventory 拉取 → running 容器 stats 批量 →
     * Host 边界采集 → 差分与事件合并组装，返回上报对象。各采集环节独立降级（inventory 失败
     * 置 inventoryOk=false、单容器 stats 失败该项 NULL），返回全新窗口对象——上报失败的
     * 重试由调用方在发送层当场处理（全败回灌走 {@link #restoreContainerEventsFromPayload}）。
     */
    public static HostStats buildHostStats() {
        //① inventory：失败时 null（绝不变更容器跟踪表），本轮容器指标置空。
        List<ContainerSummaryRaw> inventory = null;
        try {
            inventory = DockerInfoHelper.listAllContainers();
        } catch (Throwable e) {
            log.warn("docker inventory failed: {}", e.getMessage());
        }
        //② running 容器 stats（差分第二点），整轮 12s 预算；失败容器缺省（该项 NULL）。
        Map<String, ContainerStatsRaw> statsMap = null;
        if (inventory != null) {
            try {
                List<String> runningIds = new ArrayList<>(inventory.size());
                for (ContainerSummaryRaw summary : inventory) {
                    if (DockerInfoHelper.STATE_RUNNING.equals(summary.getState())) {
                        runningIds.add(summary.getId());
                    }
                }
                statsMap = DockerInfoHelper.containerStatsBatch(runningIds);
            } catch (Throwable e) {
                log.error("stats collection failed: {}", e.getMessage(), e);
            }
        }
        //③ 差分与事件合并组装（Host 边界快照在组装内部采集，与 stats 同刻 δ=0）。
        return buildHostStats(SystemInfoHelper.getHostHash(), inventory, statsMap);
    }

    /**
     * 事件窗口轮询入口（10s）：计算窗口 [上界无缝衔接/首轮回看 70s, now] → 拉取 → 累积。
     * 拉取失败跳过本轮（窗口上界不推进，下轮无缝续拉，事件不丢）——异常在本入口内消化，
     * 调用方无需 catch。
     */
    public static void pollContainerEvents() {
        long untilNano = SystemClock.now() * 1_000_000L;
        //窗口起点：有上界从上界无缝续拉；首轮回看 70s 覆盖 agent 启动间隙（进程未运行期间的事件不丢）。
        long sinceNano = lastEventNano != 0 ? lastEventNano : untilNano - FIRST_EVENT_WINDOW_LOOKBACK_NANOS;
        try {
            recordContainerEvents(DockerInfoHelper.containerEvents(sinceNano, untilNano), untilNano);
        } catch (Throwable e) {
            log.warn("docker events poll failed, skip this round: {}", e.getMessage());
        }
    }

    /**
     * 从放弃的窗口 payload 提取事件回灌（发送层 3 次重试全败/中断时调用）：
     * 窗口快照可弃事件不可再生（台账与告警的证据链），下轮组包重新携带。
     *
     * @param payload 上报失败被放弃的窗口对象
     */
    public static void restoreContainerEventsFromPayload(HostStats payload) {
        if (payload == null || payload.getContainerInfoList() == null) {
            return;
        }
        List<PendingEvent> events = new ArrayList<>();
        for (ContainerInfo info : payload.getContainerInfoList()) {
            if (info.getEventList() != null) {
                for (ContainerInfo.Event event : info.getEventList()) {
                    events.add(new PendingEvent(info.getId(), event));
                }
            }
        }
        if (events.isEmpty()) {
            return;
        }
        synchronized (pendingEvents) {
            pendingEvents.addAll(events);
            //回灌可能超上限：丢最旧收敛。
            while (pendingEvents.size() > MAX_PENDING_EVENTS) {
                pendingEvents.pollFirst();
            }
        }
    }

    /**
     * 启动时建立主机差分基线（内部采集本时刻边界快照）：调用后首个窗口即产出有效差值；仅基线缺失时生效。
     */
    public static void init() {
        if (lastHostBoundary == null) {
            lastHostBoundary = buildHostBoundary();
        }
    }

//===== 事件管道（写入与累积，events 线程调用，private） =====

    /**
     * 记录一轮事件窗口（独立 events 线程调用）：全量容器事件投影为上报模型后平铺累积进待报缓存
     * （含 start/stop/create 等全部动作，异常判定归 Center）；timeNano &lt;= lastEventNano 的重复事件
     * 与 Actor.Id 缺失的事件跳过（前者窗口衔接去重，后者无法定位容器无消费方），窗口上界推进到
     * windowEndNano（空窗口也不滞留，下轮 since 恒定衔接）。
     *
     * @param events        本窗口事件列表（原始形状）；null/空只推进窗口上界
     * @param windowEndNano 本窗口终点（纳秒，即拉取请求的 until）
     */
    private static void recordContainerEvents(List<ContainerEventRaw> events, long windowEndNano) {
        if (events != null) {
            int dropped = 0;
            synchronized (pendingEvents) {
                for (ContainerEventRaw raw : events) {
                    if (raw == null || raw.getTimeNano() <= lastEventNano || StringUtils.isBlank(raw.getActorId())) {
                        continue;
                    }
                    if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
                        pendingEvents.pollFirst();
                        dropped++;
                    }
                    //投影为上报最小模型（action/timeNano/exitCode，hash 由分组键承载）：exitCode 仅
                    //die 事件有值，Actor.Attributes 缺失/非法格式为 null（Center 端按 0=非异常退出）。
                    ContainerInfo.Event event = new ContainerInfo.Event();
                    event.setAction(raw.getAction());
                    event.setTimeNano(raw.getTimeNano());
                    if (raw.getActor() != null && raw.getActor().getAttributes() != null) {
                        String code = raw.getActor().getAttributes().get("exitCode");
                        if (code != null && !code.isBlank()) {
                            try {
                                event.setExitCode(Integer.parseInt(code.trim()));
                            } catch (NumberFormatException e) {
                                //非法格式保持 null。
                            }
                        }
                    }
                    pendingEvents.addLast(new PendingEvent(raw.getActorId(), event));
                }
            }
            if (dropped > 0) {
                //批内合并告警一次：持续溢出态逐条打 WARN 会刷屏。
                log.warn("pending events overflow, {} oldest dropped (limit={})", dropped, MAX_PENDING_EVENTS);
            }
        }
        lastEventNano = Math.max(lastEventNano, windowEndNano);
    }

//===== 差分核心（组包骨架：基准推进→差分→容器投影→事件合并） =====

    /**
     * 差分与事件合并组装（私有核心）：归一化本轮容器快照 → 当前变历史（推进基准）→ 差分 →
     * 容器视图三合一（observed + stats + events 聚合在 ContainerInfo 上）→ 全新窗口对象。
     * 每轮调用必产出新对象——上报失败的重试由 MainService 在发送层当场处理，与本方法无关。
     *
     * @param hostHash  主机标识
     * @param inventory 本轮容器清单（null=本轮 inventory 失败，payload 置 inventoryOk=false）
     * @param statsMap  本轮容器 stats（containerId -> 快照；失败容器缺省）
     * @return 全新窗口上报对象
     */
    private static HostStats buildHostStats(String hostHash, List<ContainerSummaryRaw> inventory, Map<String, ContainerStatsRaw> statsMap) {
        //差分基准（上一轮快照）与本轮归一化：容器基准须取快照副本——
        //下方 putAll 就地推进同一 map，引用会被本轮值覆盖导致 prev==curr。
        HostBoundaryCounters boundary = buildHostBoundary();
        HostBoundaryCounters prevBoundary = lastHostBoundary;
        Map<String, ContainerCounters> prevCounters = new HashMap<>(lastContainerCounters.size() * 2);
        prevCounters.putAll(lastContainerCounters);
        Map<String, ContainerCounters> currentCounters = toContainerCounters(statsMap);
        //当前变历史：推进差分基准（容器计数增量并入，消失容器由 inventory 清理）。
        lastHostBoundary = boundary;
        lastContainerCounters.putAll(currentCounters);
        //组窗：容器差分（纯函数：本轮归一化计数 vs 上一轮基准做差）+ 主机差分 + 末值快照。
        Map<String, ContainerInfo.Stats> containerStatsById = new HashMap<>(currentCounters.size() * 2);
        for (Map.Entry<String, ContainerCounters> counterEntry : currentCounters.entrySet()) {
            ContainerInfo.Stats stats = diffContainer(prevCounters.get(counterEntry.getKey()), counterEntry.getValue());
            if (stats != null) {
                containerStatsById.put(counterEntry.getKey(), stats);
            }
        }
        Map<String, ContainerInfo> views = new HashMap<>(inventory != null ? inventory.size() * 2 : 16);
        if (inventory != null) {
            for (ContainerSummaryRaw summary : inventory) {
                //inventory 投影：Names 取首个并剥离前导斜杠；Created 为 unix 秒（异常值<=0 置 null 由消费方兜底）。
                ContainerInfo info = new ContainerInfo();
                info.setId(summary.getId());
                if (summary.getNames() != null && !summary.getNames().isEmpty()) {
                    String name = summary.getNames().get(0);
                    info.setName(name != null && name.startsWith("/") ? name.substring(1) : name);
                }
                info.setImage(summary.getImage());
                info.setCommand(summary.getCommand());
                info.setCreateDate(summary.getCreated() != null && summary.getCreated() > 0 ? new Date(summary.getCreated() * 1000L) : null);
                info.setState(summary.getState());
                info.setStatus(summary.getStatus());
                if (summary.getHostConfig() != null) {
                    info.setNetwork(summary.getHostConfig().getNetworkMode());
                }
                ContainerInfo.Stats stats = containerStatsById.get(info.getId());
                if (stats != null) {
                    info.setStatsList(List.of(stats));
                }
                views.put(info.getId(), info);
            }
            //容器差分基准同步清理（消失容器移除）：present 集即 views 的 key 集，无需另建 HashSet。
            lastContainerCounters.keySet().retainAll(views.keySet());
        }
        boolean hostGapValid = prevBoundary != null && gapValid(prevBoundary.monoNanos, boundary.monoNanos);
        HostStats stats = new HostStats();
        stats.setHostHash(hostHash);
        stats.setStatsDate(new Date());
        //Host 差分窗口有效性统一判定（一次）：无效时 CPU 组 NULL、磁盘/网卡组仅骨架——各组失效输出形态不同，但判定同一。
        stats.setCpuStats(diffHostCpu(prevBoundary, boundary, hostGapValid));
        stats.setDiskStatsList(diffHostDisk(prevBoundary, boundary, hostGapValid));
        stats.setNetworkStatsList(diffHostNetwork(prevBoundary, boundary, hostGapValid));
        stats.setMemStats(SystemInfoHelper.buildMemStats());
        stats.setLoadStats(SystemInfoHelper.buildLoadStats());
        stats.setFsStatsList(SystemInfoHelper.buildFsStatsList());
        stats.setInternetStats(SystemInfoHelper.buildInternetStats());
        //===== 事件合并（最后一步：此前步骤若抛异常，drain 尚未发生，事件留缓存下轮再带；=====
        //本段一旦执行，drain 出的事件只能随 payload 走——失败由 restoreContainerEventsFromPayload 回灌）。
        List<PendingEvent> eventBatch;
        synchronized (pendingEvents) {
            eventBatch = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        //平铺批按容器分组：组内保持累积序，分组间顺序无业务语义（兜底容器排列不影响 Center 处理）。
        Map<String, List<ContainerInfo.Event>> eventsById = new HashMap<>(eventBatch.size() * 2);
        for (PendingEvent pending : eventBatch) {
            eventsById.computeIfAbsent(pending.containerHash(), k -> new ArrayList<>()).add(pending.event());
        }
        for (Map.Entry<String, List<ContainerInfo.Event>> entry : eventsById.entrySet()) {
            ContainerInfo info = views.get(entry.getKey());
            if (info == null) {
                //事件兜底容器：本窗有事件但已不在存活快照（--rm 秒退/已销毁/快照间隙）——
                //空 observed + state=destroyed（统一走 state 语义），Center 只处理其事件不动台账。
                info = new ContainerInfo();
                info.setId(entry.getKey());
                info.setState(ContainerInfo.STATE_DESTROYED);
                views.put(entry.getKey(), info);
            }
            info.setEventList(entry.getValue());
        }
        stats.setContainerInfoList(new ArrayList<>(views.values()));
        return stats;
    }

    /**
     * 采集本激发时刻的 Host 边界快照：取值走 SystemInfoHelper build*Counters 系列，
     * 打点用 System.nanoTime（与容器差分 toContainerCounters 同源）。
     *
     * @return 边界计数快照
     */
    private static HostBoundaryCounters buildHostBoundary() {
        return new HostBoundaryCounters(System.nanoTime(),
                SystemInfoHelper.buildCpuCounters(),
                SystemInfoHelper.buildNetworkCounters(),
                SystemInfoHelper.buildDiskCounters());
    }

    /**
     * 单容器差分：均值/速率 = 两点差 ÷ gap 秒；mem/pid = 末值；无基准或 gap 无效 → 差分字段缺省 0；回退（重启）非负钳制。
     */
    private static ContainerInfo.Stats diffContainer(ContainerCounters prev, ContainerCounters curr) {
        ContainerInfo.Stats stats = new ContainerInfo.Stats();
        //末值快照：working set = usage - cache(v1)/inactive_file(v2，toContainerCounters 归一进 memCache)。
        stats.setMemUsage(Math.max(curr.memUsage - curr.memCache, 0));
        stats.setMemTotal(curr.memLimit);
        if (curr.memLimit > 0) {
            stats.setMemPercent(round2(stats.getMemUsage() * 100.0 / curr.memLimit));
        }
        stats.setPidCount((int) curr.pidCount);
        stats.setPidsLimit(curr.pidsLimit > 0 ? curr.pidsLimit : 0L);
        //两点差分。
        if (prev == null) {
            return stats;
        }
        if (!gapValid(prev.monoNanos, curr.monoNanos)) {
            return stats;
        }
        double seconds = (curr.monoNanos - prev.monoNanos) / 1_000_000_000.0;
        //cpu_load：单核口径可超 100%，×10 = milliCores。
        long cpuDiff = delta(curr.cpuTotalUsage, prev.cpuTotalUsage);
        stats.setCpuPercent(round2(cpuDiff / 1_000_000_000.0 / seconds * 100));
        //throttle 时长占比（0-100）与频率：CFS 周期在走（periodsDelta>0）即输出，
        //无配额容器 throttledNanos 恒 0 输出 0%，与真实低值不冲突。
        long periodsDelta = delta(curr.periods, prev.periods);
        if (periodsDelta > 0) {
            long throttled = delta(curr.throttledNanos, prev.throttledNanos);
            stats.setCpuThrottle(Math.max(0, Math.min(100, round2(throttled / 1_000_000_000.0 / seconds * 100))));
            long throttledPeriods = delta(curr.throttledPeriods, prev.throttledPeriods);
            stats.setCpuThrottleFreq(round2(throttledPeriods * 100.0 / periodsDelta));
        }
        //网络：任一点无 networks 块（host 模式/中途切换）→ 不输出；有则非负钳制求速率。
        if (prev.networksPresent && curr.networksPresent) {
            stats.setNetworkIn(Math.round(delta(curr.netRx, prev.netRx) / seconds));
            stats.setNetworkOut(Math.round(delta(curr.netTx, prev.netTx) / seconds));
            stats.setRxDropped(delta(curr.rxDropped, prev.rxDropped));
            stats.setTxDropped(delta(curr.txDropped, prev.txDropped));
            stats.setRxErrors(delta(curr.rxErrors, prev.rxErrors));
            stats.setTxErrors(delta(curr.txErrors, prev.txErrors));
        }
        //磁盘：blkio 窗口平均速率。
        stats.setDiskRead(Math.round(delta(curr.diskRead, prev.diskRead) / seconds));
        stats.setDiskWrite(Math.round(delta(curr.diskWrite, prev.diskWrite) / seconds));
        return stats;
    }

    /**
     * 本轮容器 stats 归一化为计数快照（纯转换，无副作用）：失败容器（null 值）跳过；
     * 单容器字段缺失按 0，分网卡/分设备求和，v1 cache / v2 inactive_file 归一为单一
     * memCache（取存在者，都不存在=0）。
     */
    private static Map<String, ContainerCounters> toContainerCounters(Map<String, ContainerStatsRaw> statsMap) {
        Map<String, ContainerCounters> result = new HashMap<>(statsMap == null ? 16 : statsMap.size() * 2);
        if (statsMap == null) {
            return result;
        }
        for (Map.Entry<String, ContainerStatsRaw> entry : statsMap.entrySet()) {
            ContainerStatsRaw stats = entry.getValue();
            if (stats == null) {
                continue;
            }
            ContainerCounters counter = new ContainerCounters();
            counter.monoNanos = System.nanoTime();
            ContainerStatsRaw.CpuSection cpu = stats.getCpuStats();
            if (cpu != null) {
                if (cpu.getCpuUsage() != null) {
                    counter.cpuTotalUsage = nvl(cpu.getCpuUsage().getTotalUsage());
                }
                ContainerStatsRaw.ThrottlingSection throttling = cpu.getThrottlingData();
                if (throttling != null) {
                    counter.throttledNanos = nvl(throttling.getThrottledTime());
                    counter.periods = nvl(throttling.getPeriods());
                    counter.throttledPeriods = nvl(throttling.getThrottledPeriods());
                }
            }
            ContainerStatsRaw.MemorySection memory = stats.getMemoryStats();
            if (memory != null) {
                counter.memUsage = nvl(memory.getUsage());
                counter.memLimit = nvl(memory.getLimit());
                if (memory.getStats() != null) {
                    Long v1Cache = memory.getStats().get("cache");
                    Long v2Inactive = memory.getStats().get("inactive_file");
                    counter.memCache = v1Cache != null ? v1Cache : nvl(v2Inactive);
                }
            }
            ContainerStatsRaw.PidsSection pids = stats.getPidsStats();
            if (pids != null) {
                counter.pidCount = nvl(pids.getCurrent());
                counter.pidsLimit = nvl(pids.getLimit());
            }
            Map<String, ContainerStatsRaw.NetworkIfStats> networks = stats.getNetworks();
            if (networks != null && !networks.isEmpty()) {
                counter.networksPresent = true;
            long rx = 0, tx = 0, rxDrop = 0, txDrop = 0, rxErr = 0, txErr = 0;
            for (ContainerStatsRaw.NetworkIfStats ifStats : networks.values()) {
                if (ifStats == null) {
                    continue;
                }
                rx += nvl(ifStats.getRxBytes());
                tx += nvl(ifStats.getTxBytes());
                rxDrop += nvl(ifStats.getRxDropped());
                txDrop += nvl(ifStats.getTxDropped());
                rxErr += nvl(ifStats.getRxErrors());
                txErr += nvl(ifStats.getTxErrors());
            }
                counter.netRx = rx;
                counter.netTx = tx;
                counter.rxDropped = rxDrop;
                counter.txDropped = txDrop;
                counter.rxErrors = rxErr;
                counter.txErrors = txErr;
            }
            ContainerStatsRaw.BlkioSection blkio = stats.getBlkioStats();
            if (blkio != null && blkio.getIoServiceBytes() != null) {
                long read = 0, write = 0;
                for (ContainerStatsRaw.BlkioEntry ioEntry : blkio.getIoServiceBytes()) {
                    if (ioEntry == null || ioEntry.getValue() == null) {
                        continue;
                    }
                    if ("Read".equalsIgnoreCase(ioEntry.getOp())) {
                        read += ioEntry.getValue();
                    } else if ("Write".equalsIgnoreCase(ioEntry.getOp())) {
                        write += ioEntry.getValue();
                    }
                }
                counter.diskRead = read;
                counter.diskWrite = write;
            }
            result.put(entry.getKey(), counter);
        }
        return result;
    }

//===== 主机差分 =====

    /**
     * 主机 CPU 差分：占比 = Δtick / ΣΔtick；cs/int = 差 ÷ 实际间隔秒。
     * 防护：窗口无效（外围判定）或任一 tick 回退（重启）→ 整组 NULL。
     */
    private static CpuStats diffHostCpu(HostBoundaryCounters prev, HostBoundaryCounters curr, boolean gapValid) {
        if (!gapValid) {
            return null;
        }
        long[] tickDeltas = new long[8];
        long total = 0;
        for (int t = 0; t < 8; t++) {
            tickDeltas[t] = curr.cpu.cpuTicks[t] - prev.cpu.cpuTicks[t];
            if (tickDeltas[t] < 0) {
                return null;
            }
            total += tickDeltas[t];
        }
        if (total <= 0) {
            return null;
        }
        double seconds = (curr.monoNanos - prev.monoNanos) / 1_000_000_000.0;
        long cs = Math.round(delta(curr.cpu.contextSwitches, prev.cpu.contextSwitches) / seconds);
        long in = Math.round(delta(curr.cpu.interrupts, prev.cpu.interrupts) / seconds);
        //tick 下标按 OSHI TickType 序（USER/NICE/SYS/IDLE/IOWAIT/IRQ/SOFTIRQ/STEAL），
        //具名取出对齐 CpuStats 构造参数，避免裸下标位置错配静默漂移。
        long user = tickDeltas[CentralProcessor.TickType.USER.getIndex()];
        long nice = tickDeltas[CentralProcessor.TickType.NICE.getIndex()];
        long sys = tickDeltas[CentralProcessor.TickType.SYSTEM.getIndex()];
        long idle = tickDeltas[CentralProcessor.TickType.IDLE.getIndex()];
        long ioWait = tickDeltas[CentralProcessor.TickType.IOWAIT.getIndex()];
        long hardIrq = tickDeltas[CentralProcessor.TickType.IRQ.getIndex()];
        long softIrq = tickDeltas[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        long steal = tickDeltas[CentralProcessor.TickType.STEAL.getIndex()];
        return new CpuStats(cs, in,
                percent(user, total), percent(nice, total), percent(sys, total),
                percent(idle, total), percent(ioWait, total), percent(hardIrq, total),
                percent(softIrq, total), percent(steal, total));
    }

    /**
     * 主机磁盘差分：窗口增量（次数/字节，不除秒）。骨架直接遍历本轮边界计数的 keySet
     * （与差分输入同源，无需二次 OSHI 遍历），queueLength 取即时值字段直传；
     * gap 无效或无基准 → 仅骨架（计数 0）；boundary 缺失 → 空列表（Center 求和口径等价 0）。
     */
    private static List<DiskStats> diffHostDisk(HostBoundaryCounters prev, HostBoundaryCounters curr, boolean gapValid) {
        if (curr == null) {
            return new ArrayList<>(0);
        }
        List<DiskStats> list = new ArrayList<>(curr.diskCounters.size());
        for (Map.Entry<String, DiskCounters> entry : curr.diskCounters.entrySet()) {
            DiskCounters currDisk = entry.getValue();
            DiskStats disk = new DiskStats();
            disk.setName(entry.getKey());
            disk.setQueueLength(currDisk.queueLength);
            if (gapValid) {
                DiskCounters prevDisk = prev.diskCounters.get(entry.getKey());
                if (prevDisk != null) {
                    disk.setReads(delta(currDisk.reads, prevDisk.reads));
                    disk.setReadBytes(delta(currDisk.readBytes, prevDisk.readBytes));
                    disk.setWrites(delta(currDisk.writes, prevDisk.writes));
                    disk.setWriteBytes(delta(currDisk.writeBytes, prevDisk.writeBytes));
                    disk.setTransferTime(delta(currDisk.transferTime, prevDisk.transferTime));
                }
            }
            list.add(disk);
        }
        return list;
    }

    /**
     * 主机网卡差分：窗口平均速率 = 字节差 ÷ 实际间隔秒。骨架直接遍历本轮边界计数的
     * entrySet（LinkedHashMap 按 index 升序，与差分输入同源，无需二次 OSHI 遍历——对齐磁盘侧模式）；
     * gap 无效或无基准 → 仅骨架（速率 0）；boundary 缺失 → 空列表（Center 侧保持 0）。
     */
    private static List<NetworkStats> diffHostNetwork(HostBoundaryCounters prev, HostBoundaryCounters curr, boolean gapValid) {
        if (curr == null) {
            return new ArrayList<>(0);
        }
        List<NetworkStats> list = new ArrayList<>(curr.netCounters.size());
        double seconds = gapValid ? (curr.monoNanos - prev.monoNanos) / 1_000_000_000.0 : 0.0;
        for (Map.Entry<String, NetIfCounters> entry : curr.netCounters.entrySet()) {
            NetworkStats net = new NetworkStats();
            net.setName(entry.getKey());
            if (gapValid) {
                NetIfCounters prevIf = prev.netCounters.get(entry.getKey());
                if (prevIf != null) {
                    long rx = entry.getValue().rxBytes - prevIf.rxBytes;
                    long tx = entry.getValue().txBytes - prevIf.txBytes;
                    if (rx >= 0) {
                        net.setRxRate(Math.round(rx / seconds));
                    }
                    if (tx >= 0) {
                        net.setTxRate(Math.round(tx / seconds));
                    }
                }
            }
            list.add(net);
        }
        return list;
    }

//===== 数值工具 =====

    /**
     * 差分间隔有效性（容器/主机共用同一门槛）：毫秒间隔落在 [45s, 90s]。
     */
    private static boolean gapValid(long prevNanos, long currNanos) {
        long gapMillis = (currNanos - prevNanos) / 1_000_000L;
        return gapMillis >= MIN_GAP_MILLIS && gapMillis <= MAX_GAP_MILLIS;
    }

    /**
     * 两点差非负钳 0（计数回退=容器/主机重启，置 0）。
     */
    private static long delta(long curr, long prev) {
        return Math.max(curr - prev, 0);
    }

    /**
     * null 安全取 long。
     */
    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 占比两位小数（非负钳制后计算）。
     */
    private static double percent(long value, long total) {
        return round2(100.0 * Math.max(value, 0) / total);
    }

    /**
     * 两位小数。
     */
    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

}