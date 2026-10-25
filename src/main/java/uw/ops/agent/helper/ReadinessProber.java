package uw.ops.agent.helper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.util.JsonUtils;
import uw.common.util.SystemClock;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.sub.ContainerInfo;
import uw.ops.agent.vo.ProbeScriptEntry;
import uw.ops.agent.vo.ProbeSpec;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 就绪探测循环（Agent 本地循环，不走任务通道）。
 * <p>
 * <b>配置到达（两条互斥路径，生成侧决策）：</b>
 * <ul>
 *   <li>tcp/http DSL（无 secret）→ 容器 label {@code uw.ops.probe}（create 携带、主轮 inventory
 *       声明式收敛：label 在=配置在，label 变=条目更新，容器删=条目消失）。Docker 元数据即
 *       持久化真相源，agent 重启后零恢复动作；探针配置变更无需重新部署。</li>
 *   <li>含 cmd 的 DSL（或含 secret 占位符的 tcp/http）→ START taskData（docker.probeConfig）
 *       下发，缓存条目落盘，agent 重启经 {@link #restoreFromDisk()} 重建（不落盘则该类容器
 *       永久失去探测）。</li>
 * </ul>
 * <p>
 * <b>多探测并存：</b>tcpProbes/httpProbes/cmdProbes 三组数组同时生效（同类多条、跨类并存），
 * 单轮遍历全部执行，任一失败即判定失败（AND 语义——全部通过才算就绪），失败原因取首个
 * 失败探测的短码。
 * <p>
 * <b>节奏：</b>启动期 5s 密集探测（首次成功即回落），稳态 15s；tcp=http=JVM 原生探测
 * （零进程 spawn、毫秒级），cmd=/bin/sh 进程（默认 3s 超时）。探测调度 5s 一轮、条目按
 * {@code nextProbeAt} 到期执行。
 * <p>
 * <b>聚合与上报：</b>agent 只做无判断计数（seq 单调、连续失败数、连续成功毫秒数、失败原因
 * 短码），每容器一个摘要随 60s 主轮上报（{@link ContainerInfo} probeState/probeSeq/probeDate/
 * probeFails/probeStreak/probeReason）。判定阈值与动作（门禁/NOT_READY/替换分级）全部在
 * Center——Agent 不做本地自治决策，Center 失联期间照常探测、期望未变的实例保持现状。
 * <p>
 * 安全边界：label/taskData 均为 center 受信内容（终值 DSL），不经过 ShellRiskChecker
 * （该检查面向任务通道的人工脚本）；exec 输出仅以退出码判定（输出可能携带令牌）。
 * <p>
 * 静态单例设计（与 HostStatsCollector 一致）；集合操作与聚合更新持类锁串行（条目量百级），
 * 探测执行在锁外（exec 3s 超时不阻塞主轮组包），结果回写时条目已消失则丢弃。
 */
public final class ReadinessProber {

    private static final Logger log = LoggerFactory.getLogger(ReadinessProber.class);

    /**
     * 探测结果：未探测（条目初始值，尚无样本）。
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * 探测结果：最近一次成功（协议上报值）。
     */
    public static final int STATE_SUCCESS = 1;

    /**
     * 探测结果：最近一次失败（协议上报值；连续失败分级在 Center）。
     */
    public static final int STATE_FAILED = 2;

    /**
     * 启动期探测间隔（毫秒）：新条目首次成功前密集探测（发布门禁分辨率），首次成功即回落稳态。
     */
    public static final long STARTUP_INTERVAL_MILLIS = 5_000L;

    /**
     * 稳态探测间隔（毫秒）：运行期健康监控节奏；tcp/http 毫秒级成本，15s 负载可忽略。
     */
    public static final long STEADY_INTERVAL_MILLIS = 15_000L;

    /**
     * 探测调度轮间隔（秒）：{@code probeOnce} 的调度周期，条目按 nextProbeAt 到期执行。
     */
    public static final long PROBE_TICK_SECONDS = 5;

    /**
     * 默认超时：tcp/http 连接毫秒级、cmd 3 秒（终值 DSL 的 timeoutMs 可覆盖，钳制范围同 center 侧）。
     */
    static final int TCP_DEFAULT_TIMEOUT_MILLIS = 2_000;

    static final int HTTP_DEFAULT_TIMEOUT_MILLIS = 2_000;

    static final int CMD_DEFAULT_TIMEOUT_MILLIS = 3_000;

    /**
     * 失败原因短码上限（协议字段约束，Center 台账列 varchar(32)）。
     */
    private static final int REASON_LIMIT = 32;

    /**
     * expectBody 判定的响应体读取上限（字符，粗略即可）：健康端点响应远小于此，超限截断足够判定。
     */
    private static final int HTTP_BODY_READ_LIMIT = 64 * 1024;

    /** exec 退出码提取（失败路径每次探测命中，预编译）。 */
    private static final java.util.regex.Pattern EXEC_EXIT_CODE = java.util.regex.Pattern.compile("exited with code (\\d+)");

    /**
     * 探测条目表：dockerId -> 条目（label 声明式与 taskData 落盘两类来源共用一张表，按 dockerId 唯一）。
     */
    private static final Map<String, ProbeEntry> ENTRIES = new HashMap<>();

    /**
     * exec/taskData 路径的条目落盘目录（环境变量 OPS_AGENT_PROBE_DIR 可覆盖）。
     */
    private static final Path PROBE_STORE_DIR = Paths.get(Constants.getOpsAgentProbeDir());

    /**
     * 脚本执行器（测试可注入；生产=按 DSL 类型分派的原生执行器组）。
     */
    static volatile ProbeExecutor probeExecutor = ReadinessProber::runNative;

    /**
     * 单个受管容器的探测条目：终值配置 + 节奏 + 无判断聚合。字段仅在类锁内更新。
     */
    static final class ProbeEntry {

        /**
         * ops 台账容器ID（center 上报定位用，缓存键为 dockerId）。
         */
        final long containerId;

        /**
         * 终值探针配置（tcpProbes/httpProbes/cmdProbes 三组并存）。
         */
        final ProbeSpec spec;

        /**
         * 来源=true：label 声明式（随 inventory 收敛，无落盘）；false：taskData（exec/敏感，落盘）。
         */
        final boolean fromLabel;

        /**
         * 原始配置 JSON（label 收敛时的变更比对）。
         */
        final String sourceJson;

        /**
         * 下次到期时刻（System.nanoTime()；启动期/稳态间隔切换由 everSucceeded 决定）。
         */
        long nextProbeNanos;

        /**
         * 是否已有过成功样本（true=稳态间隔）。
         */
        boolean everSucceeded;

        //==== 聚合摘要（无判断事实，Center 消费） ====

        /**
         * 探测序号（单调递增；Center 乱序/重放屏障，时钟漂移免疫）。
         */
        long seq;

        /**
         * 最近一次探测状态（STATE_*）。
         */
        int lastState = STATE_UNKNOWN;

        /**
         * 最近一次探测墙钟时间（毫秒；展示与新鲜度判定用，不作新旧判据）。
         */
        long lastProbeWallClock;

        /**
         * 连续失败次数（成功清零）。
         */
        int probeFailCount;

        /**
         * 连续成功起点（nanoTime；0=无进行中 streak）。
         */
        long streakStartNanos;

        /**
         * 最近一次成功样本时的连续成功毫秒数（发布门禁判据）。
         */
        long streakMillis;

        /**
         * 最近失败原因短码（成功后保留，供排障）。
         */
        String lastReason;

        ProbeEntry(long containerId, ProbeSpec spec, boolean fromLabel, String sourceJson) {
            this.containerId = containerId;
            this.spec = spec;
            this.fromLabel = fromLabel;
            this.sourceJson = sourceJson;
            this.nextProbeNanos = System.nanoTime();
        }
    }

    /**
     * 探测执行函数接口（测试注入点）：返回 null=成功；非 null=失败原因短码。
     */
    @FunctionalInterface
    interface ProbeExecutor {

        /**
         * 执行一次探测。
         *
         * @param spec 终值探针配置
         * @return null=成功；非 null=失败原因短码
         */
        String run(ProbeSpec spec);
    }

    private ReadinessProber() {
    }

    //==================== 集合管理（类锁串行） ====================

    /**
     * taskData 路径注册（含 cmd/含 secret 的终值 DSL JSON，随 START 下发；幂等命中与新建两条路径都覆盖）：
     * 条目内存登记并落盘（agent 升级/重启经 {@link #restoreFromDisk()} 重建）。
     *
     * @param dockerId    Docker 容器 ID
     * @param containerId ops 台账容器ID
     * @param dslJson     终值 DSL JSON
     */
    public static synchronized void cacheStartScript(String dockerId, long containerId, String dslJson) {
        if (!safeDockerId(dockerId) || StringUtils.isBlank(dslJson)) {
            return;
        }
        ProbeSpec spec = parseSpec(dslJson);
        if (spec == null) {
            log.warn("readiness probe spec unparsable, skipped, containerId={}", containerId);
            removeContainer(dockerId);
            return;
        }
        ENTRIES.put(dockerId, new ProbeEntry(containerId, spec, false, dslJson));
        persistEntry(dockerId, containerId, dslJson);
        log.info("readiness probe registered (taskData), containerId={}, dockerId={}, probes={}", containerId, dockerId, probeSummary(spec));
    }

    /**
     * label 路径注册（tcp/http 终值 DSL JSON；create 携带与 inventory 收敛共用入口）：
     * 声明式条目仅驻内存（Docker 元数据即持久化真相源，重启后由 inventory 自然重建）。
     *
     * @param dockerId    Docker 容器 ID
     * @param containerId ops 台账容器ID
     * @param labelJson   label 值（终值 DSL JSON）
     */
    public static synchronized void registerFromLabel(String dockerId, long containerId, String labelJson) {
        if (!safeDockerId(dockerId) || StringUtils.isBlank(labelJson)) {
            return;
        }
        ProbeSpec spec = parseSpec(labelJson);
        if (spec == null) {
            log.warn("readiness probe label unparsable, skipped, containerId={}", containerId);
            return;
        }
        ENTRIES.put(dockerId, new ProbeEntry(containerId, spec, true, labelJson));
        log.info("readiness probe registered (label), containerId={}, dockerId={}, probes={}", containerId, dockerId, probeSummary(spec));
    }

    /**
     * 主轮 inventory 声明式收敛（仅成功轮调用）：label 在=配置在（无条目注册、有变化更新），
     * label 消失=该 label 条目移除（探针配置已下线）；taskData 条目（exec/敏感）不受 label 驱动。
     * 配置变化的条目整体重建（聚合计数归零重新累计，保守安全不误放行门禁）。
     *
     * @param labelsById 本轮全量容器 label 表（dockerId -> labels；null=本轮 inventory 失败，跳过）
     * @param labelKey   探针 label 键（uw.ops.probe）
     */
    public static synchronized void applyInventoryLabels(Map<String, Map<String, String>> labelsById, String labelKey) {
        if (labelsById == null) {
            return;
        }
        //① label 条目对账：label 消失或值变化 → 移除（变化者由 ② 从 labelsById 重建，声明式源唯一）。
        Iterator<Map.Entry<String, ProbeEntry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ProbeEntry> cached = it.next();
            if (!cached.getValue().fromLabel) {
                continue;
            }
            Map<String, String> labels = labelsById.get(cached.getKey());
            String labelJson = labels == null ? null : labels.get(labelKey);
            if (StringUtils.isBlank(labelJson) || !labelJson.equals(cached.getValue().sourceJson)) {
                it.remove();
                log.info("readiness probe label changed or gone, containerId={}, dockerId={}",
                        cached.getValue().containerId, cached.getKey());
            }
        }
        //② label 在而条目无/刚移除 → 注册（containerId 由受管 label 兜底解析，非受管容器跳过）。
        for (Map.Entry<String, Map<String, String>> row : labelsById.entrySet()) {
            String labelJson = row.getValue() == null ? null : row.getValue().get(labelKey);
            if (StringUtils.isBlank(labelJson)) {
                continue;
            }
            ProbeEntry existing = ENTRIES.get(row.getKey());
            if (existing != null && existing.fromLabel && labelJson.equals(existing.sourceJson)) {
                continue;
            }
            Long containerId = parseContainerIdLabel(row.getValue());
            if (containerId == null) {
                continue;
            }
            registerFromLabel(row.getKey(), containerId, labelJson);
        }
    }

    /**
     * 移除容器探测缓存（结构化 STOP 执行时调用；容器已删除，探测条目随之失效），并清理落盘条目。
     * 落盘清理无条件执行：STOP 可能发生在无内存条目的进程（agent 重启后 ENTRIES 为空）。
     *
     * @param dockerId 容器 hash
     */
    public static synchronized void removeContainer(String dockerId) {
        if (!safeDockerId(dockerId)) {
            return;
        }
        if (ENTRIES.remove(dockerId) != null) {
            log.info("readiness probe removed, dockerId={}", dockerId);
        }
        removePersisted(dockerId);
    }

    /**
     * 探测配置注册单入口（START 成功尾部调用）：路径分发内聚于本类——label 载荷非空走声明式
     * 条目（内存，随 inventory 收敛，衔接启动期密集探测），否则走 taskData 载荷（缓存并落盘，
     * 重启恢复；空载荷=无探测部署，顺带清理同 id 旧落盘条目）。调用方只转发载荷，不感知路径。
     *
     * @param dockerId     Docker 容器 ID
     * @param containerId  ops 台账容器ID
     * @param labelJson    label 路径载荷（tcp/http 无 secret 终值 DSL）
     * @param taskDataJson taskData 路径载荷（exec/敏感终值 DSL）
     */
    public static synchronized void registerProbe(String dockerId, long containerId, String labelJson, String taskDataJson) {
        if (StringUtils.isNotBlank(labelJson)) {
            registerFromLabel(dockerId, containerId, labelJson);
        } else {
            cacheStartScript(dockerId, containerId, taskDataJson);
        }
    }

    /**
     * 主轮 inventory 对账（仅成功轮调用，失败轮传 null 跳过）：条目不在全量容器集合
     * = 容器已被删除（含绕过任务通道的手工 docker rm），移除缓存并清理落盘。
     * null 不得当作"全部消失"——agent 刚启动 {@link #restoreFromDisk} 后首轮 inventory
     * 尚未成功时误清会丢失全部恢复条目。
     *
     * @param presentIds 本轮全量容器 ID 集合（null=本轮 inventory 失败，不清理）
     */
    public static synchronized void retainPresent(Collection<String> presentIds) {
        if (presentIds == null) {
            return;
        }
        for (Iterator<Map.Entry<String, ProbeEntry>> it = ENTRIES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ProbeEntry> cached = it.next();
            if (presentIds.contains(cached.getKey())) {
                continue;
            }
            it.remove();
            removePersisted(cached.getKey());
            log.info("readiness probe entry dropped, container gone, containerId={}, dockerId={}",
                    cached.getValue().containerId, cached.getKey());
        }
    }

    /**
     * 启动时从落盘目录重建 taskData 路径条目（label 条目无需恢复，首轮 inventory 自然重建；
     * 容器已消失的条目由首轮 inventory 对账清理）。仅恢复名称与 dockerId 匹配的普通 JSON 文件。
     */
    public static synchronized void restoreFromDisk() {
        File[] files = PROBE_STORE_DIR.toFile().listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        int restored = 0;
        for (File file : files) {
            if (!file.getName().endsWith(".json") || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                ProbeScriptEntry stored = JsonUtils.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), ProbeScriptEntry.class);
                if (stored != null && safeDockerId(stored.getDockerId()) && StringUtils.isNotBlank(stored.getScript())
                        && file.getName().equals(stored.getDockerId() + ".json")) {
                    ProbeSpec spec = parseSpec(stored.getScript());
                    if (spec != null) {
                        ENTRIES.put(stored.getDockerId(), new ProbeEntry(stored.getContainerId(), spec, false, stored.getScript()));
                        restored++;
                    } else {
                        Files.deleteIfExists(file.toPath());
                    }
                } else {
                    Files.deleteIfExists(file.toPath());
                }
            } catch (Exception e) {
                log.warn("readiness probe restore skipped, file={}, err={}", file.getName(), e.getClass().getSimpleName());
            }
        }
        log.info("readiness probe restored {} entries from disk (gone containers will be swept on first inventory round).", restored);
    }

    /**
     * 条目落盘（JSON：dockerId/containerId/DSL JSON；原子写 {@link SecretStore#writeAtomic}），
     * 失败降级内存态（WARN）。
     */
    private static void persistEntry(String dockerId, long containerId, String dslJson) {
        boolean persisted;
        try {
            persisted = SecretStore.writeAtomic(PROBE_STORE_DIR.resolve(dockerId + ".json"),
                    JsonUtils.toBytes(new ProbeScriptEntry(dockerId, containerId, dslJson)));
        } catch (Exception e) {
            log.warn("readiness probe persist failed (memory-only degradation), dockerId={}, err={}", dockerId, e.getClass().getSimpleName());
            return;
        }
        if (!persisted) {
            log.warn("readiness probe persist failed (memory-only degradation), dockerId={}", dockerId);
        }
    }

    /**
     * Docker ID 仅允许安全路径片段（字符集同 {@link DockerInfoHelper#isSafeIdToken}，首字符字母数字）。
     */
    private static boolean safeDockerId(String dockerId) {
        return dockerId != null && !dockerId.isEmpty() && Character.isLetterOrDigit(dockerId.charAt(0))
                && DockerInfoHelper.isSafeIdToken(dockerId);
    }

    /** 清理持久化条目；调用方持有类锁。 */
    private static void removePersisted(String dockerId) {
        try {
            Files.deleteIfExists(PROBE_STORE_DIR.resolve(dockerId + ".json"));
        } catch (Exception e) {
            log.warn("readiness probe persist remove failed, dockerId={}, err={}", dockerId, e.getClass().getSimpleName());
        }
    }

    //==================== 探测执行 ====================

    /**
     * 单轮探测（5 秒 tick 调度，fixedDelay 防重入）：锁内快照到期条目 → 锁外执行
     * （tcp/http 毫秒级；exec 3s 超时不阻塞组包）→ 锁内回写聚合（条目已消失则丢弃）。
     */
    public static void probeOnce() {
        List<Map.Entry<String, ProbeEntry>> due;
        synchronized (ReadinessProber.class) {
            if (ENTRIES.isEmpty()) {
                return;
            }
            long nowNanos = System.nanoTime();
            due = new ArrayList<>(ENTRIES.size());
            for (Map.Entry<String, ProbeEntry> cached : ENTRIES.entrySet()) {
                if (nowNanos - cached.getValue().nextProbeNanos >= 0) {
                    due.add(cached);
                }
            }
            if (due.isEmpty()) {
                return;
            }
        }
        for (Map.Entry<String, ProbeEntry> cached : due) {
            String dockerId = cached.getKey();
            ProbeEntry entry = cached.getValue();
            String reason;
            try {
                reason = probeExecutor.run(entry.spec);
            } catch (Throwable e) {
                reason = "probe-error";
                log.warn("readiness probe executor failed, containerId={}, err={}", entry.containerId, e.getClass().getSimpleName());
            }
            long wallClock = SystemClock.now();
            long interval;
            synchronized (ReadinessProber.class) {
                ProbeEntry current = ENTRIES.get(dockerId);
                if (current != entry) {
                    //执行期间条目被替换（重新部署换配置）或移除：结果丢弃。
                    continue;
                }
                entry.seq++;
                entry.lastProbeWallClock = wallClock;
                if (reason == null) {
                    if (entry.lastState != STATE_SUCCESS) {
                        log.info("readiness probe state change, containerId={}, {} -> {}",
                                entry.containerId, entry.lastState, STATE_SUCCESS);
                    }
                    entry.lastState = STATE_SUCCESS;
                    entry.probeFailCount = 0;
                    if (entry.streakStartNanos == 0) {
                        entry.streakStartNanos = System.nanoTime();
                        entry.streakMillis = 0;
                    } else {
                        entry.streakMillis = Math.max(0, (System.nanoTime() - entry.streakStartNanos) / 1_000_000L);
                    }
                    entry.everSucceeded = true;
                } else {
                    if (entry.lastState != STATE_FAILED) {
                        log.info("readiness probe state change, containerId={}, {} -> {}, reason={}",
                                entry.containerId, entry.lastState, STATE_FAILED, reason);
                    } else {
                        log.debug("readiness probe still failing, containerId={}, fails={}, reason={}",
                                entry.containerId, entry.probeFailCount + 1, reason);
                    }
                    entry.lastState = STATE_FAILED;
                    entry.probeFailCount++;
                    entry.lastReason = reason;
                    entry.streakStartNanos = 0;
                    entry.streakMillis = 0;
                }
                interval = entry.everSucceeded ? STEADY_INTERVAL_MILLIS : STARTUP_INTERVAL_MILLIS;
                entry.nextProbeNanos = System.nanoTime() + interval * 1_000_000L;
            }
        }
    }

    //==================== 主轮组包取数 ====================

    /**
     * 主轮组包（ContainerInfo 就地填充）：不在探测集合时全部字段保持 null（序列化省略，
     * Center 不动台账就绪字段）；在集合时填充完整摘要（seq 为 Center 侧乱序/重放屏障）。
     *
     * @param container 容器视图（id=dockerId）
     */
    public static void applyResult(ContainerInfo container) {
        ProbeEntry entry;
        synchronized (ReadinessProber.class) {
            entry = container.getId() == null ? null : ENTRIES.get(container.getId());
            //seq=0（条目存在但尚未产出首个样本）：上报省略全部字段——与不在集合同形，
            //Center 侧关门语义一致，避免携带无意义水位。
            if (entry == null || entry.seq == 0) {
                return;
            }
            //锁内快照：seq 与聚合字段一致发布。
            container.setProbeSeq(entry.seq);
            container.setProbeState(entry.lastState);
            container.setProbeDate(entry.lastProbeWallClock > 0 ? entry.lastProbeWallClock : null);
            container.setProbeFails(entry.probeFailCount);
            container.setProbeStreak(entry.lastState == STATE_SUCCESS ? entry.streakMillis : 0L);
            container.setProbeReason(entry.lastReason);
        }
    }

    /**
     * 缓存规模（测试断言用）。
     *
     * @return 探测条目数
     */
    public static synchronized int size() {
        return ENTRIES.size();
    }

    /**
     * 清空缓存（测试隔离用）。
     */
    static synchronized void clearForTest() {
        ENTRIES.clear();
    }

    /**
     * 全部条目置为到期（测试用：跳过启动期/稳态间隔等待，连续探测可行）。
     */
    static synchronized void expireForTest() {
        for (ProbeEntry entry : ENTRIES.values()) {
            entry.nextProbeNanos = 0;
        }
    }

    //==================== DSL 解析与执行器 ====================

    /**
     * 终值 DSL 解析（多探测并存形态）：JSON 对象绑定 {@link ProbeSpec} 且
     * tcpProbes/httpProbes/cmdProbes 任一非空即接受；空/非 JSON/损坏输入返回 null（调用方决定跳过）。
     */
    static ProbeSpec parseSpec(String json) {
        if (StringUtils.isBlank(json) || !json.trim().startsWith("{")) {
            return null;
        }
        try {
            ProbeSpec spec = JsonUtils.parse(json, ProbeSpec.class);
            if (spec != null && spec.probeCount() > 0) {
                return spec;
            }
        } catch (Exception e) {
            log.warn("readiness probe spec parse failed, err={}", e.getClass().getSimpleName());
        }
        return null;
    }

    /** 受管 label（uw.ops.container-id）解析为台账 ID。 */
    private static Long parseContainerIdLabel(Map<String, String> labels) {
        String value = labels == null ? null : labels.get(StructuredTaskExecutor.LABEL_CONTAINER_ID);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 原生执行分派：遍历三组探测全部执行，任一失败即返回其短码（AND 语义——全部通过才算
     * 就绪；包私有：测试直连真实执行器组）。
     */
    static String runNative(ProbeSpec spec) {
        if (spec.getTcpProbes() != null) {
            for (ProbeSpec.TcpProbe probe : spec.getTcpProbes()) {
                String reason = executeTcp(probe);
                if (reason != null) {
                    return reason;
                }
            }
        }
        if (spec.getHttpProbes() != null) {
            for (ProbeSpec.HttpProbe probe : spec.getHttpProbes()) {
                String reason = executeHttp(probe);
                if (reason != null) {
                    return reason;
                }
            }
        }
        if (spec.getCmdProbes() != null) {
            for (ProbeSpec.CmdProbe probe : spec.getCmdProbes()) {
                String reason = executeCmd(probe);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    /**
     * 探测组摘要（注册日志用）：tcp=N/http=M/cmd=K。
     */
    private static String probeSummary(ProbeSpec spec) {
        int tcp = spec.getTcpProbes() == null ? 0 : spec.getTcpProbes().size();
        int http = spec.getHttpProbes() == null ? 0 : spec.getHttpProbes().size();
        int cmd = spec.getCmdProbes() == null ? 0 : spec.getCmdProbes().size();
        return "tcp=" + tcp + "/http=" + http + "/cmd=" + cmd;
    }

    /**
     * tcp 探测：Socket connect（零进程）。null=成功；refused/timeout/error 短码=失败。
     */
    private static String executeTcp(ProbeSpec.TcpProbe spec) {
        String host = StringUtils.isBlank(spec.getHost()) ? "127.0.0.1" : spec.getHost();
        int timeout = spec.getTimeoutMs() != null ? spec.getTimeoutMs() : TCP_DEFAULT_TIMEOUT_MILLIS;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, spec.getPort()), timeout);
            return null;
        } catch (java.net.ConnectException e) {
            return "tcp-refused";
        } catch (java.net.SocketTimeoutException e) {
            return "tcp-timeout";
        } catch (IOException e) {
            return "tcp-error";
        }
    }

    /**
     * http 探测：HttpURLConnection（零进程，直连不走代理）。null=成功（状态码命中 expect
     * 默认 200，且响应体含 expectBody 任一子串）；http-status-N/body-miss/refused/timeout/
     * error 短码=失败。
     */
    private static String executeHttp(ProbeSpec.HttpProbe spec) {
        int timeout = spec.getTimeoutMs() != null ? spec.getTimeoutMs() : HTTP_DEFAULT_TIMEOUT_MILLIS;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(spec.getUrl()).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            if (spec.getHeaders() != null) {
                for (Map.Entry<String, String> header : spec.getHeaders().entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            int code = connection.getResponseCode();
            List<Integer> expectCode = spec.getExpectCode();
            boolean statusOk = expectCode != null && !expectCode.isEmpty() ? expectCode.contains(code) : code >= 200 && code < 300;
            if (!statusOk) {
                return shorten("http-status-" + code);
            }
            List<String> expectBody = spec.getExpectBody();
            if (expectBody != null && !expectBody.isEmpty()) {
                String body = readBody(connection);
                if (body == null) {
                    return "http-error";
                }
                boolean bodyHit = false;
                for (String keyword : expectBody) {
                    if (body.contains(keyword)) {
                        bodyHit = true;
                        break;
                    }
                }
                if (!bodyHit) {
                    return "http-body-miss";
                }
            }
            return null;
        } catch (java.net.ConnectException e) {
            return "http-refused";
        } catch (java.net.SocketTimeoutException e) {
            return "http-timeout";
        } catch (IOException e) {
            return "http-error";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 响应体读取（expectBody 判定用；健康端点响应小，超限截断足够判定）。
     */
    private static String readBody(HttpURLConnection connection) {
        try (java.io.InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) >= 0 && text.length() < HTTP_BODY_READ_LIMIT) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return text.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * cmd 探测：/bin/sh -c 脚本（默认 3s 超时）。退出码 0 且（可选）stdout 含 expectOutput
     * 任一子串才算成功——输出仅内存判定子串，不落日志不上报（输出可能携带令牌的安全边界保留）。
     * cmd-exit-N=非零退出；cmd-output-miss=码 0 但输出未命中；cmd-timeout/cmd-error=超时/IO 异常。
     */
    private static String executeCmd(ProbeSpec.CmdProbe spec) {
        int timeout = spec.getTimeoutMs() != null ? spec.getTimeoutMs() : CMD_DEFAULT_TIMEOUT_MILLIS;
        try {
            List<String> output = ShellCmdUtils.runNative(new String[]{"/bin/sh", "-c", spec.getScript()}, null, true, timeout);
            List<String> expectOutput = spec.getExpectOutput();
            if (expectOutput != null && !expectOutput.isEmpty()) {
                String text = String.join("\n", output);
                boolean hit = false;
                for (String keyword : expectOutput) {
                    if (text.contains(keyword)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return "cmd-output-miss";
                }
            }
            return null;
        } catch (ShellCmdUtils.CommandException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("timed out")) {
                return "cmd-timeout";
            }
            java.util.regex.Matcher matcher = EXEC_EXIT_CODE.matcher(message);
            if (matcher.find()) {
                return shorten("cmd-exit-" + matcher.group(1));
            }
            return "cmd-error";
        }
    }

    /** 失败原因短码截断（协议约束 ≤32 字符）。 */
    private static String shorten(String reason) {
        return reason.length() <= REASON_LIMIT ? reason : reason.substring(0, REASON_LIMIT);
    }

}
