package uw.ops.agent.nativeimage;

import org.junit.jupiter.api.Test;
import uw.common.util.JsonUtils;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageMetadataVerifierTest {

    @Test
    void probePersistenceModelIsIncludedInAutomaticNativeRegistration() throws Exception {
        var scan = AgentReflectionFeature.class.getDeclaredMethod("findModelClassNames", Iterable.class);
        scan.setAccessible(true);
        var root = java.nio.file.Path.of(uw.ops.agent.vo.ProbeScriptEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<?> classes = (Set<?>) scan.invoke(null, List.of(root));
        assertTrue(classes.contains("uw.ops.agent.vo.ProbeScriptEntry"));
    }

    @Test
    void shouldVerifyJsonMetadataPaths() {
        assertDoesNotThrow(NativeImageMetadataVerifier::verify);
    }

    @Test
    void shouldOnlyEnableVerificationForTheExactOption() {
        assertTrue(NativeImageMetadataVerifier.isVerificationRequested(new String[]{"--verify-native-metadata"}));
        assertFalse(NativeImageMetadataVerifier.isVerificationRequested(null));
        assertFalse(NativeImageMetadataVerifier.isVerificationRequested(new String[0]));
        assertFalse(NativeImageMetadataVerifier.isVerificationRequested(new String[]{"--verify-native-metadata", "extra"}));
    }

    @Test
    void shouldPackageOnlyUnifiedReachabilityMetadata() throws Exception {
        ClassLoader classLoader = NativeImageMetadataVerifierTest.class.getClassLoader();
        URL metadata = classLoader.getResource("META-INF/native-image/com.umtone/uw-ops-agent/reachability-metadata.json");
        assertNotNull(metadata);
        try (InputStream inputStream = metadata.openStream()) {
            Map<?, ?> metadataConfig = JsonUtils.parse(inputStream, Map.class);
            assertEquals(Set.of("comment", "proxy", "reflection", "resources"), metadataConfig.keySet());
            //proxy 段仅限 JNA 库接口（macOS 本机二进制经 Native.load 走动态代理），
            //条目必须是可加载的真实接口——挡住非接口类（如 CallbackReference）混入。
            for (Object entry : (List<?>) metadataConfig.get("proxy")) {
                for (String interfaceName : (List<String>) ((Map<?, ?>) entry).get("interfaces")) {
                    assertTrue(interfaceName.startsWith("com.sun.jna."), "proxy interface " + interfaceName);
                    assertTrue(Class.forName(interfaceName, false, classLoader).isInterface(),
                            "proxy interface " + interfaceName);
                }
            }
            Set<String> resourceGlobs = ((List<?>) metadataConfig.get("resources")).stream()
                    .map(Map.class::cast)
                    .map(resource -> (String) resource.get("glob"))
                    .collect(Collectors.toSet());
            assertTrue(resourceGlobs.contains("com/sun/jna/linux-aarch64/libjnidispatch.so"));
            assertTrue(resourceGlobs.contains("com/sun/jna/linux-x86-64/libjnidispatch.so"));
            assertFalse(resourceGlobs.contains("simplelogger.properties"));

            boolean hasManualApplicationModel = ((List<?>) metadataConfig.get("reflection")).stream()
                    .map(Map.class::cast)
                    .map(reflection -> reflection.get("type"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(type -> type.startsWith("uw.ops.agent.vo") || type.startsWith("uw.ops.agent.helper.vo"));
            assertFalse(hasManualApplicationModel);
        }
        assertNull(classLoader.getResource("META-INF/native-image/config/reflect-config.json"));
        assertNull(classLoader.getResource("META-INF/native-image/config/jni-config.json"));
        assertNull(classLoader.getResource("META-INF/native-image/config/proxy-config.json"));
        assertNull(classLoader.getResource("META-INF/native-image/config/resource-config.json"));
    }
}
