package uw.ops.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistSecretWithOwnerOnlyPermissions() throws Exception {
        Path secretFile = tempDir.resolve("agent.secret");

        assertTrue(SecretStore.writeSecret(secretFile, "test-secret"));
        assertEquals("test-secret", Files.readString(secretFile, StandardCharsets.UTF_8));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(secretFile));
    }
}
