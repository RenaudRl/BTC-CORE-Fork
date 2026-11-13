package dev.btc.core.security.admin;

import dev.btc.core.config.AnticheatConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code admin.op-lock.*} keys of anticheat.yml: delivered off, written back documented, read when set. */
class OpLockConfigTest {

    // identity.server-id is set so that init() has no reason to log through a server that is not there.
    private static final String IDENTITY = "identity:\n  server-id: test\n";
    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void deliveredOffWithAnEmptyListAndTheFileIsWrittenBackDocumented(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY);

        AnticheatConfig.init(file.toFile());

        assertEquals(OpLock.Mode.OFF, AnticheatConfig.opLockMode);
        assertEquals(Set.of(), AnticheatConfig.opLockAllowed);
        assertEquals(20, AnticheatConfig.opLockSweepIntervalTicks);
        String written = Files.readString(file);
        assertTrue(written.contains("op-lock:"), written);
        assertTrue(written.contains("allowed: []"), written);
        assertTrue(written.contains("sweep-interval-ticks: 20"), written);
        assertTrue(written.contains("EMPTY allowed list means nobody may be op"),
            "the key is documented in the file: " + written);
        assertTrue(written.contains("# ADMIN LOCKS"), "the section is documented in the file: " + written);
    }

    @Test
    void anUnquotedOffIsReadAsOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY + "admin:\n  op-lock:\n    mode: off\n");

        AnticheatConfig.init(file.toFile());

        assertEquals(OpLock.Mode.OFF, AnticheatConfig.opLockMode);
    }

    @Test
    void anOperatorsValuesAreReadAndAMalformedUuidIsKeptApart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY + """
            admin:
              op-lock:
                mode: ENFORCE
                allowed:
                  - 11111111-2222-3333-4444-555555555555
                  - not-a-uuid
                sweep-interval-ticks: 5
            """);

        AnticheatConfig.init(file.toFile());

        assertEquals(OpLock.Mode.ENFORCE, AnticheatConfig.opLockMode);
        assertEquals(Set.of(OWNER), AnticheatConfig.opLockAllowed);
        assertEquals(List.of("not-a-uuid"), AnticheatConfig.opLockInvalidEntries);
        assertEquals(5, AnticheatConfig.opLockSweepIntervalTicks);
    }
}
