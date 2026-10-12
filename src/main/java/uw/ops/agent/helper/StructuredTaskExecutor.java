package uw.ops.agent.helper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uw.common.util.JsonUtils;
import uw.ops.agent.constant.TaskState;
import uw.ops.agent.helper.vo.ContainerInspectRaw;
import uw.ops.agent.helper.vo.ContainerSummaryRaw;
import uw.ops.agent.helper.vo.CreateContainerParam;
import uw.ops.agent.helper.vo.CreateContainerRaw;
import uw.ops.agent.util.NetworkUtils;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 结构化任务执行器（taskData.execType=DOCKER 通道，无 shell 回退）。
 * <p>
 * 依 taskData.Docker.Action 分发：
 * <ul>
 *   <li><b>start</b>：幂等定位（label uw.ops.container-id / 容器名）→ 命中且镜像一致、running
 *       直接成功（重放不产生第二个容器）；残留（镜像不符/已退出）stop+remove 后重建；
 *       create（Env 含密钥随 UDS 请求体直传 daemon，无临时文件）→ start → 轮询等待 running；</li>
 *   <li><b>stop</b>：按 TargetContainerHash 定位（hash 失败按容器名兜底），容器已不存在直接成功
 *       （幂等）；stop?t=30 后 remove(force+v)——对齐旧 start 脚本头部的 stop+rm 模式，消除已停
 *       残留的中间态；各步 404 幂等，stop 成功但 remove 失败任务 FAILED（重跑时 stop 幂等后补删）。</li>
 * </ul>
 * 安全规范：日志与 taskError 全程不输出 Env 值（密钥脱敏）——失败时详细原因（daemon 响应等）
 * 只写服务端日志，对外用固定英文摘要。
 */
