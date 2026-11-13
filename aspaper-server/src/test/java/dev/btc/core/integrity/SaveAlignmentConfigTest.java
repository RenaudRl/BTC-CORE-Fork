package dev.btc.core.integrity;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.playerdata.SaveAlignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code mechanics.save-alignment.*} keys of anticheat.yml: delivered observing, written back documented, read when set. */
class SaveAlignmentConfigTest {

    // identity.server-id is set so that init() has no reason to log through a server that is not there.
    private static final String IDENTITY = "identity:\n  server-id: test\n";

    @Test
    void deliveredObservingWithNoWorldOnQuitAndTheFileIsWrittenBackDocumented(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY);

        AnticheatConfig.init(file.toFile());

        assertEquals(SaveAlignment.Mode.OBSERVE, AnticheatConfig.saveAlignmentMode);
        assertEquals(List.of(), AnticheatConfig.saveAlignmentWorldsOnQuit);
        String written = Files.readString(file);
        assertTrue(written.contains("save-alignment:"), written);
        assertTrue(written.contains("mode: observe"), written);
        assertTrue(written.contains("save-world-on-quit: []"), written);
        assertTrue(written.contains("is not part of this release"), "the mode is documented in the file: " + written);
        assertTrue(written.contains("# MECHANICS INTEGRITY"), "the section is documented in the file: " + written);
    }

    @Test
    void anOperatorsValuesAreReadAndRecognisedAsKeys(@TempDir Path dir) throws IOException {
        // An unrecognised key makes init() warn through the server logger, which is absent here: reaching
        // the assertions proves both keys are registered, so that a real server would not warn about them.
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY + """
            mechanics:
              save-alignment:
                mode: OFF
                save-world-on-quit:
                  - btc_skyblock_*
            """);

        AnticheatConfig.init(file.toFile());

        assertEquals(SaveAlignment.Mode.OFF, AnticheatConfig.saveAlignmentMode);
        assertEquals(List.of("btc_skyblock_*"), AnticheatConfig.saveAlignmentWorldsOnQuit);
    }

    @Test
    void anUnquotedOffIsReadAsOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("anticheat.yml");
        Files.writeString(file, IDENTITY + "mechanics:\n  save-alignment:\n    mode: off\n");

        AnticheatConfig.init(file.toFile());

        assertEquals(SaveAlignment.Mode.OFF, AnticheatConfig.saveAlignmentMode);
    }
}
