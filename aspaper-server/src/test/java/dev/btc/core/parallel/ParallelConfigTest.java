package dev.btc.core.parallel;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 0 of the port map: the switches are off everywhere, and frozen by the first load - worlds
 * already running must not change thread because a file was read again.
 */
class ParallelConfigTest {

    private static final List<String> OPT_IN = List.of("parallel-world-ticking.enabled", "world.quarantine.enabled");
    private static final String SAVE_BEFORE_UNLOAD = "world.quarantine.save-before-unload";

    // One test on purpose: BTCCoreConfig loads once per JVM, so the order of the three checks matters.
    @Test
    @DisplayName("off in the shipped file, off for a file that predates it, and a second load cannot switch it on")
    void off_by_default_and_frozen_by_the_first_load(@TempDir Path dir) throws IOException, InvalidConfigurationException {
        YamlConfiguration template = new YamlConfiguration();
        try (InputStream in = BTCCoreConfig.class.getClassLoader().getResourceAsStream("btccore.yml")) {
            assertNotNull(in, "btccore.yml must be on the classpath");
            template.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String option : OPT_IN) {
            assertTrue(template.isBoolean(option), option + " is missing from the template");
            assertFalse(template.getBoolean(option), option);
        }
        assertTrue(template.getBoolean(SAVE_BEFORE_UNLOAD), "a quarantined world is saved unless the operator says otherwise");

        File older = dir.resolve("btccore.yml").toFile();
        Files.writeString(older.toPath(), "config-version: 1\n");
        BTCCoreConfig.load(older);

        assertFalse(BTCCoreConfig.parallelWorldTickingEnabled);
        assertFalse(BTCCoreConfig.worldQuarantineEnabled);
        assertTrue(BTCCoreConfig.worldQuarantineSaveBeforeUnload);
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(older);
        for (String option : OPT_IN) {
            assertTrue(saved.isBoolean(option), option + " was not written back");
            assertFalse(saved.getBoolean(option), option);
        }
        assertTrue(saved.getBoolean(SAVE_BEFORE_UNLOAD), SAVE_BEFORE_UNLOAD + " was not written back as true");

        File switchedOn = dir.resolve("switched-on.yml").toFile();
        Files.writeString(switchedOn.toPath(),
            "config-version: 1\nparallel-world-ticking:\n  enabled: true\nworld:\n  quarantine:\n    enabled: true\n");
        BTCCoreConfig.load(switchedOn);

        assertFalse(BTCCoreConfig.parallelWorldTickingEnabled, "a second load moved running worlds to other threads");
        assertFalse(BTCCoreConfig.worldQuarantineEnabled, "a second load switched the quarantine on under a running server");
    }
}
