package uw.ops.agent.nativeimage;

import org.junit.jupiter.api.Test;
import uw.common.util.JsonUtils;

import java.io.IOException;
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
    void shouldPackageOnlyUnifiedReachabilityMetadata() throws IOException {
        ClassLoader classLoader = NativeImageMetadataVerifierTest.class.getClassLoader();
        URL metadata = classLoader.getResource("META-INF/native-image/com.umtone/uw-ops-agent/reachability-metadata.json");
        assertNotNull(metadata);
        try (InputStream inputStream = metadata.openStream()) {
            Map<?, ?> metadataConfig = JsonUtils.parse(inputStream, Map.class);
            assertEquals(Set.of("comment", "reflection", "resources"), metadataConfig.keySet());
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
