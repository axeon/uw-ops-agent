package uw.ops.agent.helper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.common.util.JsonUtils;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.vo.sub.ContainerInfo;
import uw.ops.agent.vo.ProbeSpec;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ReadinessProber 单测（DSL 摘要模型）：双路径注册（label 声明式 / taskData 落盘）、
 * inventory 收敛（label 变化/消失/新增与全集对账）、聚合摘要（seq/fails/streak/reason）、
 * 启动期→稳态节奏切换、DSL 解析（JSON/legacy shell）与真实执行器
 * （tcp=本地 ServerSocket、http=本地 HttpServer、exec=/bin/sh 退出码）。
 */
class ReadinessProberTest {

    private static final String LABEL_KEY = StructuredTaskExecutor.LABEL_PROBE;

    @BeforeEach
    void cleanDisk() throws Exception {
        Path dir = Path.of(Constants.getOpsAgentProbeDir());
        Assertions.assertTrue(dir.toString().contains("target/test-agent"));
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        ReadinessProber.clearForTest();
    }

    @AfterEach
    void reset() {
        ReadinessProber.clearForTest();
        ReadinessProber.probeExecutor = spec -> null;
    }

    private static void probeOnceSuccess() {
        ReadinessProber.probeExecutor = spec -> null;
    }

    private static void probeOnceFailing(String reason) {
        ReadinessProber.probeExecutor = spec -> reason;
    }

    /** taskData 路径注册 + 落盘 + restore（含旧格式兼容与坏文件清理）。 */
    @Test
    void taskDataPathPersistsAndRestoresWithLegacyCompat() throws Exception {
        Path dir = Path.of(Constants.getOpsAgentProbeDir());
        //新格式：DSL JSON 落盘。
        ReadinessProber.cacheStartScript("persisted-hash", 501, "{\"type\":\"tcp\",\"port\":8080}");
        //旧格式：纯 shell 文本落盘 → restore 按 exec 包装。
        Files.writeString(dir.resolve("legacy-hash.json"),
                "{\"dockerId\":\"legacy-hash\",\"containerId\":502,\"script\":\"exit 0\"}");
        //坏条目：非 JSON script 且非法。
        Files.writeString(dir.resolve("broken.json"), "{broken");
        ReadinessProber.clearForTest();
        ReadinessProber.restoreFromDisk();
        Assertions.assertEquals(2, ReadinessProber.size());
        ContainerInfo legacy = new ContainerInfo();
        legacy.setId("legacy-hash");
        ReadinessProber.applyResult(legacy);
        //seq=0（恢复但尚未产出首样本）：上报省略全部字段（与不在集合同形）。
        Assertions.assertNull(legacy.getProbeState());
        Assertions.assertNull(legacy.getProbeSeq());
    }

    @Test
    void blankAndUnparsableTaskDataNotCached() {
        ReadinessProber.cacheStartScript("hash-a", 101, " ");
        ReadinessProber.cacheStartScript("hash-a", 101, "{\"type\":\"grpc\",\"port\":1}");
        ReadinessProber.cacheStartScript(null, 105, "{\"type\":\"tcp\",\"port\":80}");
        Assertions.assertEquals(0, ReadinessProber.size());
        ReadinessProber.cacheStartScript("hash-e", 105, "{\"type\":\"tcp\",\"port\":80}");
        ReadinessProber.removeContainer("hash-e");
        ReadinessProber.removeContainer("hash-e");
        Assertions.assertEquals(0, ReadinessProber.size());
    }

    @Test
    void containerIdCannotEscapePersistenceDirectory() throws Exception {
        Path external = Path.of(Constants.getOpsAgentProbeDir()).getParent().resolve("escape.json");
        Files.deleteIfExists(external);
        ReadinessProber.cacheStartScript("../escape", 504, "{\"type\":\"tcp\",\"port\":80}");
        Assertions.assertFalse(Files.exists(external));
        Assertions.assertEquals(0, ReadinessProber.size());
    }