public final class StructuredTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(StructuredTaskExecutor.class);

    /**
     * 执行类型：shell 通道（MainService 分发用）。
     */
    public static final String EXEC_TYPE_SHELL = "SHELL";

    /**
     * 执行类型：结构化 Docker 通道（本执行器）。
     */
    public static final String EXEC_TYPE_DOCKER = "DOCKER";

    /**
     * 受管容器 label：计划 id（管理分组）。
     */
    public static final String LABEL_PLAN_ID = "uw.ops.plan-id";

    /**
     * 受管容器 label：容器 id（幂等定位的台账身份键）。
     */
    public static final String LABEL_CONTAINER_ID = "uw.ops.container-id";

    /** 标记启动任务使用秘密，指标采集不得回传潜在敏感命令。 */
    public static final String LABEL_SENSITIVE = "uw.ops.sensitive";

    /**
     * 探针 label 键：tcp/http 终值 DSL JSON 的声明式下发位（create 携带、inventory 收敛，
     * Docker 元数据即持久化真相源——agent 重启后探测集合零恢复动作）。
     */
    public static final String LABEL_PROBE = "uw.ops.probe";

    /**
     * Docker.Action：启动（create+start+等待 running）。
     */
    private static final String ACTION_START = "start";

    /**
     * Docker.Action：停止（stop 后 remove，消除已停残留中间态）。
     */
    private static final String ACTION_STOP = "stop";

    /**
     * stop 的 SIGTERM 等待秒数（terminationGraceSeconds，应用走停机链摘流）。
     */
    private static final int STOP_TIMEOUT_SECONDS = 30;

    /**
     * start 启动后等待 running 的轮询间隔。
     */
    private static final long RUNNING_POLL_INTERVAL_MILLIS = 2_000L;

    /**
     * start 总预算（与普通任务超时一致：10 分钟，覆盖 daemon 拉镜像/create/start/等待）。
     */
    private static final long TASK_BUDGET_MILLIS = 600_000L;

    /**
     * 主机时区读取来源（Env 无 TZ 条目时的默认注入值）：生产读 /etc/timezone 首行，
     * 读失败静默跳过；测试可注入覆盖（本机无该文件的环境确定性）。
     */
    static volatile Supplier<String> hostTimezoneSupplier = StructuredTaskExecutor::readHostTimezoneFile;

    /**
     * 生产端口校验器（真实 bind 探测递增）。
     */
    static final PortVerifier NATIVE_PORT_VERIFIER = StructuredTaskExecutor::verifyHostPortsNative;

    /**
     * 端口校验器（测试可注入）：生产=NetworkUtils 真实 bind 探测递增
     * （{@link #NATIVE_PORT_VERIFIER}）；测试注入 no-op/受控实现保证断言确定性。
     */
    static volatile PortVerifier portVerifier = NATIVE_PORT_VERIFIER;

    /**
     * 端口校验函数接口（测试注入点）。
     */
    @FunctionalInterface
    interface PortVerifier {

        /**
         * 校验并按需改写 hostPorts 为本机实际可用端口（被占递增）。
         *
         * @param docker DOCKER 执行段
         * @throws Exception 校验失败（如端口空间耗尽，message 为固定英文摘要）
         */
        void verify(TaskData.ContainerSection docker) throws Exception;
    }

    private StructuredTaskExecutor() {
    }

    /**
     * 执行 DOCKER 结构化任务：成功时置 dockerId/taskResult 与 SUCCEED 状态；
     * 失败抛固定英文摘要异常（不含 Env 值），由 MainService 统一置 FAILED 与 taskError。
     *
     * @param opsTask  任务对象（containerId/planId 为 label 与定位材料）
     * @param taskData 统一载荷（Docker 段必填）
     * @throws Exception 执行失败（message 为固定英文摘要，日志保留任务ID与异常类型）
     */
    public static void execute(OpsTask opsTask, TaskData taskData) throws Exception {
        TaskData.ContainerSection docker = taskData.getContainer();
        if (docker == null || StringUtils.isBlank(docker.getAction())) {
            throw new IllegalArgumentException("taskData.docker section or action missing.");
        }
        DockerInfoHelper.beginTaskBudget(TASK_BUDGET_MILLIS);
        try {
            if (ACTION_START.equals(docker.getAction())) {
                executeStart(opsTask, docker, taskData.isSensitiveOutput());
            } else if (ACTION_STOP.equals(docker.getAction())) {
                executeStop(opsTask, docker);
            } else {
                throw new IllegalArgumentException("unsupported docker action: " + docker.getAction());
            }
        } catch (TaskExecutionException e) {
            //自带固定摘要的失败（端口耗尽/stopped-but-remove-failed 等，含子类）：原样上抛不套
            //通用摘要（taskError 定位用），根因已进日志。
            throw e;
        } catch (Exception e) {
            //daemon响应/异常可能回显秘密载荷，日志与上报均只记录固定摘要。
            log.error("structured task failed, taskId={}, containerId={}, errorType={}",
                    opsTask.getId(), opsTask.getContainerId(), e.getClass().getSimpleName());
            throw new TaskExecutionException("docker task failed: execution rejected.");
        } finally {
            DockerInfoHelper.endTaskBudget();
        }
    }

    /**
     * start 执行流：幂等定位 → 残留清理 → create+start → 等待 running → 上报。
     */
    private static void executeStart(OpsTask opsTask, TaskData.ContainerSection docker, boolean sensitiveOutput) throws Exception {
        //1.幂等定位：label uw.ops.container-id 优先，未命中按容器名兜底（存量/异常命名残留）。
        String containerName = docker.getContainerName();
        ContainerInspectRaw existing = locateExisting(String.valueOf(opsTask.getContainerId()), containerName);
        if (existing != null) {
            boolean running = existing.getState() != null && Boolean.TRUE.equals(existing.getState().getRunning());
            if (running && isTargetMatch(existing, docker, String.valueOf(opsTask.getContainerId()))) {
                //命中且 Image/labels 一致、running：直接成功（重放不产生第二个容器）。
                log.info("structured start idempotent hit, taskId={}, containerId={}, dockerId={}",
                        opsTask.getId(), opsTask.getContainerId(), existing.getId());
                finishStart(opsTask, docker, existing.getId());
                return;
            }
            //2.残留清理：Image/labels 不符或已退出 → running 先 stop 再 remove 后重建（对齐旧 start 脚本头部语义）。
            log.info("structured start clean residue, taskId={}, containerId={}, dockerId={}, running={}",
                    opsTask.getId(), opsTask.getContainerId(), existing.getId(), running);
            if (running) {
                DockerInfoHelper.stopContainer(existing.getId(), STOP_TIMEOUT_SECONDS);
            }
            DockerInfoHelper.removeContainer(existing.getId());
        }
        //3.本机端口校验（台账空闲≠本机可用：非受管进程/手工容器/内核临时端口 center 不可见）：
        //   被占则从分配值递增取空闲，hostPorts 改写为实际值（后续 create/回程/摘要随实际值走）。
        portVerifier.verify(docker);
        //4.镜像准备：本地不存在则按确定引用拉取（Engine API 的 create 不具备 CLI run 的
        //   自动拉取——本地无镜像直接 404；凭据随任务下发用后即弃）。
        DockerInfoHelper.ensureImage(docker.getImage(), docker.getRegistryAuth());
        //5.组装并创建、启动。
        CreateContainerParam param = buildCreateParam(opsTask, docker, sensitiveOutput);
        log.info("structured start creating, taskId={}, containerId={}",
                opsTask.getId(), opsTask.getContainerId());
        CreateContainerRaw created = DockerInfoHelper.createContainer(containerName, param);
        DockerInfoHelper.startContainer(created.getId());
        //6.轮询等待 running（间隔 2s，总预算与任务超时一致）。
        waitRunning(created.getId());
        //7.上报：dockerId 为 containerHash 台账唯一来源；taskResult 摘要不含 Env 值。
        finishStart(opsTask, docker, created.getId());
        log.info("structured start finished, taskId={}, containerId={}, dockerId={}",
                opsTask.getId(), opsTask.getContainerId(), created.getId());
    }

    /**
     * 本机端口校验（生产实现，仅新建路径调用）：center 台账分配只覆盖受管容器，非受管进程、
     * 手工容器、内核临时端口的占用 center 不可见——对每个分配端口从分配值起 bind 探测
     * （{@link NetworkUtils#getUsablePort}），空闲原样保留、被占递增至下一个空闲并整体改写
     * hostPorts 为实际值（后续 PortBindings、actualPorts 回程与摘要均用实际值；center 与台账
     * 比对发现偏移即告警，运维可见）。幂等命中分支不得调用：容器已在运行，其端口被自身监听
     * 属预期，校验必偏移。端口空间耗尽抛固定英文摘要（任务失败）。
     */
    private static void verifyHostPortsNative(TaskData.ContainerSection docker) throws TaskExecutionException {
        Map<String, Integer> hostPorts = docker.getHostPorts();
        if (hostPorts == null || hostPorts.isEmpty()) {
            return;
        }
        Map<String, Integer> actualPorts = new LinkedHashMap<>();
        boolean shifted = false;
        for (Map.Entry<String, Integer> entry : hostPorts.entrySet()) {
            Integer assigned = entry.getValue();
            //非法定义跳过校验原样保留（daemon 侧 create 校验兜底拒绝）。
            if (entry.getKey() == null || assigned == null || assigned <= 0 || assigned > 65535) {
                continue;
            }
            int usable;
            try {
                usable = NetworkUtils.getUsablePort(assigned);
            } catch (IOException e) {
                throw new TaskExecutionException("host port allocation failed: no usable port from " + assigned + ".");
            }
            if (usable != assigned) {
                shifted = true;
                log.warn("host port occupied, shifted to next usable: name={}, assigned={}, actual={}",
                        entry.getKey(), assigned, usable);
            }
            actualPorts.put(entry.getKey(), usable);
        }
        if (shifted) {
            docker.setHostPorts(actualPorts);
        }
    }

    /**
     * start 成功尾部（幂等命中与新建两条路径共用）：回填 dockerId/actualPorts/摘要与 SUCCEED
     * 状态；探测配置分流注册——label 路径（tcp/http 无 secret，create 已写入容器 label）即时
     * 注册条目衔接启动期密集探测，taskData 路径（exec/敏感）缓存并落盘供 ReadinessProber 消费。
     */
    private static void finishStart(OpsTask opsTask, TaskData.ContainerSection docker, String dockerId) {
        opsTask.setDockerId(dockerId);
        opsTask.setActualPorts(buildActualPorts(docker));
        opsTask.setTaskResult(buildStartSummary(docker, dockerId));
        opsTask.setState(TaskState.SUCCEED.getValue());
        ReadinessProber.registerProbe(dockerId, opsTask.getContainerId(), docker.getProbeLabel(), docker.getProbeTaskData());
    }

    /**
     * stop 执行流（对齐旧 start 脚本头部的 stop+rm 模式，消除已停残留的中间态）：
     * hash 定位（失败按名兜底）→ 不存在直接幂等成功 → stop?t=30 → 复核退出码 → remove(force+v)。
     * 各步 404 幂等；stop 成功但 remove 失败抛 {@link StopRemoveFailedException}（taskError 注明
     * stopped-but-remove-failed，重跑时 stop 幂等成功后补删）。
     */
    private static void executeStop(OpsTask opsTask, TaskData.ContainerSection docker) throws DockerInfoHelper.DockerApiException, StopRemoveFailedException {
        String targetHash = docker.getTargetContainerHash();
        //1.目标 = TargetContainerHash；404/不存在 → 直接成功（幂等）。
        ContainerInspectRaw inspect = inspectQuietly(targetHash);
        if (inspect == null && StringUtils.isNotBlank(docker.getContainerName())) {
            //hash 定位失败按容器名兜底（台账 name/hash 兼容存量命名）。
            inspect = inspectQuietly(docker.getContainerName());
        }
        if (inspect == null) {
            ReadinessProber.removeContainer(targetHash);
            log.info("structured stop target already gone, taskId={}, containerId={}",
                    opsTask.getId(), opsTask.getContainerId());
            opsTask.setDockerId(targetHash);
            opsTask.setTaskResult("container already gone, stop succeed (idempotent). name=" + docker.getContainerName());
            opsTask.setState(TaskState.SUCCEED.getValue());
            return;
        }
        //2.优雅停止（SIGTERM 等待 30s，超时 daemon SIGKILL）；404=容器已不在，幂等继续走补删。
        try {
            DockerInfoHelper.stopContainer(inspect.getId(), STOP_TIMEOUT_SECONDS);
        } catch (DockerInfoHelper.ContainerNotFoundException e) {
            log.info("structured stop target gone before stop, taskId={}, dockerId={}", opsTask.getId(), inspect.getId());
        }
        //确认停止成功或已不存在后再移除，stop 失败时运行中容器必须继续探测。
        //按名兜底定位时 targetHash 与实际 ID 不同才需双清（同 ID 免重复删锁与文件系统调用）。
        if (!inspect.getId().equals(targetHash)) {
            ReadinessProber.removeContainer(targetHash);
        }
        ReadinessProber.removeContainer(inspect.getId());
        //3.复核退出码（remove 前的最后 inspect；容器已消失则 unknown）。
        Long exitCode = null;
        ContainerInspectRaw stopped = inspectQuietly(inspect.getId());
        if (stopped != null && stopped.getState() != null) {
            exitCode = stopped.getState().getExitCode();
        }
        opsTask.setDockerId(inspect.getId());
        //4.remove（force=1&v=1）：404 幂等（已删/已消失）；其余失败任务 FAILED。
        try {
            DockerInfoHelper.removeContainer(inspect.getId());
        } catch (DockerInfoHelper.ContainerNotFoundException e) {
            log.info("structured stop target gone before remove, taskId={}, dockerId={}", opsTask.getId(), inspect.getId());
        } catch (DockerInfoHelper.DockerApiException e) {
            log.error("structured stop remove failed, taskId={}, dockerId={}, err={}",
                    opsTask.getId(), inspect.getId(), e.getClass().getSimpleName());
            throw new StopRemoveFailedException("container stopped but remove failed: exitCode="
                    + (exitCode == null ? "unknown" : exitCode));
        }
        //5.全链成功：上报 dockerId 与退出码。
        opsTask.setTaskResult("container stopped and removed. name=" + docker.getContainerName()
                + ", exitCode=" + (exitCode == null ? "unknown" : exitCode));
        opsTask.setState(TaskState.SUCCEED.getValue());
        log.info("structured stop finished, taskId={}, containerId={}, dockerId={}, exitCode={}",
                opsTask.getId(), opsTask.getContainerId(), inspect.getId(), exitCode);
    }

    /**
     * 幂等命中判定：镜像一致且携带目标 uw.ops.container-id label（按名兜底定位到的
     * 无 label 异物/存量容器不算命中，走残留清理重建收敛为受管容器）。
     */
    private static boolean isTargetMatch(ContainerInspectRaw inspect, TaskData.ContainerSection docker, String containerIdLabel) {
        if (docker.getImage() == null || inspect.getConfig() == null || !docker.getImage().equals(inspect.getConfig().getImage())) {
            return false;
        }
        Map<String, String> labels = inspect.getConfig().getLabels();
        return labels != null && containerIdLabel.equals(labels.get(LABEL_CONTAINER_ID));
    }

    /**
     * 幂等定位本机已有容器：inventory 全量扫描，label uw.ops.container-id 命中优先返回；
     * 未命中再按容器名比对（Names 带前导斜杠）返回残留（含无 label 的同名异物）。
     *
     * @return 命中容器的 inspect（authoritative 的 State/Config 比对源），不存在返回 null
     */
    private static ContainerInspectRaw locateExisting(String containerIdLabel, String containerName) throws DockerInfoHelper.DockerApiException {
        ContainerSummaryRaw byName = null;
        for (ContainerSummaryRaw summary : DockerInfoHelper.listAllContainers()) {
            Map<String, String> labels = summary.getLabels();
            if (labels != null && containerIdLabel.equals(labels.get(LABEL_CONTAINER_ID))) {
                return DockerInfoHelper.containerInspect(summary.getId());
            }
            if (byName == null && summary.getNames() != null) {
                for (String name : summary.getNames()) {
                    if (containerName != null && ("/" + containerName).equals(name)) {
                        byName = summary;
                        break;
                    }
                }
            }
        }
        return byName == null ? null : DockerInfoHelper.containerInspect(byName.getId());
    }

    /**
     * inspect 单查，404（容器不存在）返回 null，其余异常上抛。
     */
    private static ContainerInspectRaw inspectQuietly(String idOrName) throws DockerInfoHelper.DockerApiException {
        if (StringUtils.isBlank(idOrName)) {
            return null;
        }
        try {
            return DockerInfoHelper.containerInspect(idOrName);
        } catch (DockerInfoHelper.ContainerNotFoundException e) {
            return null;
        }
    }

    /**
     * 组装 create 请求参数：固定字段（Image/Env/Labels/HostConfig 显式段）+ Extra 合并（固定字段优先）。
     */
    private static CreateContainerParam buildCreateParam(OpsTask opsTask, TaskData.ContainerSection docker, boolean sensitiveOutput) {
        CreateContainerParam param = new CreateContainerParam();
        param.setImage(docker.getImage());
        param.setEnv(buildEnv(docker.getEnv()));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_PLAN_ID, String.valueOf(opsTask.getPlanId()));
        labels.put(LABEL_CONTAINER_ID, String.valueOf(opsTask.getContainerId()));
        if (sensitiveOutput) {
            labels.put(LABEL_SENSITIVE, "true");
        }
        if (StringUtils.isNotBlank(docker.getProbeLabel())) {
            labels.put(LABEL_PROBE, docker.getProbeLabel());
        }
        if (StringUtils.isNotBlank(docker.getUser())) {
            param.setUser(docker.getUser());
        }
        param.setLabels(labels);
        param.setHostConfig(buildHostConfig(docker));
        return param;
    }

    /**
     * 组装 HostConfig 固定字段：NetworkMode/RestartPolicy/Memory(MB→字节)/NanoCpus/Binds/PortBindings/
     * Privileged/Sysctls/Ulimits/LogConfig(四平铺字段合成)，再与 Extra 片段合并（固定字段优先）。
     */
    private static CreateContainerParam.HostConfigSection buildHostConfig(TaskData.ContainerSection docker) {
        CreateContainerParam.HostConfigSection hostConfig = new CreateContainerParam.HostConfigSection();
        if (StringUtils.isNotBlank(docker.getNetworkMode())) {
            hostConfig.setNetworkMode(docker.getNetworkMode());
        }
        hostConfig.setRestartPolicy(parseRestartPolicy(docker.getRestartPolicy()));
        if (Boolean.TRUE.equals(docker.getPrivileged())) {
            hostConfig.setPrivileged(Boolean.TRUE);
        }
        if (docker.getSysctls() != null && !docker.getSysctls().isEmpty()) {
            hostConfig.setSysctls(new LinkedHashMap<>(docker.getSysctls()));
        }
        if (docker.getUlimits() != null && !docker.getUlimits().isEmpty()) {
            List<CreateContainerParam.Ulimit> ulimits = new ArrayList<>(docker.getUlimits().size());
            for (TaskData.Ulimit limit : docker.getUlimits()) {
                if (limit == null || StringUtils.isBlank(limit.getName())) {
                    continue;
                }
                CreateContainerParam.Ulimit target = new CreateContainerParam.Ulimit();
                target.setName(limit.getName());
                target.setSoft(limit.getSoft());
                target.setHard(limit.getHard());
                ulimits.add(target);
            }
            if (!ulimits.isEmpty()) {
                hostConfig.setUlimits(ulimits);
            }
        }
        CreateContainerParam.LogConfigSection logConfig = buildLogConfig(docker);
        if (logConfig != null) {
            hostConfig.setLogConfig(logConfig);
        }
        if (docker.getContainerMem() != null && docker.getContainerMem() > 0) {
            hostConfig.setMemory(docker.getContainerMem() * 1024 * 1024);
        }
        if (docker.getNanoCpus() != null && docker.getNanoCpus() != 0) {
            hostConfig.setNanoCpus(docker.getNanoCpus());
        }
        if (docker.getMounts() != null && !docker.getMounts().isEmpty()) {
            hostConfig.setBinds(new ArrayList<>(docker.getMounts()));
        }
        if (docker.getHostPorts() != null && !docker.getHostPorts().isEmpty()) {
            Map<String, List<CreateContainerParam.PortBinding>> portBindings = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : docker.getHostPorts().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                //Docker API 的 PortBindings 键=容器端口/协议（数字），HostPort=宿主端口；
                //端口模型为"端口名→分配端口"（host 网络容器与宿主同端口），
                //容器端口取实际使用值（本机校验被占时已从分配值递增偏移）。键若误用端口名
                //（如 APP_PORT），daemon 校验 "invalid port" 直接 400（host 模式虽丢弃发布端口，
                //仍先过键校验）。
                portBindings.put(entry.getValue() + "/tcp", List.of(new CreateContainerParam.PortBinding(String.valueOf(entry.getValue()))));
            }
            if (!portBindings.isEmpty()) {
                hostConfig.setPortBindings(portBindings);
            }
        }
        return mergeExtra(hostConfig, docker.getExtra());
    }

    /**
     * 日志配置合成（四平铺字段 → LogConfig）：driver/tag/max-size/max-file 任一存在才生成，
     * 全空返回 null（随 daemon 默认）；driver 缺省按 json-file 承载选项。
     */
    private static CreateContainerParam.LogConfigSection buildLogConfig(TaskData.ContainerSection docker) {
        boolean hasDriver = StringUtils.isNotBlank(docker.getLogDriver());
        Map<String, String> options = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(docker.getLogTag())) {
            options.put("tag", docker.getLogTag());
        }
        if (StringUtils.isNotBlank(docker.getLogMaxSize())) {
            options.put("max-size", docker.getLogMaxSize());
        }
        if (StringUtils.isNotBlank(docker.getLogMaxFile())) {
            options.put("max-file", docker.getLogMaxFile());
        }
        if (!hasDriver && options.isEmpty()) {
            return null;
        }
        CreateContainerParam.LogConfigSection logConfig = new CreateContainerParam.LogConfigSection();
        logConfig.setType(hasDriver ? docker.getLogDriver() : "json-file");
        logConfig.setConfig(options.isEmpty() ? null : options);
        return logConfig;
    }

    /**
     * Env 组装：taskData 直传（K=V 保序，含 center 注入的 secret）；无 TZ= 开头条目时追加
     * TZ=&lt;主机 /etc/timezone 首行&gt;，读取失败静默跳过（容器时区回落镜像默认）。
     */
    private static List<String> buildEnv(List<String> env) {
        List<String> result = env == null ? new ArrayList<>() : new ArrayList<>(env);
        boolean hasTz = false;
        for (String entry : result) {
            if (entry != null && entry.startsWith("TZ=")) {
                hasTz = true;
                break;
            }
        }
        if (!hasTz) {
            String timezone = hostTimezoneSupplier.get();
            if (StringUtils.isNotBlank(timezone)) {
                result.add("TZ=" + timezone);
            }
        }
        return result;
    }

    /**
     * 读主机时区（/etc/timezone 首行）：不存在/读失败返回 null（静默，不注入 TZ）。
     */
    static String readHostTimezoneFile() {
        try {
            for (String line : Files.readAllLines(Paths.get("/etc/timezone"), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重启策略解析："unless-stopped"/"always"/"no" → 仅 Name；"on-failure[:N]" → Name+MaximumRetryCount；
     * 空值返回 null（daemon 默认 no）。非法重试次数抛英文异常（任务失败，拒绝静默降级）。
     */
    static CreateContainerParam.RestartPolicySection parseRestartPolicy(String policy) {
        if (StringUtils.isBlank(policy)) {
            return null;
        }
        CreateContainerParam.RestartPolicySection section = new CreateContainerParam.RestartPolicySection();
        if (policy.startsWith("on-failure")) {
            section.setName("on-failure");
            int split = policy.indexOf(':');
            if (split >= 0) {
                String retry = policy.substring(split + 1).trim();
                try {
                    section.setMaximumRetryCount(Integer.parseInt(retry));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid restart policy: " + policy);
                }
            }
            return section;
        }
        section.setName(policy);
        return section;
    }

    /**
     * Extra 合并（固定字段优先）：Extra 是 HostConfig 的 JSON 直通片段，反序列化为补丁对象后
     * 仅在固定字段未设（序列化缺省，即 null）时填充——固定字段（NetworkMode/Memory/...）是
     * center 调度决策值，不允许被 Extra 覆盖；Extra 承载其余高级参数（CapAdd/Sysctls/Ulimits 等）。
     * 实现走 JsonNode 键级合并（对象树逐顶层字段判缺省），不做反射字段拷贝。
     */
    static CreateContainerParam.HostConfigSection mergeExtra(CreateContainerParam.HostConfigSection fixed, JsonNode extra) {
        if (extra == null || extra.isNull() || extra.isEmpty()) {
            return fixed;
        }
        if (!extra.isObject()) {
            throw new IllegalArgumentException("taskData.docker.extra must be a json object.");
        }
        //固定段序列化为树：类上 NON_NULL，已设字段才出现键；Extra 仅填充缺省键。
        ObjectNode merged = (ObjectNode) JsonUtils.getJsonMapper().valueToTree(fixed);
        ObjectNode extraNode = (ObjectNode) extra;
        for (Map.Entry<String, JsonNode> entry : extraNode.properties()) {
            if (!merged.has(entry.getKey()) || merged.get(entry.getKey()).isNull()) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }
        return JsonUtils.convert(merged, CreateContainerParam.HostConfigSection.class);
    }

    /**
     * 轮询等待容器 running：间隔 2s，超总预算抛异常（native 的 inspect 异常直接上抛）。
     */
    private static void waitRunning(String containerId) throws DockerInfoHelper.DockerApiException, InterruptedException {
        while (true) {
            ContainerInspectRaw inspect = DockerInfoHelper.containerInspect(containerId);
            if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return;
            }
            Thread.sleep(Math.min(RUNNING_POLL_INTERVAL_MILLIS, DockerInfoHelper.remainingTaskMillis()));
        }
    }

    /**
     * actualPorts 回程（Properties 格式）：hostPorts 名=实际使用端口逐行（本机校验偏移后即
     * 实际值），center 与台账比对（不符告警，运维可见端口偏移），host 网络下即实际监听端口的核对来源。
     */
    private static String buildActualPorts(TaskData.ContainerSection docker) {
        if (docker.getHostPorts() == null || docker.getHostPorts().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : docker.getHostPorts().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * start 成功摘要（镜像/容器名/hostPorts，不含 Env 值）。
     */
    private static String buildStartSummary(TaskData.ContainerSection docker, String dockerId) {
        StringBuilder sb = new StringBuilder("container started. name=").append(docker.getContainerName())
                .append(", image=").append(docker.getImage())
                .append(", dockerId=").append(dockerId);
        if (docker.getHostPorts() != null && !docker.getHostPorts().isEmpty()) {
            sb.append(", hostPorts=").append(docker.getHostPorts());
        }
        return sb.toString();
    }

    /**
     * stop 已成功但 remove 失败：message 即 taskError 摘要（英文、含 stopped-but-remove-failed 语义，
     * 不含 daemon 响应细节与 Env 值），不套通用 "docker task failed" 摘要，便于定位补删场景。
     */
    public static class TaskExecutionException extends Exception {
        TaskExecutionException(String summary) {
            super(summary);
        }
    }

    static final class StopRemoveFailedException extends TaskExecutionException {

        /**
         * @param message 固定英文摘要
         */
        StopRemoveFailedException(String message) {
            super(message);
        }
    }

}
