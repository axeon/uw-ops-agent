package uw.ops.agent.api;

import com.sun.net.httpserver.HttpServer;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.JsonUtils;
import uw.ops.agent.util.SecretStore;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.TaskData;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP regression tests; all secrets are synthetic and stored below target/test-agent. */
class OpsAgentApiTest {
    private static final String TRUSTED = "synthetic-original-secret";
    private static final String REPLACEMENT = "synthetic-untrusted-secret";
    private static final String HASH = "synthetic-host";
    private HttpServer server;
    private final AtomicReference<Runnable> duringResponse = new AtomicReference<>(() -> {});
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>(Map.of());
    private final AtomicReference<String> requestNonce = new AtomicReference<>();
    private final AtomicReference<Boolean> requestSignValid = new AtomicReference<>();
    private String signingSecret;
    private boolean deliverSecret;
    private String body;
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        assertTrue(SecretStore.getSecretPath().toString().contains("target/test-agent"));
        SecretStore.clear();
        assertTrue(SecretStore.save(TRUSTED));
        var hostField = OpsAgentApi.class.getDeclaredField("hostHash");
        hostField.setAccessible(true);
        hostField.set(null, HASH);
        body = JsonUtils.toString(ResponseData.success(List.of()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestNonce.set(exchange.getRequestHeaders().getFirst("X-Agent-Nonce"));
            String requestStamp = exchange.getRequestHeaders().getFirst("X-Agent-Stamp");
            String requestSign = exchange.getRequestHeaders().getFirst("X-Agent-Sign");
            if (requestSign != null) {
                requestSignValid.set(HmacUtils.verify(HASH + "::" + requestStamp
                        + (requestNonce.get() == null ? "" : ":" + requestNonce.get()), TRUSTED, requestSign));
            }
            duringResponse.get().run();
            Map<String, String> responseHeaders = signingSecret == null ? headers.get() : signedHeaders(signingSecret, deliverSecret);
            responseHeaders.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        SecretStore.clear();
    }

    private Map<String, String> signedHeaders(String secret, boolean deliver) {
        String stamp = Long.toString(System.currentTimeMillis());
        String nonce = requestNonce.get();
        String signature = HmacUtils.sign("center-response-v2:" + HASH + ":" + nonce + ":" + body + ":" + stamp, secret);
        return deliver ? Map.of("X-Center-Nonce", nonce, OpsAgentApi.HEADER_CENTER_STAMP, stamp, OpsAgentApi.HEADER_CENTER_SIGN, signature,
                OpsAgentApi.HEADER_SECRET, secret)
                : Map.of("X-Center-Nonce", nonce, OpsAgentApi.HEADER_CENTER_STAMP, stamp, OpsAgentApi.HEADER_CENTER_SIGN, signature);
    }

    private void signResponse(String secret, boolean deliver) {
        signingSecret = secret;
        deliverSecret = deliver;
    }

    private String request(String action, boolean verify) throws Exception {
        Request.Builder request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/").get();
        String nonce = null;
        if (verify) {
            nonce = java.util.UUID.randomUUID().toString().replace("-", "");
            request.header("X-Agent-Nonce", nonce);
        }
        Method sign = OpsAgentApi.class.getDeclaredMethod("applySignHeaders", Request.Builder.class, String.class, byte[].class, String.class);
        sign.setAccessible(true);
        Object identity = sign.invoke(null, request, HASH, new byte[0], nonce);
        Method execute = OpsAgentApi.class.getDeclaredMethod("executeForBody", Request.Builder.class, String.class, identity.getClass(), boolean.class);
        execute.setAccessible(true);
        try {
            return (String) execute.invoke(null, request, action, identity, verify);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void taskResponseCannotReplaceTrustedSecret() {
        signResponse(REPLACEMENT, true);
        assertThrows(Exception.class, () -> request("getTaskList", true));
        assertEquals(TRUSTED, SecretStore.load());
    }

    @Test
    void taskResponseWithoutTrustedSecretFailsClosed() {
        SecretStore.clear();
        signResponse(REPLACEMENT, true);
        assertThrows(Exception.class, () -> request("getTaskList", true));
        assertNull(SecretStore.load());
    }

    @Test
    void missingOrWrongSignatureIsRejected() {
        assertThrows(Exception.class, () -> request("getTaskList", true));
        signResponse(REPLACEMENT, false);
        assertThrows(Exception.class, () -> request("getTaskList", true));
        assertEquals(TRUSTED, SecretStore.load());
    }

    @Test
    void verifiesWithSecretCapturedBeforeNetworkCall() throws Exception {
        signResponse(TRUSTED, false);
        duringResponse.set(() -> assertTrue(SecretStore.save(REPLACEMENT)));
        assertEquals(body, request("getTaskList", true));
    }

    @Test
    void forgedHostDisabledCannotClearSecretOnAnyChannel() {
        status = 401;
        headers.set(Map.of(OpsAgentApi.HEADER_REASON, OpsAgentApi.REASON_HOST_DISABLED));
        for (String action : List.of("getTaskList", "uploadHostStats", "reportTaskResult")) {
            assertThrows(Exception.class, () -> request(action, "getTaskList".equals(action)));
            assertEquals(TRUSTED, SecretStore.load());
        }
    }

    @Test
    void onlySuccessfulInitialStatsMayBootstrap() throws Exception {
        SecretStore.clear();
        headers.set(Map.of(OpsAgentApi.HEADER_SECRET, REPLACEMENT));
        assertThrows(Exception.class, () -> request("reportTaskResult", false));
        assertNull(SecretStore.load());
        status = 500;
        assertThrows(Exception.class, () -> request("uploadHostStats", false));
        assertNull(SecretStore.load());
        status = 200;
        assertEquals(body, request("uploadHostStats", false));
        assertEquals(REPLACEMENT, SecretStore.load());
        assertThrows(Exception.class, () -> request("uploadHostStats", false));
    }

    @Test
    void signedResponseCannotBeReplayedOnAnotherClaim() throws Exception {
        duringResponse.set(() -> {
            if (headers.get().isEmpty()) {
                headers.set(signedHeaders(TRUSTED, false));
            }
        });
        assertEquals(body, request("getTaskList", true));
        String firstNonce = requestNonce.get();
        assertTrue(firstNonce.matches("[a-f0-9]{32}"));
        assertTrue(requestSignValid.get(), "nonce must be included in request authentication");
        assertThrows(Exception.class, () -> request("getTaskList", true));
        assertNotEquals(firstNonce, requestNonce.get());
    }

    @Test
    void nonceMustBePresentAndAuthenticated() {
        duringResponse.set(() -> {
            var signed = new java.util.HashMap<>(signedHeaders(TRUSTED, false));
            signed.remove("X-Center-Nonce");
            headers.set(signed);
        });
        assertThrows(Exception.class, () -> request("getTaskList", true));
        duringResponse.set(() -> {
            String stamp = Long.toString(System.currentTimeMillis());
            headers.set(Map.of("X-Center-Nonce", requestNonce.get(), "X-Center-Stamp", stamp,
                    "X-Center-Sign", HmacUtils.sign("center-response-v2:" + HASH + ":wrong:" + body + ":" + stamp, TRUSTED)));
        });
        assertThrows(Exception.class, () -> request("getTaskList", true));
    }

    @Test
    void allTaskTypesAndOutcomesRetainClaimIdentityInResultJson() throws Exception {
        Method result = OpsAgentApi.class.getDeclaredMethod("resultPayload", OpsTask.class);
        result.setAccessible(true);
        for (int type : List.of(0, 1, 2)) {
            for (int state : List.of(3, -1)) {
                OpsTask task = new OpsTask();
                task.setId(123);
                task.setHostId(456);
                task.setTaskType(type);
                task.setState(state);
                task.setExecuteDate(new Date(1234));
                task.setFinishDate(new Date(5678));
                task.setTaskResult("output");
                task.setTaskError(state == -1 ? "failed" : null);
                OpsTask payload = JsonUtils.parse(JsonUtils.toString(result.invoke(null, task)), OpsTask.class);
                assertEquals(type, payload.getTaskType());
                assertEquals(state, payload.getState());
                assertEquals(123, payload.getId());
                assertEquals(456, payload.getHostId());
                assertEquals(HASH, payload.getHostHash());
                assertEquals(task.getExecuteDate(), payload.getExecuteDate());
                assertEquals(task.getFinishDate(), payload.getFinishDate());
                assertEquals(task.getTaskResult(), payload.getTaskResult());
                assertEquals(task.getTaskError(), payload.getTaskError());
            }
        }
    }

    @Test
    void sensitiveOutputCannotEnterResultPayload() throws Exception {
        OpsTask task = new OpsTask();
        TaskData data = new TaskData();
        data.setSensitiveOutput(true);
        task.setTaskData(data);
        task.setTaskResult("synthetic-secret");
        task.setTaskError("synthetic-secret");
        Method result = OpsAgentApi.class.getDeclaredMethod("resultPayload", OpsTask.class);
        result.setAccessible(true);
        String json = JsonUtils.toString(result.invoke(null, task));
        assertFalse(json.contains("synthetic-secret"));
        assertTrue(json.contains("result withheld"));
    }

    @Test
    void resultPayloadKeepsClaimAndOutcomeWithoutExecutionSecrets() throws Exception {
        OpsTask task = new OpsTask();
        task.setId(7);
        task.setTaskType(2);
        task.setHostId(4);
        task.setExecuteDate(new Date(1234));
        task.setFinishDate(new Date(2234));
        task.setDockerId("docker-id");
        task.setTaskInfo("sensitive-display-value");
        TaskData data = new TaskData();
        TaskData.ContainerSection docker = new TaskData.ContainerSection();
        docker.setEnv(List.of("PASSWORD=synthetic-env-secret"));
        docker.setRegistryAuth("synthetic-registry-secret");
        data.setContainer(docker);
        task.setTaskData(data);
        Method result = OpsAgentApi.class.getDeclaredMethod("resultPayload", OpsTask.class);
        result.setAccessible(true);
        OpsTask payload = (OpsTask) result.invoke(null, task);
        String json = JsonUtils.toString(payload);
        assertFalse(json.contains("synthetic-env-secret"));
        assertFalse(json.contains("synthetic-registry-secret"));
        assertFalse(json.contains("sensitive-display-value"));
        assertEquals(task.getExecuteDate(), payload.getExecuteDate());
        assertEquals(4, payload.getHostId());
        assertEquals(2, payload.getTaskType());
        assertEquals("docker-id", payload.getDockerId());
        assertSame(data, task.getTaskData());
    }
}