    /** label 声明式收敛：新增注册 / 值变化重建 / label 消失移除；taskData 条目不受 label 驱动。 */
    @Test
    void inventoryLabelReconcile() {
        Map<String, Map<String, String>> inventory = new HashMap<>();
        inventory.put("lbl-1", labels(11, "{\"type\":\"tcp\",\"port\":8080}"));
        //未携带受管 container-id label 的容器跳过。
        inventory.put("foreign", Map.of(LABEL_KEY, "{\"type\":\"tcp\",\"port\":9090}"));
        ReadinessProber.applyInventoryLabels(inventory, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());

        //label 值变化 → 条目重建（聚合计数归零）。
        probeOnceSuccess();
        ReadinessProber.probeOnce();
        Map<String, Map<String, String>> changed = new HashMap<>();
        changed.put("lbl-1", labels(11, "{\"type\":\"http\",\"url\":\"http://127.0.0.1:8080/h\"}"));
        ReadinessProber.applyInventoryLabels(changed, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
        ContainerInfo view = new ContainerInfo();
        view.setId("lbl-1");
        ReadinessProber.applyResult(view);
        Assertions.assertNull(view.getProbeSeq(), "rebuilt entry resets aggregation");

        //label 消失 → label 条目移除；taskData 条目保留（落盘条目不受 label 驱动）。
        ReadinessProber.cacheStartScript("exec-hash", 12, "{\"type\":\"exec\",\"script\":\"exit 0\"}");
        ReadinessProber.applyInventoryLabels(new HashMap<>(), LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
        //null（inventory 失败轮）不动任何条目。
        ReadinessProber.applyInventoryLabels(null, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
    }

    /** 全集对账：容器消失 → 缓存与落盘同删（label 与 taskData 条目 alike）。 */
    @Test
    void retainPresentSweepsGoneContainersAndDisk() throws Exception {
        ReadinessProber.cacheStartScript("td-hash", 102, "{\"type\":\"exec\",\"script\":\"exit 0\"}");
        ReadinessProber.registerFromLabel("lbl-hash", 103, "{\"type\":\"tcp\",\"port\":8080}");
        ReadinessProber.retainPresent(Set.of("other"));
        Assertions.assertEquals(0, ReadinessProber.size());
        ReadinessProber.clearForTest();
        ReadinessProber.restoreFromDisk();
        Assertions.assertEquals(0, ReadinessProber.size(), "disk entries must be swept too");
        //null=inventory 失败轮不清理。
        ReadinessProber.cacheStartScript("td-2", 104, "{\"type\":\"exec\",\"script\":\"exit 0\"}");
        ReadinessProber.retainPresent(null);
        Assertions.assertEquals(1, ReadinessProber.size());
    }

    /** 聚合摘要：seq 递增、失败计数与原因、成功 streak 与清零、失败后 streak 归零。 */
    @Test
    void aggregationSummaryTransitions() {
        ReadinessProber.registerFromLabel("agg", 201, "{\"type\":\"tcp\",\"port\":8080}");
        ReadinessProber.probeExecutor = spec -> "tcp-refused";
        ReadinessProber.probeOnce();
        ContainerInfo fail1 = summaryOf("agg");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, fail1.getProbeState());
        Assertions.assertEquals(1, fail1.getProbeSeq());
        Assertions.assertEquals(1, fail1.getProbeFails());
        Assertions.assertEquals("tcp-refused", fail1.getProbeReason());
        Assertions.assertEquals(0L, fail1.getProbeStreak());

        ReadinessProber.expireForTest();
        ReadinessProber.probeOnce();
        ContainerInfo fail2 = summaryOf("agg");
        Assertions.assertEquals(2, fail2.getProbeFails());
        Assertions.assertEquals(2, fail2.getProbeSeq());

        //成功：fails 清零、streak 起步（首个成功样本 streak=0）、reason 保留供排障。
        probeOnceSuccess();
        ReadinessProber.expireForTest();
        ReadinessProber.probeOnce();
        ContainerInfo ok1 = summaryOf("agg");
        Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, ok1.getProbeState());
        Assertions.assertEquals(0, ok1.getProbeFails());
        Assertions.assertEquals(0L, ok1.getProbeStreak());
        Assertions.assertEquals("tcp-refused", ok1.getProbeReason(), "last reason kept for debugging");
    }

    /** 节奏：到期才探测（未到期轮空转），启动期→稳态切换由 everSucceeded 驱动。 */
    @Test
    void scheduleAdvancesOnlyDueEntries() {
        ReadinessProber.registerFromLabel("tick", 202, "{\"type\":\"tcp\",\"port\":8080}");
        probeOnceSuccess();
        ReadinessProber.probeOnce();
        Assertions.assertEquals(1L, summaryOf("tick").getProbeSeq());
        //未到期：空转不探测（seq 不变）。
        ReadinessProber.probeOnce();
        Assertions.assertEquals(1L, summaryOf("tick").getProbeSeq());
    }

    /** 执行期间条目被移除：结果丢弃（无残留副作用）。 */
    @Test
    void resultDroppedWhenEntryRemovedDuringExecution() {
        ReadinessProber.registerFromLabel("gone", 203, "{\"type\":\"tcp\",\"port\":8080}");
        ReadinessProber.probeExecutor = spec -> {
            ReadinessProber.removeContainer("gone");
            return null;
        };
        ReadinessProber.probeOnce();
        Assertions.assertEquals(0, ReadinessProber.size());
        ContainerInfo view = new ContainerInfo();
        view.setId("gone");
        ReadinessProber.applyResult(view);
        Assertions.assertNull(view.getProbeSeq());
    }

    /** 真实 tcp 执行器：本地端口连通=成功、拒绝=tcp-refused。 */
    @Test
    void realTcpExecutor() throws Exception {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        try (ServerSocket server = new ServerSocket(0)) {
            ReadinessProber.registerFromLabel("tcp-ok", 301, "{\"type\":\"tcp\",\"port\":" + server.getLocalPort() + "}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("tcp-ok").getProbeState());
        }
        ReadinessProber.registerFromLabel("tcp-bad", 302, "{\"type\":\"tcp\",\"port\":1}");
        ReadinessProber.probeOnce();
        ContainerInfo bad = summaryOf("tcp-bad");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, bad.getProbeState());
        Assertions.assertEquals("tcp-refused", bad.getProbeReason());
    }

    /** 真实 http 执行器：期望码命中=成功、404=http-status-404。 */
    @Test
    void realHttpExecutor() throws Exception {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> exchange.sendResponseHeaders(200, -1));
        server.start();
        try {
            int port = server.getAddress().getPort();
            ReadinessProber.registerFromLabel("http-ok", 303,
                    "{\"type\":\"http\",\"url\":\"http://127.0.0.1:" + port + "/health\"}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("http-ok").getProbeState());
            ReadinessProber.registerFromLabel("http-404", 304,
                    "{\"type\":\"http\",\"url\":\"http://127.0.0.1:" + port + "/missing\"}");
            ReadinessProber.probeOnce();
            ContainerInfo miss = summaryOf("http-404");
            Assertions.assertEquals(ReadinessProber.STATE_FAILED, miss.getProbeState());
            Assertions.assertEquals("http-status-404", miss.getProbeReason());
        } finally {
            server.stop(0);
        }
    }

    /** 真实 exec 执行器：退出码 0/非 0 语义 + reason 短码。 */
    @Test
    void realExecExecutor() {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        ReadinessProber.registerFromLabel("exec-ok", 305, "{\"type\":\"exec\",\"script\":\"exit 0\"}");
        ReadinessProber.registerFromLabel("exec-bad", 306, "{\"type\":\"exec\",\"script\":\"exit 3\"}");
        ReadinessProber.registerFromLabel("exec-legacy", 307, "exit 0");
        ReadinessProber.probeOnce();
        Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("exec-ok").getProbeState());
        Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("exec-legacy").getProbeState());
        ContainerInfo bad = summaryOf("exec-bad");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, bad.getProbeState());
        Assertions.assertEquals("exec-exit-3", bad.getProbeReason());
    }

    /** 协议字段：探测摘要按 camelCase 字段名序列化（null 省略、非 null 输出）。 */
    @Test
    void containerInfoProtocolFields() {
        ContainerInfo info = new ContainerInfo();
        String jsonWithout = JsonUtils.toString(info);
        Assertions.assertFalse(jsonWithout.contains("probeState"));
        info.setProbeState(1);
        info.setProbeSeq(42L);
        info.setProbeDate(1700000000000L);
        info.setProbeFails(0);
        info.setProbeStreak(90000L);
        info.setProbeReason("tcp-refused");
        String jsonWith = JsonUtils.toString(info);
        Assertions.assertTrue(jsonWith.contains("\"probeState\":1"));
        Assertions.assertTrue(jsonWith.contains("\"probeSeq\":42"));
        Assertions.assertTrue(jsonWith.contains("\"probeDate\":1700000000000"));
        Assertions.assertTrue(jsonWith.contains("\"probeFails\":0"));
        Assertions.assertTrue(jsonWith.contains("\"probeStreak\":90000"));
        Assertions.assertTrue(jsonWith.contains("\"probeReason\":\"tcp-refused\""));
    }

    /** DSL 解析：JSON 三型合法、未知类型/损坏 JSON 拒绝、legacy 文本包装 exec。 */
    @Test
    void parseSpecVariants() {
        ProbeSpec tcp = ReadinessProber.parseSpec("{\"type\":\"tcp\",\"port\":8080,\"timeoutMs\":9999}");
        Assertions.assertEquals("tcp", tcp.getType());
        Assertions.assertEquals(8080, tcp.getPort());
        ProbeSpec legacy = ReadinessProber.parseSpec("curl -sf http://127.0.0.1:8080 || exit 1");
        Assertions.assertEquals("exec", legacy.getType());
        Assertions.assertNull(ReadinessProber.parseSpec("{\"type\":\"grpc\"}"));
        Assertions.assertNull(ReadinessProber.parseSpec("  "));
    }

    private static Map<String, String> labels(long containerId, String probeJson) {
        Map<String, String> labels = new HashMap<>();
        labels.put(StructuredTaskExecutor.LABEL_CONTAINER_ID, String.valueOf(containerId));
        labels.put(LABEL_KEY, probeJson);
        return labels;
    }

    private static ContainerInfo summaryOf(String dockerId) {
        ContainerInfo view = new ContainerInfo();
        view.setId(dockerId);
        ReadinessProber.applyResult(view);
        return view;
    }
}
