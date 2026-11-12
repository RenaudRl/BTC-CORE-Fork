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
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The targeted gains (tracking range, AI, crops, hoppers, network, culling) are opt-in until their
 * bench is green: off in the code, off in the shipped file, and written there.
 */
class TargetedGainsDefaultsTest {

    /** Each option, with the field the hooks read. */
    private static final Map<String, BooleanSupplier> OPT_IN = Map.ofEntries(
        Map.entry("performance.per-entity-tracking-range.enabled", () -> BTCCoreConfig.perEntityTrackingRangeEnabled),
        Map.entry("performance.ai-sensor-loops.enabled", () -> BTCCoreConfig.aiSensorLoopsEnabled),
        Map.entry("performance.simple-farm-checks.enabled", () -> BTCCoreConfig.simpleFarmChecksEnabled),
        Map.entry("performance.network.lazy-non-flush-packets", () -> BTCCoreConfig.networkLazyNonFlushPackets),
        Map.entry("performance.network.fast-varint", () -> BTCCoreConfig.networkFastVarInt),
        Map.entry("performance.network.skip-unchanged-motion", () -> BTCCoreConfig.networkSkipUnchangedMotion),
        Map.entry("performance.network.skip-empty-entity-moves", () -> BTCCoreConfig.networkSkipEmptyEntityMoves),
        Map.entry("performance.network.filter-entity-motion.enabled", () -> BTCCoreConfig.networkFilterEntityMotion),
        Map.entry("performance.raytrace-culling.enabled", () -> BTCCoreConfig.raytraceCullingEnabled));

    @Test
    @DisplayName("the shipped btccore.yml spells each option out, set to false")
    void theShippedTemplateCarriesThemOff() throws IOException, InvalidConfigurationException {
        YamlConfiguration template = new YamlConfiguration();
        try (InputStream in = BTCCoreConfig.class.getClassLoader().getResourceAsStream("btccore.yml")) {
            assertNotNull(in, "btccore.yml must be on the classpath");
            template.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        for (String option : OPT_IN.keySet()) {
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

        OPT_IN.forEach((option, field) -> assertFalse(field.getAsBoolean(), option));
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        for (String option : OPT_IN.keySet()) {
            assertTrue(saved.isBoolean(option), option + " was not written back");
            assertFalse(saved.getBoolean(option), option);
        }
    }
}
