package uw.ops.agent.api;

import tools.jackson.core.type.TypeReference;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.JsonUtils;
import uw.common.util.SystemClock;
import uw.httpclient.http.HttpConfig;
import uw.httpclient.http.HttpInterface;
import uw.httpclient.json.JsonInterfaceHelper;
import uw.httpclient.util.MediaTypes;
import uw.httpclient.util.SSLContextUtils;
import uw.ops.agent.constant.Constants;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * opsAgent 和 opsCenter 交互的类。
 * <p>
 * 鉴权协议(v1)：per-host secret，双向 HMAC 签名。
 * <ul>
 *   <li>secret 在 Center 端 enable(审核) 时生成并存 DB，标记 secret_delivered=0。</li>
 *   <li>Agent 首次 uploadHostStats 时(filter 放行)，Center 通过响应头 X-Agent-Secret
 *       一次性下发同一个 secret，并置 secret_delivered=1；Agent 落盘到本地。</li>
 *   <li>稳态下所有上行请求(uploadHostStats/getTaskList/reportTaskResult)携带：
 *       X-Agent-HostHash(身份,始终带) / X-Agent-Stamp / X-Agent-Sign = HMAC(hostHash:body:stamp, secret)。</li>
 *   <li>Center disable 时清空 DB secret 并置 state=DISABLED，Agent 下次请求收到
 *       401 + X-Agent-Reason:host-disabled，拒绝本轮请求但保留本地 secret；重新注册需管理员显式重置。</li>
 * </ul>
 */
public class OpsAgentApi {

    private static final Logger log = LoggerFactory.getLogger(OpsAgentApi.class);

    /**
     * 共享 HTTP 客户端：15 秒超时（连接/读/写）。指标上报 60 秒一窗口，超时快速放弃可保住上报节奏
     * （Center 挂死期窗口保留率约 80%，30 秒超时降到 67%）；任务拉取/结果上报正常为毫秒级，
     * 异常时收紧到 15 秒放弃更果断，且有轮询与重试兜底。单实例避免多套连接池/线程池开销。
     */
    private static final HttpInterface agentClient = new JsonInterfaceHelper(HttpConfig.builder()
            .connectTimeout(15000).readTimeout(15000).writeTimeout(15000)
            .retryOnConnectionFailure(true)
            .trustManager(SSLContextUtils.getTrustAllManager())
            .sslSocketFactory(SSLContextUtils.getTruestAllSocketFactory())
            .hostnameVerifier((hostName, sslSession) -> true)
            .build());

    /**
     * 签名头：主机hash。
     */
    public static final String HEADER_HOST_HASH = "X-Agent-HostHash";
    /**
     * 签名头：时间戳(毫秒)。
     */
    public static final String HEADER_STAMP = "X-Agent-Stamp";
    /**
     * 签名头：HMAC签名。
     */
    public static final String HEADER_SIGN = "X-Agent-Sign";

    /** 本次请求唯一随机值；下行响应必须与本次请求绑定。 */
    public static final String HEADER_NONCE = "X-Agent-Nonce";

    /** Center签名回显的请求随机值。 */
    public static final String HEADER_CENTER_NONCE = "X-Center-Nonce";
    /**
     * 响应头：首次下发的secret。
     */
    public static final String HEADER_SECRET = "X-Agent-Secret";

    /**
     * 响应头：鉴权失败原因标记(Center 端 AgentAuthFilter.REASON_HOST_DISABLED)。
     */
    public static final String HEADER_REASON = "X-Agent-Reason";

    /**
     * 鉴权失败原因标记(Center 端 AgentAuthFilter.REASON_HOST_DISABLED)。
     */
    public static final String REASON_HOST_DISABLED = "host-disabled";

    /**
     * 响应头：下行响应签名时间戳(毫秒)，getTaskList(任务下发)通道专用。
     */
    public static final String HEADER_CENTER_STAMP = "X-Center-Stamp";

    /**
     * 响应头：下行响应签名 HMAC，getTaskList(任务下发)通道专用。
     */
    public static final String HEADER_CENTER_SIGN = "X-Center-Sign";

