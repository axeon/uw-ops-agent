package uw.ops.agent.nativeimage;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import oshi.SystemInfo;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.JsonUtils;
import uw.httpclient.util.SSLContextUtils;
import uw.ops.agent.helper.vo.ContainerInspectRaw;
import uw.ops.agent.helper.vo.ContainerStatsRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;
import uw.ops.agent.helper.vo.CreateContainerParam;
import uw.ops.agent.helper.vo.CreateContainerRaw;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;
import uw.ops.agent.vo.ProbeScriptEntry;
import uw.ops.agent.vo.ProbeSpec;
import uw.ops.agent.vo.sub.ContainerInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native Image 元数据运行时验证器。
 * <p>
 * native 构建产物以 {@code uw-ops-agent --verify-native-metadata} 启动时执行 {@link #verify()}，
 * 在真实 native 运行时里逐一走通依赖反射元数据的代码路径，任何一个环节失败即抛异常退出——
 * 用于在镜像发布前暴露"JVM 可跑、native 缺元数据"的问题（典型如 vo 包新类未注册反射）。
 * JVM 侧由 NativeImageMetadataVerifierTest 直调同一路径。
 * <p>
 * 验证覆盖：
 * <ul>
 *   <li>vo 包 setter 反射（{@link AgentReflectionFeature} 注册的 declared 方法路径）；</li>
 *   <li>上/下行协议 JSON 绑定（OpsTask/ResponseData/HostStats 序列化）；</li>
 *   <li>Engine API 响应 vo 绑定（ContainerSummaryRaw/ContainerStatsRaw/ContainerInspectRaw，
 *       含 @JsonProperty snake/Pascal 映射、Map 嵌套绑定）与写操作 vo（CreateContainerParam 出站序列化、
 *       CreateContainerRaw 响应绑定——结构化任务通道）；</li>
 *   <li>任务统一载荷契约类 TaskData（本地 vo 包，随 AgentReflectionFeature 注册）：
 *       Shell+Docker 两段完整样例的序列化/反序列化往返（结构化任务通道的载荷绑定）；</li>
 *   <li>HMAC 签名与 TLS 上下文（JDK crypto provider 元数据）；</li>
 *   <li>资源包含（oshi/slf4j 配置与 SPI 文件）；</li>
 *   <li>Linux 原生访问（JNA 库加载、OSHI 硬件/OS 读取）；</li>
 *   <li>UDS SocketChannel 自环收发（Docker Engine API 采集的 NIO 传输路径，
 *       诞生于上次 tracing 采集之后，靠本验证兜底）；</li>
 *   <li>shell 执行（任务脚本链路）。</li>
 * </ul>
 */
public final class NativeImageMetadataVerifier {

    /**
     * 验证模式启动参数。
     */
    private static final String VERIFY_OPTION = "--verify-native-metadata";

    private NativeImageMetadataVerifier() {
    }

    /**
     * 检查进程是否以元数据验证模式启动（仅接受该单一参数）。
     *
     * @param args 进程参数
     * @return 验证模式时返回 true
     */
    public static boolean isVerificationRequested(String[] args) {
        return args != null && args.length == 1 && VERIFY_OPTION.equals(args[0]);
    }

    /**
     * 执行全部元数据依赖路径验证，全部通过后输出通过信息。
     */
    public static void verify() {
        verifySetterInvocation();
        verifyResponseDataDeserialization();
        verifySecurityServices();
        verifyResources();
        verifyLinuxNativeAccess();
        verifyUdsSocketChannel();
        verifyShellExecution();
        verifyProtocolVoBinding();
        verifyProbePersistenceBinding();
        System.out.println("Native image metadata verification passed.");
    }

    /** 验证探测模型的落盘与恢复绑定，真实 Native 运行时同样执行此路径。 */
    private static void verifyProbePersistenceBinding() {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("ops-probe-metadata-", ".json");
            ProbeScriptEntry expected = new ProbeScriptEntry("native-probe", 123,
                    "{\"type\":\"http\",\"url\":\"http://127.0.0.1:8080/health\",\"expect\":[200,204]}");
            Files.write(temporary, JsonUtils.toBytes(expected));
            ProbeScriptEntry restored = JsonUtils.parse(Files.readAllBytes(temporary), ProbeScriptEntry.class);
            require(restored != null && "native-probe".equals(restored.getDockerId())
                    && restored.getContainerId() == 123, "probe persistence binding");
            //ProbeSpec DSL 反射绑定（label/taskData 两路径的 JSON 契约）。
            ProbeSpec spec = JsonUtils.parse(restored.getScript(), ProbeSpec.class);
            require(spec != null && "http".equals(spec.getType())
                    && "http://127.0.0.1:8080/health".equals(spec.getUrl())
                    && spec.getExpect() != null && spec.getExpect().size() == 2
                    && spec.getExpect().get(1) == 204, "probe spec binding");
        } catch (IOException e) {
            throw new IllegalStateException("probe persistence verification failed.", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    throw new IllegalStateException("probe persistence verification cleanup failed.", e);
                }
            }
        }
    }

    /**
     * 验证 vo 包 setter 的反射调用（AgentReflectionFeature 注册的 declared methods 在 native 下可用）。
     */
    private static void verifySetterInvocation() {
        ContainerInfo ps = new ContainerInfo();
        try {
            ContainerInfo.class.getDeclaredMethod("setName", String.class).invoke(ps, "reflected-name");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ContainerInfo setter reflection failed.", e);
        }
        require("reflected-name".equals(ps.getName()), "ContainerInfo setter reflection");
    }

    /**
     * 验证下行协议 JSON 绑定：泛型 ResponseData 容器内嵌任务对象反序列化
     * （含 taskData 统一载荷的 camelCase 契约字段）。
     */
    private static void verifyResponseDataDeserialization() {
        ResponseData<List<OpsTask>> response = JsonUtils.parse("""
                {"time":1,"state":"SUCCESS","msg":"ok","code":"","data":[{"id":1,"taskData":{"execType":"SHELL","shell":{"script":"true"}}}]}
                """, new TypeReference<>() {
        });
        require(response.getData() != null && response.getData().size() == 1, "ResponseData deserialization");
        require(response.getData().getFirst().getId() == 1, "ResponseData task deserialization");
        require(response.getData().getFirst().getTaskData() != null
                && "SHELL".equals(response.getData().getFirst().getTaskData().getExecType()), "ResponseData taskData deserialization");
    }

    /**
     * 验证安全服务元数据：HMAC-SHA256 签名验签与 TLS 上下文初始化（鉴权与 Center 通信链路）。
     */
    private static void verifySecurityServices() {
        String signature = HmacUtils.sign("native-metadata", "verification-secret");
        require(HmacUtils.verify("native-metadata", "verification-secret", signature), "HMAC-SHA256");
        String nonce = UUID.randomUUID().toString().replace("-", "");
        require(nonce.matches("[a-f0-9]{32}"), "request nonce entropy initialization");
        String response = "center-response-v2:host:" + nonce + ":{}:1700000000000";
        require(HmacUtils.verify(response, "verification-secret", HmacUtils.sign(response, "verification-secret")),
                "nonce-bound response signature");
        require(SSLContextUtils.getAllTruestContext() != null, "TLS context initialization");
    }

    /**
     * 验证资源包含：oshi/slf4j 的配置与 SPI 服务文件、版本号资源被打进镜像。
     */
    private static void verifyResources() {
        verifyResource("oshi.properties");
        verifyResource("META-INF/services/oshi.spi.SystemInfoProvider");
        verifyResource("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
        verifyResource("project-info.properties");
    }

    /**
     * 验证单个类路径资源可读。
     *
     * @param resourceName 资源名
     */
    private static void verifyResource(String resourceName) {
        try (InputStream inputStream = NativeImageMetadataVerifier.class.getClassLoader().getResourceAsStream(resourceName)) {
            require(inputStream != null && inputStream.read() >= 0, "resource " + resourceName);
        } catch (IOException e) {
            throw new IllegalStateException("Resource verification failed: " + resourceName, e);
        }
    }

    /**
     * 验证 Linux 原生访问（仅 Linux 产物执行）：JNA 库加载与 OSHI 硬件/OS 读取。
     */
    private static void verifyLinuxNativeAccess() {
        if (!Platform.isLinux()) {
            return;
        }
        require(Native.POINTER_SIZE > 0, "JNA native library");
        SystemInfo systemInfo = new SystemInfo();
        require(systemInfo.getHardware().getProcessor().getLogicalProcessorCount() > 0, "OSHI processor access");
        require(!systemInfo.getOperatingSystem().getFamily().isBlank(), "OSHI operating system access");
    }

    /**
     * 验证 UDS SocketChannel 自环收发：Docker Engine API 采集的 NIO 传输路径
     * （本路径在既有 tracing 采集之后引入，且 JDK 标准类无需显式元数据，靠运行时实测兜底）。
     */
    private static void verifyUdsSocketChannel() {
        Path socketFile = null;
        try {
            socketFile = Files.createTempFile("uw-ops-verify", ".sock");
            Files.deleteIfExists(socketFile);
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketFile.toString());
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                 SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(address);
                client.connect(address);
                client.write(ByteBuffer.wrap("uds-ok".getBytes(StandardCharsets.US_ASCII)));
                try (SocketChannel accepted = server.accept()) {
                    ByteBuffer buffer = ByteBuffer.allocate(16);
                    int read = accepted.read(buffer);
                    require(read == 6 && "uds-ok".equals(new String(buffer.array(), 0, read, StandardCharsets.US_ASCII)),
                            "UDS socket channel");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("UDS socket channel verification failed.", e);
        } finally {
            try {
                if (socketFile != null) {
                    Files.deleteIfExists(socketFile);
                }
            } catch (IOException ignored) {
                //临时文件清理失败不影响验证结论。
            }
        }
    }

    /**
     * 验证 shell 执行链路（任务脚本执行的底层调用）。
     */
    private static void verifyShellExecution() {
        List<String> output = ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "printf 'native-shell\\n'"}, null, true, 5_000);
        require(output.equals(List.of("native-shell")), "shell execution");
    }

    /**
     * 验证协议与 Engine API 响应 vo 的 JSON 绑定：
     * 上报协议（HostStats 序列化，含幂等键字段 statsDate）、inventory/stats/inspect 三个输入 vo
     * （@JsonProperty 的 snake/Pascal 字段映射与嵌套 Map 绑定）。
     */
    private static void verifyProtocolVoBinding() {
        //上报协议：statsDate 是 (host_id, create_date) 幂等键的时间源，必须随序列化输出；
        //嵌套结构（info 内 statsList/eventList）是本轮协议的核心形状，一并断言。
        HostInfo hardware = new HostInfo();
        hardware.setCpuLogicalCount(8);
        require(JsonUtils.parse(JsonUtils.toString(hardware), HostInfo.class).getCpuLogicalCount() == 8,
                "HostInfo CPU capacity binding");
        ContainerInfo.Stats unknown = JsonUtils.parse(JsonUtils.toString(new ContainerInfo.Stats()), ContainerInfo.Stats.class);
        require(unknown.getCpuPercent() == null && unknown.getNetworkIn() == null && unknown.getDiskRead() == null,
                "unknown metrics stay null");
        HostStats hostStats = new HostStats();
        hostStats.setStatsDate(new Date(1700000000000L));
        ContainerInfo sampleInfo = new ContainerInfo();
        sampleInfo.setId("sample-hash");
        sampleInfo.setStatsList(List.of(new ContainerInfo.Stats()));
        sampleInfo.setEventList(List.of(new ContainerInfo.Event()));
        //就绪探测摘要字段：probeState/probeSeq/probeDate/probeFails/probeStreak/probeReason 随窗上报（camelCase），null 时省略。
        sampleInfo.setProbeState(1);
        sampleInfo.setProbeSeq(42L);
        sampleInfo.setProbeDate(1700000000000L);
        sampleInfo.setProbeFails(0);
        sampleInfo.setProbeStreak(90000L);
        sampleInfo.setProbeReason("tcp-refused");
        hostStats.setContainerInfoList(List.of(sampleInfo));
        String hostStatsJson = JsonUtils.toString(hostStats);
        require(hostStatsJson.contains("\"containerInfoList\"") && hostStatsJson.contains("\"statsList\"")
                && hostStatsJson.contains("\"eventList\"") && hostStatsJson.contains("\"statsDate\"")
                && hostStatsJson.contains("\"probeState\"") && hostStatsJson.contains("\"probeSeq\"")
                && hostStatsJson.contains("\"probeDate\"") && hostStatsJson.contains("\"probeFails\"")
                && hostStatsJson.contains("\"probeStreak\"") && hostStatsJson.contains("\"probeReason\""), "HostStats serialization");

        //inventory 输入 vo：PascalCase 字段与嵌套 HostConfig。
        ContainerSummaryRaw[] summaries = JsonUtils.parse("""
                [{"Id":"sum-id","Names":["/app-1"],"Image":"img:1","Command":"run","Created":1700000000,
                  "State":"running","Status":"Up 1 hour","HostConfig":{"NetworkMode":"bridge"}}]
                """, ContainerSummaryRaw[].class);
        require(summaries.length == 1 && "sum-id".equals(summaries[0].getId())
                && "bridge".equals(summaries[0].getHostConfig().getNetworkMode()), "ContainerSummaryRaw deserialization");

        //stats 输入 vo：snake_case 字段、memory stats 与 networks 的 Map 嵌套绑定。
        ContainerStatsRaw statsVo = JsonUtils.parse("""
                {"cpu_stats":{"cpu_usage":{"total_usage":7},"throttling_data":{"throttled_time":1,"periods":10,"throttled_periods":2}},
                 "memory_stats":{"usage":100,"limit":200,"stats":{"cache":50,"inactive_file":51}},
                 "pids_stats":{"current":3,"limit":512},
                 "networks":{"eth0":{"rx_bytes":11,"tx_bytes":22,"rx_dropped":0,"tx_dropped":0,"rx_errors":0,"tx_errors":0}},
                 "blkio_stats":{"io_service_bytes_recursive":[{"op":"Read","value":5},{"op":"Write","value":6}]}}
                """, ContainerStatsRaw.class);
        require(statsVo.getCpuStats() != null && statsVo.getCpuStats().getCpuUsage().getTotalUsage() == 7, "ContainerStatsRaw deserialization");
        require(statsVo.getMemoryStats() != null && statsVo.getMemoryStats().getStats().get("cache") == 50, "ContainerStatsRaw memory stats deserialization");
        require(statsVo.getNetworks() != null && statsVo.getNetworks().get("eth0").getRxBytes() == 11, "ContainerStatsRaw networks deserialization");

        //inspect 输入 vo：嵌套 State/HostConfig 标量。
        ContainerInspectRaw inspectVo = JsonUtils.parse("""
                {"RestartCount":2,"State":{"OOMKilled":true},"HostConfig":{"NanoCpus":2000000000,"Memory":4294967296}}
                """, ContainerInspectRaw.class);
        require(inspectVo.getRestartCount() == 2 && Boolean.TRUE.equals(inspectVo.getState().getOomKilled())
                && inspectVo.getHostConfig().getNanoCpus() == 2000000000L, "ContainerInspectRaw deserialization");

        //下行任务对象绑定（taskData 内嵌契约类，camelCase 键）。
        OpsTask task = JsonUtils.parse("{\"id\":1,\"dockerId\":\"hash01\",\"actualPorts\":\"APP_PORT=10010\\n\","
                + "\"taskData\":{\"taskVersion\":2,\"execType\":\"SHELL\",\"shell\":{\"script\":\"true\",\"ports\":{\"APP_PORT\":\"10010\"}}}}", OpsTask.class);
        require(task.getId() == 1 && "hash01".equals(task.getDockerId())
                && task.getTaskData() != null && "SHELL".equals(task.getTaskData().getExecType())
                && "true".equals(task.getTaskData().getShell().getScript()), "OpsTask deserialization");

        //任务统一载荷契约类（本地 vo.TaskData，随 uw.ops.agent.vo 包注册反射）：
        //Shell+Docker 两段完整样例经 JsonUtils 序列化/反序列化往返（结构化任务通道的载荷绑定）。
        TaskData sample = new TaskData();
        sample.setExecType("DOCKER");
        sample.setSensitiveOutput(true);
        sample.setSecretKeys(List.of("DB_SECRET"));
        TaskData.ShellSection shellSection = new TaskData.ShellSection();
        shellSection.setScript("echo ${APP_PORT}");
        shellSection.setPorts(Map.of("APP_PORT", "10010"));
        sample.setShell(shellSection);
        TaskData.ContainerSection dockerSection = new TaskData.ContainerSection();
        dockerSection.setAction("start");
        dockerSection.setImage("registry.example.com/uw-task-center@sha256:abc123");
        dockerSection.setContainerName("uw-ops-123");
        dockerSection.setTargetContainerHash("hash01");
        dockerSection.setEnv(List.of("JAVA_OPTS=-Xmx1g", "TZ=Asia/Shanghai"));
        dockerSection.setMounts(List.of("/etc/localtime:/etc/localtime:ro"));
        dockerSection.setNetworkMode("host");
        dockerSection.setRestartPolicy("on-failure:5");
        dockerSection.setHostPorts(Map.of("APP_PORT", 10010));
        dockerSection.setContainerMem(1000L);
        dockerSection.setNanoCpus(2_000_000_000L);
        dockerSection.setExtra(JsonUtils.parse("{\"CapAdd\":[\"NET_ADMIN\"],\"Sysctls\":{}}", JsonNode.class));
        dockerSection.setProbeTaskData("curl -sf -m 2 http://127.0.0.1:10010/ready || exit 1");
        dockerSection.setProfileCode("dev");
        sample.setContainer(dockerSection);
        TaskData roundTrip = JsonUtils.parse(JsonUtils.toString(sample), TaskData.class);
        require(roundTrip.isSensitiveOutput() && roundTrip.getSecretKeys().equals(List.of("DB_SECRET")) && "DOCKER".equals(roundTrip.getExecType()) && "start".equals(roundTrip.getContainer().getAction())
                && "registry.example.com/uw-task-center@sha256:abc123".equals(roundTrip.getContainer().getImage())
                && roundTrip.getContainer().getHostPorts().get("APP_PORT") == 10010
                && roundTrip.getContainer().getEnv().contains("TZ=Asia/Shanghai")
                && roundTrip.getShell().getPorts().get("APP_PORT").equals("10010")
                && "NET_ADMIN".equals(roundTrip.getContainer().getExtra().path("CapAdd").get(0).asString()),
                "TaskData round-trip deserialization");

        //结构化任务写操作 vo：create 请求体出站序列化（PascalCase + null 省略）与 daemon 响应入站绑定。
        CreateContainerParam createParam = new CreateContainerParam();
        createParam.setImage("registry.example.com/uw-task-center@sha256:abc123");
        createParam.setEnv(List.of("APP_PORT=10010", "TZ=Asia/Shanghai"));
        createParam.setLabels(Map.of("uw.ops.container-id", "123"));
        CreateContainerParam.HostConfigSection hostConfig = new CreateContainerParam.HostConfigSection();
        hostConfig.setNetworkMode("host");
        hostConfig.setMemory(1024L * 1024 * 1024);
        hostConfig.setBinds(List.of("/etc/localtime:/etc/localtime:ro"));
        CreateContainerParam.RestartPolicySection restartPolicy = new CreateContainerParam.RestartPolicySection();
        restartPolicy.setName("unless-stopped");
        hostConfig.setRestartPolicy(restartPolicy);
        createParam.setHostConfig(hostConfig);
        String createJson = JsonUtils.toString(createParam);
        require(createJson.contains("\"Image\"") && createJson.contains("\"NetworkMode\"")
                && createJson.contains("\"RestartPolicy\"") && !createJson.contains("\"PortBindings\""),
                "CreateContainerParam serialization");

        CreateContainerRaw createRaw = JsonUtils.parse("{\"Id\":\"abc123def\",\"Warnings\":[]}", CreateContainerRaw.class);
        require("abc123def".equals(createRaw.getId()) && createRaw.getWarnings().isEmpty(), "CreateContainerRaw deserialization");
    }

    /**
     * 断言验证项通过，失败时抛出带环节名的异常。
     *
     * @param valid     验证结果
     * @param operation 环节名（用于失败信息）
     */
    private static void require(boolean valid, String operation) {
        if (!valid) {
            throw new IllegalStateException(operation + " failed.");
        }
    }
}
