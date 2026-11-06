package dev.btc.core.integrity;

import dev.btc.core.config.AnticheatConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A refused anticheat.yml must leave every setting as it was, not half-loaded. */
class AnticheatConfigInitTest {

    @Test
    @DisplayName("an invalid storage.table-prefix changes no setting and leaves the file untouched")
    void invalidPrefixAppliesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        String content = """
            sentinel:
              enabled: false
            checks:
              reach:
                max-distance-survival: 9.5
            storage:
              enabled: true
              port: 1234
              table-prefix: "1bad prefix"
            """;
        Files.writeString(file, content);

        boolean sentinel = AnticheatConfig.sentinelEnabled;
        double reach = AnticheatConfig.reachMaxDistanceSurvival;
        boolean storage = AnticheatConfig.storageEnabled;
        int port = AnticheatConfig.storagePort;
        String prefix = AnticheatConfig.storageTablePrefix;
        try {
            AnticheatConfig.sentinelEnabled = true;
            AnticheatConfig.reachMaxDistanceSurvival = 3.0;
            AnticheatConfig.storageEnabled = false;
            AnticheatConfig.storagePort = 5432;
            AnticheatConfig.storageTablePrefix = "btc_";

            IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> AnticheatConfig.init(file.toFile()));

            assertTrue(refused.getMessage().contains("storage.table-prefix"), refused.getMessage());
            assertTrue(AnticheatConfig.sentinelEnabled, "sentinel.enabled=false in the file must not be applied");
            assertEquals(3.0, AnticheatConfig.reachMaxDistanceSurvival);
            assertFalse(AnticheatConfig.storageEnabled);
            assertEquals(5432, AnticheatConfig.storagePort);
            assertEquals("btc_", AnticheatConfig.storageTablePrefix);
            assertEquals(content, Files.readString(file), "a refused file must not be rewritten");
        } finally {
            AnticheatConfig.sentinelEnabled = sentinel;
            AnticheatConfig.reachMaxDistanceSurvival = reach;
            AnticheatConfig.storageEnabled = storage;
            AnticheatConfig.storagePort = port;
            AnticheatConfig.storageTablePrefix = prefix;
        }
    }
}