    /**
     * 下行响应签名消息前缀（与 center 端 AgentAuthHelper.RESPONSE_SIGN_PREFIX 一致）：
     * 与上行消息结构(hostHash:body:stamp)隔离，防上行签名被挪用为下行签名。
     */
    private static final String RESPONSE_SIGN_PREFIX = "center-response-v2:";

    /**
     * 下行响应签名时间窗(毫秒)：与 center 上行验签窗口(agent-sign-time-window，默认300s)对称，
     * 时间窗约束响应时效；窗口内重放由每次请求的独立 nonce 拒绝。
     */
    private static final long CENTER_SIGN_WINDOW_MILLIS = 300_000L;

    /**
     * 主机hash,唯一标识符。
     */
    private static volatile String hostHash;

    /**
     * 上传主机设备信息。
     */
    public static void uploadHostInfo(HostInfo hostInfo) throws Exception {
        hostHash = hostInfo.getHostHash();
        agentClient.postBodyForData(Constants.getOpsCenterHost() + "/agent/ops/uploadHostInfo", hostInfo);
    }

    /**
     * 上传主机统计信息。
     * 使用原生 OkHttp 调用，以便读取响应头 X-Agent-Secret 完成首次密钥下发。
     * 若本地已有 secret，请求携带上行签名头(稳态)；否则不携带(首次下发窗口)。
     * body 走 byte[] 一次序列化（千容器 payload 为 MB 级，避免 String 中转的重复编码拷贝）。
     */
    public static void uploadHostStats(HostStats hostStats) throws Exception {
        byte[] bodyBytes = JsonUtils.toBytes(hostStats);
        Request.Builder rb = new Request.Builder()
                .url(Constants.getOpsCenterHost() + "/agent/ops/uploadHostStats")
                .post(RequestBody.create(bodyBytes, MediaTypes.JSON_UTF8));
        RequestIdentity identity = applySignHeaders(rb, hostHash, bodyBytes, null);
        assertSuccess(executeForBody(rb, "uploadHostStats", identity, false), "uploadHostStats");
    }

    /**
     * 拉取任务执行脚本（单条 CAS 领取：一次最多一条到期任务，executeDate=领取时刻随响应下发）。
     * 领取侧保持单线程调用（MainService.ProcessOpsTask 是唯一领取者），CAS 冲突窗口毫秒级。
     * <p>
     * 下行响应签名：任务载荷可指定任意镜像/命令/密钥 env，伪造响应=直接控制宿主机——
     * 请求 nonce 绑定上行 HMAC；响应必须携带 X-Center-Nonce/X-Center-Stamp/X-Center-Sign，验签失败
     * 抛异常丢弃本轮响应，绝不执行来路不明的任务。
     */
    public static ResponseData<List<OpsTask>> getTaskList() throws Exception {
        // GET 请求无 body，签名时 body 约定为空串。
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Request.Builder rb = new Request.Builder()
                .url(Constants.getOpsCenterHost() + "/agent/ops/getTaskList?hostHash=" + (hostHash == null ? "" : hostHash))
                .header(HEADER_NONCE, nonce)
                .get();
        RequestIdentity identity = applySignHeaders(rb, hostHash, new byte[0], nonce);
        return JsonUtils.parse(executeForBody(rb, "getTaskList", identity, true), new TypeReference<ResponseData<List<OpsTask>>>() {
        });
    }

    /**
     * 上传ops任务（单条上报，各任务在执行池内独立调用，天然并发安全）。
     * <p>
     * executeDate 契约（center 侧按领取令牌与行内 execute_date 等值校验）：
     * 领取响应下发的 executeDate 原样回传（MainService.runOpsTask 不覆写）——正常执行恒等于
     * 行内值被接受；任务被超时重领后行内已刷新为新时刻，旧执行者回传的旧时刻被拒绝，
     * 迟到结果不覆盖新领取。
     * <p>
     * 日志只记摘要（taskId/taskType/state）：taskData 含领取时注入的 secret env 与
     * registry 凭据，全量序列化落日志=密钥明文落盘。
     */
    public static void reportTaskResult(OpsTask opsTask) throws Exception {
        byte[] bodyBytes = JsonUtils.toBytes(resultPayload(opsTask));
        log.info("ReportTaskResult: taskId={}, taskType={}, state={}",
                opsTask.getId(), opsTask.getTaskType(), opsTask.getState());
        Request.Builder rb = new Request.Builder()
                .url(Constants.getOpsCenterHost() + "/agent/ops/reportTaskResult")
                .post(RequestBody.create(bodyBytes, MediaTypes.JSON_UTF8));
        RequestIdentity identity = applySignHeaders(rb, hostHash, bodyBytes, null);
        assertSuccess(executeForBody(rb, "reportTaskResult", identity, false), "reportTaskResult");
    }

