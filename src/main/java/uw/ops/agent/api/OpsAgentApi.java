package uw.ops.agent.api;

import com.fasterxml.jackson.core.type.TypeReference;
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
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.vo.HostInfo;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;

import java.util.List;

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
 *       401 + X-Agent-Reason:host-disabled，据此清空本地 secret 并停止工作。</li>
 * </ul>
 */
public class OpsAgentApi {

    private static final Logger log = LoggerFactory.getLogger(OpsAgentApi.class);

    private static final HttpInterface agentClient =
            new JsonInterfaceHelper(HttpConfig.builder().connectTimeout(30000).readTimeout(30000).writeTimeout(30000).retryOnConnectionFailure(true).trustManager(SSLContextUtils.getTrustAllManager()).sslSocketFactory(SSLContextUtils.getTruestAllSocketFactory()).hostnameVerifier((hostName, sslSession) -> true).build());

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
     * 主机hash,唯一标识符。
     */
    private static volatile String hostHash;

    /**
     * 上传主机设备信息。
     */
    public static void uploadHostInfo(HostInfo hostInfo) throws Exception {
        hostHash = hostInfo.getHostHash();
        agentClient.postBodyForData(getOpsCenterHost() + "/agent/ops/uploadHostInfo", hostInfo);
    }

    /**
     * 上传主机统计信息。
     * 使用原生 OkHttp 调用，以便读取响应头 X-Agent-Secret 完成首次密钥下发。
     * 若本地已有 secret，请求携带上行签名头(稳态)；否则不携带(首次下发窗口)。
     */
    public static void uploadHostStats(HostStats hostStats) throws Exception {
        String bodyJson = JsonUtils.toString(hostStats);
        Request.Builder rb = new Request.Builder()
                .url(getOpsCenterHost() + "/agent/ops/uploadHostStats")
                .post(RequestBody.create(bodyJson, MediaTypes.JSON_UTF8));
        applySignHeaders(rb, hostHash, bodyJson);
        try (Response response = agentClient.getOkHttpClient().newCall(rb.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            // 检查是否被 Center 禁用(host-disabled)：若是则清空本地 secret，回到初始态。
            if (isHostDisabled(response)) {
                SecretStore.clear();
                throw new RuntimeException("uploadHostStats rejected: host disabled by center.");
            }
            // 首次下发：从响应头取 secret 并落盘。
            String deliveredSecret = response.header(HEADER_SECRET);
            if (StringUtils.isNotBlank(deliveredSecret)) {
                SecretStore.save(deliveredSecret);
                log.info("agent secret delivered from center.");
            }
            // 校验响应是否成功(body 为 ResponseData JSON)。
            ResponseData<?> rd = JsonUtils.parse(respBody, ResponseData.class);
            if (rd != null && rd.isNotSuccess()) {
                throw new RuntimeException("uploadHostStats failed: " + rd.getMsg());
            }
        }
    }

    /**
     * 拉取任务执行脚本。
     */
    public static ResponseData<List<OpsTask>> getTaskList() throws Exception {
        // GET 请求无 body，签名时 body 约定为空串。
        Request.Builder rb = new Request.Builder()
                .url(getOpsCenterHost() + "/agent/ops/getTaskList?hostHash=" + (hostHash == null ? "" : hostHash))
                .get();
        applySignHeaders(rb, hostHash, "");
        // 用原生 OkHttp 调用，以便检查响应头 X-Agent-Reason(host-disabled 时清 secret)。
        try (Response response = agentClient.getOkHttpClient().newCall(rb.build()).execute()) {
            if (isHostDisabled(response)) {
                SecretStore.clear();
                throw new RuntimeException("getTaskList rejected: host disabled by center.");
            }
            String respBody = response.body() != null ? response.body().string() : "";
            return JsonUtils.parse(respBody, new TypeReference<ResponseData<List<OpsTask>>>() {
            });
        }
    }

    /**
     * 上传ops任务。
     */
    public static void reportTaskResult(OpsTask opsTask) throws Exception {
        opsTask.setHostHash(hostHash);
        String bodyJson = JsonUtils.toString(opsTask);
        log.info("ReportTaskResult: {}", bodyJson);
        Request.Builder rb = new Request.Builder()
                .url(getOpsCenterHost() + "/agent/ops/reportTaskResult")
                .post(RequestBody.create(bodyJson, MediaTypes.JSON_UTF8));
        applySignHeaders(rb, hostHash, bodyJson);
        try (Response response = agentClient.getOkHttpClient().newCall(rb.build()).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            // 检查是否被 Center 禁用(host-disabled)：若是则清空本地 secret。
            if (isHostDisabled(response)) {
                SecretStore.clear();
                throw new RuntimeException("reportTaskResult rejected: host disabled by center.");
            }
            ResponseData<?> rd = JsonUtils.parse(respBody, ResponseData.class);
            if (rd != null && rd.isNotSuccess()) {
                throw new RuntimeException("reportTaskResult failed: " + rd.getMsg());
            }
        }
    }

    /**
     * 为请求附加身份与签名头。
     * 身份头(X-Agent-HostHash)无论是否有 secret 都必须带上，否则 Center 无法识别请求来源
     * (首次下发场景 Agent 本地无 secret，但仍需带身份头让 Center 知道是谁在请求)。
     * 签名头(X-Agent-Stamp/X-Agent-Sign)仅在有 secret 时才带，Center 据此判断是稳态验签
     * 还是首次下发窗口。
     */
    private static void applySignHeaders(Request.Builder rb, String hHash, String body) {
        if (StringUtils.isBlank(hHash)) {
            return;
        }
        // 身份头：始终带上。
        rb.header(HEADER_HOST_HASH, hHash);
        // 签名头：仅在有 secret 时带上(首次下发窗口 Center 放行不验签)。
        String secret = SecretStore.load();
        if (StringUtils.isBlank(secret)) {
            return;
        }
        long stamp = SystemClock.now();
        String sign = HmacUtils.sign(hHash + ":" + body + ":" + stamp, secret);
        rb.header(HEADER_STAMP, String.valueOf(stamp));
        rb.header(HEADER_SIGN, sign);
    }

    /**
     * 判断响应是否表示主机被 Center 禁用(401 + X-Agent-Reason: host-disabled)。
     * 仅此场景下 Agent 才清空本地 secret；其它 401(签名错/IP 不符等)保留 secret 重试，
     * 避免偶发抖动或临时故障导致密钥丢失。
     */
    private static boolean isHostDisabled(Response response) {
        return response.code() == 401
                && REASON_HOST_DISABLED.equals(response.header(HEADER_REASON));
    }

    /**
     * 获取opsCenter主机地址。
     */
    private static String getOpsCenterHost() {
        String opsCenterHost = System.getenv("OPS_CENTER_HOST");
        if (StringUtils.isBlank(opsCenterHost)) {
            opsCenterHost = "http://127.0.0.1:1000";
        }
        return opsCenterHost;
    }

}
