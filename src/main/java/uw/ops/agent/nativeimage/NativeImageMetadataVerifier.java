package uw.ops.agent.nativeimage;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import oshi.SystemInfo;
import tools.jackson.core.type.TypeReference;
import uw.common.response.ResponseData;
import uw.common.util.HmacUtils;
import uw.common.util.JsonUtils;
import uw.httpclient.util.SSLContextUtils;
import uw.ops.agent.util.ShellCmdUtils;
import uw.ops.agent.vo.HostStats;
import uw.ops.agent.vo.OpsTask;
import uw.ops.agent.vo.cmd.DockerPsCmd;
import uw.ops.agent.vo.cmd.DockerStatsCmd;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Exercises runtime paths that depend on Native Image reachability metadata.
 */
public final class NativeImageMetadataVerifier {

    private static final String VERIFY_OPTION = "--verify-native-metadata";

    private NativeImageMetadataVerifier() {
    }

    /**
     * Checks whether the process was started in native metadata verification mode.
     *
     * @param args process arguments
     * @return true when verification was requested
     */
    public static boolean isVerificationRequested(String[] args) {
        return args != null && args.length == 1 && VERIFY_OPTION.equals(args[0]);
    }

    /**
     * Verifies JSON, security, resource and Linux native-access paths.
     */
    public static void verify() {
        verifySetterInvocation();
        verifyResponseDataDeserialization();
        verifySecurityServices();
        verifyResources();
        verifyLinuxNativeAccess();
        verifyShellExecution();

        DockerStatsCmd[] stats = JsonUtils.parse("""
                [{"ID":"stats-id","Name":"container","CPUPerc":"1.25%","MemPerc":"2.50%",
                  "MemUsage":"1GiB / 2GiB","BlockIO":"3MB / 4MB","NetIO":"5kB / 6kB","PIDs":"7"}]
                """, DockerStatsCmd[].class);
        require(stats.length == 1 && "stats-id".equals(stats[0].getID()), "DockerStatsCmd deserialization");

        DockerPsCmd[] processes = JsonUtils.parse("""
                [{"ID":"ps-id","Name":"container","Image":"image:latest","Command":"run",
                  "CreatedAt":"2026-09-02 22:00:00 +0800 CST","State":"running","Status":"Up 1 hour",
                  "Mounts":"/data","Networks":"host","Ports":"80/tcp"}]
                """, DockerPsCmd[].class);
        require(processes.length == 1 && "ps-id".equals(processes[0].getID()), "DockerPsCmd deserialization");

        OpsTask task = JsonUtils.parse("{\"id\":1,\"taskScript\":\"true\"}", OpsTask.class);
        require(task.getId() == 1 && "true".equals(task.getTaskScript()), "OpsTask deserialization");
        require(JsonUtils.toString(new HostStats()).contains("\"dockerStatsList\""), "HostStats serialization");

        System.out.println("Native image metadata verification passed.");
    }

    private static void verifyResponseDataDeserialization() {
        ResponseData<List<OpsTask>> response = JsonUtils.parse("""
                {"time":1,"state":"SUCCESS","msg":"ok","code":"","data":[{"id":1,"taskScript":"true"}]}
                """, new TypeReference<>() {
        });
        require(response.getData() != null && response.getData().size() == 1, "ResponseData deserialization");
        require(response.getData().getFirst().getId() == 1, "ResponseData task deserialization");
    }

    private static void verifySecurityServices() {
        String signature = HmacUtils.sign("native-metadata", "verification-secret");
        require(HmacUtils.verify("native-metadata", "verification-secret", signature), "HMAC-SHA256");
        require(SSLContextUtils.getAllTruestContext() != null, "TLS context initialization");
    }

    private static void verifyResources() {
        verifyResource("oshi.properties");
        verifyResource("META-INF/services/oshi.spi.SystemInfoProvider");
        verifyResource("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
    }

    private static void verifyResource(String resourceName) {
        try (InputStream inputStream = NativeImageMetadataVerifier.class.getClassLoader().getResourceAsStream(resourceName)) {
            require(inputStream != null && inputStream.read() >= 0, "resource " + resourceName);
        } catch (IOException e) {
            throw new IllegalStateException("Resource verification failed: " + resourceName, e);
        }
    }

    private static void verifyLinuxNativeAccess() {
        if (!Platform.isLinux()) {
            return;
        }
        require(Native.POINTER_SIZE > 0, "JNA native library");
        SystemInfo systemInfo = new SystemInfo();
        require(systemInfo.getHardware().getProcessor().getLogicalProcessorCount() > 0, "OSHI processor access");
        require(!systemInfo.getOperatingSystem().getFamily().isBlank(), "OSHI operating system access");
    }

    private static void verifyShellExecution() {
        List<String> output = ShellCmdUtils.runNative(
                new String[]{"/bin/sh", "-c", "printf 'native-shell\\n'"}, null, true, 5_000);
        require(output.equals(List.of("native-shell")), "shell execution");
    }

    private static void verifySetterInvocation() {
        DockerStatsCmd stats = new DockerStatsCmd();
        try {
            String setterName = System.getProperty("uw.ops.agent.native.test.setter", "setID");
            DockerStatsCmd.class.getDeclaredMethod(setterName, String.class).invoke(stats, "reflected-id");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DockerStatsCmd setter reflection failed.", e);
        }
        require("reflected-id".equals(stats.getID()), "DockerStatsCmd setter reflection");
    }

    private static void require(boolean valid, String operation) {
        if (!valid) {
            throw new IllegalStateException(operation + " failed.");
        }
    }
}