    /**
     * 结果只携带领取身份和执行输出；任务定义由 Center 数据库提供，密钥载荷不回传。
     */
    private static OpsTask resultPayload(OpsTask source) {
        OpsTask result = new OpsTask();
        result.setId(source.getId());
        result.setHostId(source.getHostId());
        result.setTaskType(source.getTaskType());
        result.setHostHash(hostHash);
        result.setExecuteDate(source.getExecuteDate());
        result.setFinishDate(source.getFinishDate());
        result.setState(source.getState());
        result.setDockerId(source.getDockerId());
        result.setActualPorts(source.getActualPorts());
        boolean sensitive = source.getTaskData() != null && source.getTaskData().isSensitiveOutput();
        result.setTaskResult(sensitive ? TaskData.SENSITIVE_RESULT_WITHHELD : source.getTaskResult());
        result.setTaskError(sensitive && source.getTaskError() != null ? TaskData.SENSITIVE_ERROR_WITHHELD : source.getTaskError());
        return result;
    }

    /**
     * 请求签名时固定的可信身份，响应验签不能重新读取可能变化的本地密钥。
     */
    private record RequestIdentity(String hostHash, String secret, String nonce) {
    }

    /**
     * 读取响应并认证任务载荷。仅无密钥的 uploadHostStats 成功响应可引导首次领取；
     * 稳态响应禁止换钥。未认证的 host-disabled 不能清钥，否则会诱导重新引导注册。
     */
    private static String executeForBody(Request.Builder rb, String action, RequestIdentity identity, boolean verifyCenterSign) throws Exception {
        if (verifyCenterSign && StringUtils.isBlank(identity.secret())) {
            throw new IllegalStateException("trusted agent secret missing.");
        }
        Request request = rb.build();
        try (Response response = agentClient.getOkHttpClient().newCall(request).execute()) {
            String deliveredSecret = response.header(HEADER_SECRET);
            boolean bootstrap = "uploadHostStats".equals(action) && StringUtils.isBlank(identity.secret());
            if (StringUtils.isNotBlank(deliveredSecret) && !bootstrap) {
                throw new IllegalStateException("unexpected agent secret delivery.");
            }
            if (isHostDisabled(response)) {
                //该头没有独立认证，保留可信密钥防止降级；禁用状态由 Center 拒绝后续请求。
                throw new IllegalStateException(action + " rejected: host disabled by center.");
            }
            if (!response.isSuccessful()) {
                throw new IllegalStateException(action + " failed: HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            if (verifyCenterSign) {
                verifyCenterResponseSign(response, body, identity);
            }
            if (StringUtils.isNotBlank(deliveredSecret)) {
                assertSuccess(body, action);
                if (!SecretStore.saveIfAbsent(deliveredSecret)) {
                    throw new IllegalStateException("agent secret bootstrap rejected or persistence failed.");
                }
                log.info("agent secret delivered from center.");
            }
            return body;
        }
    }

    /**
     * 校验下行响应签名（getTaskList 专用，调用方已保证 secret 非空）：
     * HMAC(RESPONSE_SIGN_PREFIX + hostHash + ":" + nonce + ":" + body + ":" + stamp, secret)。
     * <p>
     * 任务载荷可指定任意镜像/命令/密钥 env——能介入网络路径的一方伪造响应即等于控制宿主机，
     * 上行签名只认证本机身份、不能证明响应来自 Center。缺头/时间戳超窗/签名不符一律抛异常，
     * 本轮响应丢弃（任务延迟一轮轮询，安全优先于可用）。
     *
     * @param response 已读体的响应（头仍可读）
     * @param body     响应体文本（与 Center 签名字节一致）
     * @param identity 发请求前已固定的可信主机身份与密钥
     */
    private static void verifyCenterResponseSign(Response response, String body, RequestIdentity identity) {
        String secret = identity.secret();
        String nonce = response.header(HEADER_CENTER_NONCE);
        if (identity.nonce() == null || !identity.nonce().equals(nonce)) {
            throw new IllegalStateException("center response nonce mismatch.");
        }
        String stampStr = response.header(HEADER_CENTER_STAMP);
        String sign = response.header(HEADER_CENTER_SIGN);
        if (StringUtils.isBlank(stampStr) || StringUtils.isBlank(sign)) {
            throw new RuntimeException("center response signature missing.");
        }
        long stamp;
        try {
            stamp = Long.parseLong(stampStr);
        } catch (NumberFormatException e) {
            throw new RuntimeException("center response signature invalid.");
        }
        long now = SystemClock.now();
        if (stamp < now - CENTER_SIGN_WINDOW_MILLIS || stamp > now + CENTER_SIGN_WINDOW_MILLIS) {
            throw new RuntimeException("center response signature expired.");
        }
        String message = RESPONSE_SIGN_PREFIX + identity.hostHash() + ":" + identity.nonce() + ":" + body + ":" + stamp;
        if (!HmacUtils.verify(message, secret, sign)) {
            throw new RuntimeException("center response signature mismatch.");
        }
    }

    /**
     * 校验响应体为成功的 ResponseData（body 为 ResponseData JSON；非成功抛异常）。
     */
    private static void assertSuccess(String respBody, String action) throws Exception {
        ResponseData<?> rd = JsonUtils.parse(respBody, ResponseData.class);
        if (rd == null || rd.isNotSuccess()) {
            throw new RuntimeException(action + " failed: response rejected.");
        }
    }

    /**
     * 为请求附加身份与签名头，返回请求身份（hostHash/secret/nonce 的签名时快照，
     * 供响应验签使用——响应验签不能重新读取可能变化的本地密钥）。
     * 身份头(X-Agent-HostHash)无论是否有 secret 都必须带上，否则 Center 无法识别请求来源
     * (首次下发场景 Agent 本地无 secret，但仍需带身份头让 Center 知道是谁在请求)。
     * 签名头(X-Agent-Stamp/X-Agent-Sign)仅在有 secret 时才带，Center 据此判断是稳态验签
     * 还是首次下发窗口。
     * 签名消息 = HMAC(hHash : body : stamp)，任务领取额外绑定 :nonce；body 直接用序列化字节多段 update
     * （与拼接整串后单段签名逐字节等价），大 payload 不做整份拼接拷贝。
     *
     * @param rb    请求构建器
     * @param hHash 主机标识
     * @param body  已序列化请求体（签名输入）
     * @param nonce 请求随机值（任务领取通道传入，其余通道 null）
     * @return 签名时快照的请求身份
     */
    private static RequestIdentity applySignHeaders(Request.Builder rb, String hHash, byte[] body, String nonce) {
        String secret = SecretStore.load();
        RequestIdentity identity = new RequestIdentity(hHash, secret, nonce);
        if (StringUtils.isBlank(hHash)) {
            return identity;
        }
        // 身份头：始终带上。
        rb.header(HEADER_HOST_HASH, hHash);
        // 签名头：仅在有 secret 时带上(首次下发窗口 Center 放行不验签)。
        if (StringUtils.isBlank(secret)) {
            return identity;
        }
        long stamp = SystemClock.now();
        String sign = HmacUtils.sign(new byte[][]{
                (hHash + ":").getBytes(StandardCharsets.UTF_8),
                body,
                (":" + stamp + (nonce == null ? "" : ":" + nonce)).getBytes(StandardCharsets.UTF_8)}, secret);
        rb.header(HEADER_STAMP, String.valueOf(stamp));
        rb.header(HEADER_SIGN, sign);
        return identity;
    }

    /**
     * 判断响应是否表示主机被 Center 禁用(401 + X-Agent-Reason: host-disabled)。
     * 该响应头不能用于清空可信密钥；所有 401 保留密钥，待管理员确认后显式重置。
     */
    private static boolean isHostDisabled(Response response) {
        return response.code() == 401
                && REASON_HOST_DISABLED.equals(response.header(HEADER_REASON));
    }

}
