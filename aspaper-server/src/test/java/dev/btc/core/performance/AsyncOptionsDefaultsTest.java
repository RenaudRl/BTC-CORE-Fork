package dev.btc.core.performance;

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
 * The options that move work off the tick thread are opt-in: off in the code, off in the shipped
 * file, and written there so an operator can find them.
 */
class AsyncOptionsDefaultsTest {

    private static final List<String> OPT_IN = List.of("async.parallel-tracker.enabled", "async.async-pathfinding.enabled");

    @Test
    @DisplayName("the shipped btccore.yml spells each option out, set to false")
    void theShippedTemplateCarriesThemOff() throws IOException, InvalidConfigurationException {
        YamlConfiguration template = new YamlConfiguration();
        try (InputStream in = BTCCoreConfig.class.getClassLoader().getResourceAsStream("btccore.yml")) {
            assertNotNull(in, "btccore.yml must be on the classpath");
            template.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        for (String option : OPT_IN) {
            assertTrue(template.isBoolean(option), option + " is missing from the template");
            assertFalse(template.getBoolean(option), option);
        }
    }

    @Test
    @DisplayName("a file that says nothing about them loads with every option off, and gets them written as false")
    void anOlderFileLoadsWithThemOff(@TempDir Path dir) throws IOException {
        File file = dir.resolve("btccore.yml").toFile();
        Files.writeString(file.toPath(), "config-version: 1\n");

        BTCCoreConfig.load(file);

        assertFalse(BTCCoreConfig.parallelTrackerEnabled);
        assertFalse(BTCCoreConfig.asyncPathfindingEnabled);
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        for (String option : OPT_IN) {
            assertTrue(saved.isBoolean(option), option + " was not written back");
            assertFalse(saved.getBoolean(option), option);
        }
    }
}
