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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ReadinessProber 单测（DSL 摘要模型）：双路径注册（label 声明式 / taskData 落盘）、
 * inventory 收敛（label 变化/消失/新增与全集对账）、聚合摘要（seq/fails/streak/reason）、
 * 启动期→稳态节奏切换、多探测并存 AND 语义、DSL 解析与真实执行器
 * （tcp=本地 ServerSocket、http=本地 HttpServer、cmd=/bin/sh 退出码+输出子串）。
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

    /** taskData 路径注册 + 落盘 + restore（坏文件清理）。 */
    @Test
    void taskDataPathPersistsAndRestores() throws Exception {
        Path dir = Path.of(Constants.getOpsAgentProbeDir());
        //多探测 DSL JSON 落盘。
        ReadinessProber.cacheStartScript("persisted-hash", 501, "{\"tcpProbes\":[{\"port\":8080}]}");
        //坏条目：非 JSON script 且非法。
        Files.writeString(dir.resolve("broken.json"), "{broken");
        ReadinessProber.clearForTest();
        ReadinessProber.restoreFromDisk();
        Assertions.assertEquals(1, ReadinessProber.size());
        ContainerInfo persisted = new ContainerInfo();
        persisted.setId("persisted-hash");
        ReadinessProber.applyResult(persisted);
        //seq=0（恢复但尚未产出首样本）：上报省略全部字段（与不在集合同形）。
        Assertions.assertNull(persisted.getProbeState());
        Assertions.assertNull(persisted.getProbeSeq());
    }

    @Test
    void blankAndUnparsableTaskDataNotCached() {
        ReadinessProber.cacheStartScript("hash-a", 101, " ");
        ReadinessProber.cacheStartScript("hash-a", 101, "{\"unknownProbe\":[{}]}");
        ReadinessProber.cacheStartScript("hash-b", 101, "{}");
        ReadinessProber.cacheStartScript(null, 105, "{\"tcpProbes\":[{\"port\":80}]}");
        Assertions.assertEquals(0, ReadinessProber.size());
        ReadinessProber.cacheStartScript("hash-e", 105, "{\"tcpProbes\":[{\"port\":80}]}");
        ReadinessProber.removeContainer("hash-e");
        ReadinessProber.removeContainer("hash-e");
        Assertions.assertEquals(0, ReadinessProber.size());
    }

    @Test
    void containerIdCannotEscapePersistenceDirectory() throws Exception {
        Path external = Path.of(Constants.getOpsAgentProbeDir()).getParent().resolve("escape.json");
        Files.deleteIfExists(external);
        ReadinessProber.cacheStartScript("../escape", 504, "{\"tcpProbes\":[{\"port\":80}]}");
        Assertions.assertFalse(Files.exists(external));
        Assertions.assertEquals(0, ReadinessProber.size());
    }

    /** label 声明式收敛：新增注册 / 值变化重建 / label 消失移除；taskData 条目不受 label 驱动。 */
    @Test
    void inventoryLabelReconcile() {
        Map<String, Map<String, String>> inventory = new HashMap<>();
        inventory.put("lbl-1", labels(11, "{\"tcpProbes\":[{\"port\":8080}]}"));
        //未携带受管 container-id label 的容器跳过。
        inventory.put("foreign", Map.of(LABEL_KEY, "{\"tcpProbes\":[{\"port\":9090}]}"));
        ReadinessProber.applyInventoryLabels(inventory, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());

        //label 值变化 → 条目重建（聚合计数归零）。
        probeOnceSuccess();
        ReadinessProber.probeOnce();
        Map<String, Map<String, String>> changed = new HashMap<>();
        changed.put("lbl-1", labels(11, "{\"httpProbes\":[{\"url\":\"http://127.0.0.1:8080/h\"}]}"));
        ReadinessProber.applyInventoryLabels(changed, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
        ContainerInfo view = new ContainerInfo();
        view.setId("lbl-1");
        ReadinessProber.applyResult(view);
        Assertions.assertNull(view.getProbeSeq(), "rebuilt entry resets aggregation");

        //label 消失 → label 条目移除；taskData 条目保留（落盘条目不受 label 驱动）。
        ReadinessProber.cacheStartScript("cmd-hash", 12, "{\"cmdProbes\":[{\"script\":\"exit 0\"}]}");
        ReadinessProber.applyInventoryLabels(new HashMap<>(), LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
        //null（inventory 失败轮）不动任何条目。
        ReadinessProber.applyInventoryLabels(null, LABEL_KEY);
        Assertions.assertEquals(1, ReadinessProber.size());
    }

    /** 全集对账：容器消失 → 缓存与落盘同删（label 与 taskData 条目 alike）。 */
    @Test
    void retainPresentSweepsGoneContainersAndDisk() throws Exception {
        ReadinessProber.cacheStartScript("td-hash", 102, "{\"cmdProbes\":[{\"script\":\"exit 0\"}]}");
        ReadinessProber.registerFromLabel("lbl-hash", 103, "{\"tcpProbes\":[{\"port\":8080}]}");
        ReadinessProber.retainPresent(Set.of("other"));
        Assertions.assertEquals(0, ReadinessProber.size());
        ReadinessProber.clearForTest();
        ReadinessProber.restoreFromDisk();
        Assertions.assertEquals(0, ReadinessProber.size(), "disk entries must be swept too");
        //null=inventory 失败轮不清理。
        ReadinessProber.cacheStartScript("td-2", 104, "{\"cmdProbes\":[{\"script\":\"exit 0\"}]}");
        ReadinessProber.retainPresent(null);
        Assertions.assertEquals(1, ReadinessProber.size());
    }

    /** 聚合摘要：seq 递增、失败计数与原因、成功 streak 与清零、失败后 streak 归零。 */
    @Test
    void aggregationSummaryTransitions() {
        ReadinessProber.registerFromLabel("agg", 201, "{\"tcpProbes\":[{\"port\":8080}]}");
        ReadinessProber.probeExecutor = spec -> "tcp-refused";
        ReadinessProber.probeOnce();
        ContainerInfo fail1 = summaryOf("agg");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, fail1.getProbeState());
        Assertions.assertEquals(1, fail1.getProbeSeq());
        Assertions.assertEquals(1, fail1.getProbeFails());
        Assertions.assertEquals("tcp-refused", fail1.getProbeReason());
        Assertions.assertEquals(0L, fail1.getProbeSuccessMs());

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
        Assertions.assertEquals(0L, ok1.getProbeSuccessMs());
        Assertions.assertEquals("tcp-refused", ok1.getProbeReason(), "last reason kept for debugging");
    }

    /** 节奏：到期才探测（未到期轮空转），启动期→稳态切换由 everSucceeded 驱动。 */
    @Test
    void scheduleAdvancesOnlyDueEntries() {
        ReadinessProber.registerFromLabel("tick", 202, "{\"tcpProbes\":[{\"port\":8080}]}");
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
        ReadinessProber.registerFromLabel("gone", 203, "{\"tcpProbes\":[{\"port\":8080}]}");
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
            ReadinessProber.registerFromLabel("tcp-ok", 301, "{\"tcpProbes\":[{\"port\":" + server.getLocalPort() + "}]}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("tcp-ok").getProbeState());
        }
        ReadinessProber.registerFromLabel("tcp-bad", 302, "{\"tcpProbes\":[{\"port\":1}]}");
        ReadinessProber.probeOnce();
        ContainerInfo bad = summaryOf("tcp-bad");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, bad.getProbeState());
        Assertions.assertEquals("tcp-refused", bad.getProbeReason());
    }

    /** 真实 http 执行器：期望码命中=成功、404=http-status-404、expectBody 子串命中/未命中。 */
    @Test
    void realHttpExecutor() throws Exception {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ReadinessProber.registerFromLabel("http-ok", 303,
                    "{\"httpProbes\":[{\"url\":\"http://127.0.0.1:" + port + "/health\"}]}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("http-ok").getProbeState());
            ReadinessProber.registerFromLabel("http-404", 304,
                    "{\"httpProbes\":[{\"url\":\"http://127.0.0.1:" + port + "/missing\"}]}");
            ReadinessProber.probeOnce();
            ContainerInfo miss = summaryOf("http-404");
            Assertions.assertEquals(ReadinessProber.STATE_FAILED, miss.getProbeState());
            Assertions.assertEquals("http-status-404", miss.getProbeReason());
            //expectBody：状态码通过后响应体须包含任一子串——命中=成功、未命中=http-body-miss。
            ReadinessProber.registerFromLabel("http-body-ok", 311,
                    "{\"httpProbes\":[{\"url\":\"http://127.0.0.1:" + port + "/health\",\"expectBody\":[\"\\\"status\\\":\\\"UP\\\"\",\"DEGRADED\"]}]}");
            ReadinessProber.registerFromLabel("http-body-miss", 312,
                    "{\"httpProbes\":[{\"url\":\"http://127.0.0.1:" + port + "/health\",\"expectBody\":[\"\\\"status\\\":\\\"DOWN\\\"\"]}]}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("http-body-ok").getProbeState());
            ContainerInfo bodyMiss = summaryOf("http-body-miss");
            Assertions.assertEquals(ReadinessProber.STATE_FAILED, bodyMiss.getProbeState());
            Assertions.assertEquals("http-body-miss", bodyMiss.getProbeReason());
        } finally {
            server.stop(0);
        }
    }

    /** 真实 cmd 执行器：退出码 0/非 0 语义、expectOutput 输出子串命中/未命中 + reason 短码。 */
    @Test
    void realCmdExecutor() {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        ReadinessProber.registerFromLabel("cmd-ok", 305, "{\"cmdProbes\":[{\"script\":\"exit 0\"}]}");
        ReadinessProber.registerFromLabel("cmd-bad", 306, "{\"cmdProbes\":[{\"script\":\"exit 3\"}]}");
        ReadinessProber.registerFromLabel("cmd-out-ok", 313,
                "{\"cmdProbes\":[{\"script\":\"echo status=UP\",\"expectOutput\":[\"status=UP\",\"status=DEGRADED\"]}]}");
        ReadinessProber.registerFromLabel("cmd-out-miss", 314,
                "{\"cmdProbes\":[{\"script\":\"echo status=DOWN\",\"expectOutput\":[\"status=UP\"]}]}");
        ReadinessProber.probeOnce();
        Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("cmd-ok").getProbeState());
        Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("cmd-out-ok").getProbeState());
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, summaryOf("cmd-out-miss").getProbeState());
        Assertions.assertEquals("cmd-output-miss", summaryOf("cmd-out-miss").getProbeReason());
        ContainerInfo bad = summaryOf("cmd-bad");
        Assertions.assertEquals(ReadinessProber.STATE_FAILED, bad.getProbeState());
        Assertions.assertEquals("cmd-exit-3", bad.getProbeReason());
    }

    /** 多探测并存（AND 语义）：tcp+http+cmd 全过=成功；任一失败=失败（短码取首个失败探测）。 */
    @Test
    void multiProbeAllMustPass() throws Exception {
        ReadinessProber.probeExecutor = ReadinessProber::runNative;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> exchange.sendResponseHeaders(200, -1));
        server.start();
        try {
            int port = server.getAddress().getPort();
            //三组并存全过。
            ReadinessProber.registerFromLabel("all-pass", 308, "{\"tcpProbes\":[{\"port\":" + port + "}],"
                    + "\"httpProbes\":[{\"url\":\"http://127.0.0.1:" + port + "/health\"}],"
                    + "\"cmdProbes\":[{\"script\":\"exit 0\"}]}");
            ReadinessProber.probeOnce();
            Assertions.assertEquals(ReadinessProber.STATE_SUCCESS, summaryOf("all-pass").getProbeState());
            //tcp 组首条失败即短路：失败短码=首个失败探测。
            ReadinessProber.registerFromLabel("tcp-fail-first", 309, "{\"tcpProbes\":[{\"port\":1},{\"port\":2}],"
                    + "\"cmdProbes\":[{\"script\":\"exit 3\"}]}");
            ReadinessProber.probeOnce();
            ContainerInfo tcpFail = summaryOf("tcp-fail-first");
            Assertions.assertEquals(ReadinessProber.STATE_FAILED, tcpFail.getProbeState());
            Assertions.assertEquals("tcp-refused", tcpFail.getProbeReason());
            //tcp 过但 cmd 失败：短码=cmd-exit-N。
            ReadinessProber.registerFromLabel("cmd-fail-late", 310, "{\"tcpProbes\":[{\"port\":" + port + "}],"
                    + "\"cmdProbes\":[{\"script\":\"exit 5\"}]}");
            ReadinessProber.probeOnce();
            ContainerInfo cmdFail = summaryOf("cmd-fail-late");
            Assertions.assertEquals(ReadinessProber.STATE_FAILED, cmdFail.getProbeState());
            Assertions.assertEquals("cmd-exit-5", cmdFail.getProbeReason());
        } finally {
            server.stop(0);
        }
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
        info.setProbeSuccessMs(90000L);
        info.setProbeReason("tcp-refused");
        String jsonWith = JsonUtils.toString(info);
        Assertions.assertTrue(jsonWith.contains("\"probeState\":1"));
        Assertions.assertTrue(jsonWith.contains("\"probeSeq\":42"));
        Assertions.assertTrue(jsonWith.contains("\"probeDate\":1700000000000"));
        Assertions.assertTrue(jsonWith.contains("\"probeFails\":0"));
        Assertions.assertTrue(jsonWith.contains("\"probeSuccessMs\":90000"));
        Assertions.assertTrue(jsonWith.contains("\"probeReason\":\"tcp-refused\""));
    }

    /** DSL 解析：三组数组形态合法、expectOutput 归位、空/非 JSON/未知键拒绝。 */
    @Test
    void parseSpecVariants() {
        //三组并存，逐字段归位。
        ProbeSpec multi = ReadinessProber.parseSpec("{\"tcpProbes\":[{\"port\":8080,\"timeoutMs\":9999}],"
                + "\"httpProbes\":[{\"url\":\"http://127.0.0.1:8080/h\",\"expectCode\":[200]}],"
                + "\"cmdProbes\":[{\"script\":\"exit 0\",\"expectOutput\":[\"UP\"]}]}");
        Assertions.assertEquals(3, multi.probeCount());
        Assertions.assertEquals(8080, multi.getTcpProbes().get(0).getPort());
        Assertions.assertEquals(9999, multi.getTcpProbes().get(0).getTimeoutMs());
        Assertions.assertEquals("http://127.0.0.1:8080/h", multi.getHttpProbes().get(0).getUrl());
        Assertions.assertEquals(200, multi.getHttpProbes().get(0).getExpectCode().get(0));
        Assertions.assertEquals("UP", multi.getCmdProbes().get(0).getExpectOutput().get(0));
        //拒绝：空对象/未知键全空/纯文本/空文本。
        Assertions.assertNull(ReadinessProber.parseSpec("{}"));
        Assertions.assertNull(ReadinessProber.parseSpec("{\"unknownProbes\":[{}]}"));
        Assertions.assertNull(ReadinessProber.parseSpec("curl -sf http://127.0.0.1:8080 || exit 1"));
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
