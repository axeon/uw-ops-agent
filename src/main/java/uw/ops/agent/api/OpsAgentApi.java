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
 * opsAgent和opsCenter交互的类。
 * <p>
 * 鉴权协议(v1)：Agent 持有 per-host secret(本地 /etc/uw-ops-agent/agent.secret)，
 * 所有上行请求(uploadHostStats/getTaskList/reportTaskResult)携带签名头：
 * X-Agent-HostHash / X-Agent-Stamp / X-Agent-Sign = HMAC(hostHash:body:stamp, secret)
 * secret 由 Center 在主机审核后首次上报时通过响应头 X-Agent-Secret 一次性下发。
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
        return agentClient.requestForEntity(rb.build(), new TypeReference<ResponseData<List<OpsTask>>>() {
        }).getValue();
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
            ResponseData<?> rd = JsonUtils.parse(respBody, ResponseData.class);
            if (rd != null && rd.isNotSuccess()) {
                throw new RuntimeException("reportTaskResult failed: " + rd.getMsg());
            }
        }
    }

    /**
     * 为请求附加上行签名头(若本地已有 secret)。
     */
    private static void applySignHeaders(Request.Builder rb, String hHash, String body) {
        if (StringUtils.isBlank(hHash)) {
            return;
        }
        String secret = SecretStore.load();
        if (StringUtils.isBlank(secret)) {
            // 尚未获取 secret，不签名(首次下发窗口)。
            return;
        }
        long stamp = SystemClock.now();
        String sign = HmacUtils.sign(hHash + ":" + body + ":" + stamp, secret);
        rb.header(HEADER_HOST_HASH, hHash);
        rb.header(HEADER_STAMP, String.valueOf(stamp));
        rb.header(HEADER_SIGN, sign);
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
