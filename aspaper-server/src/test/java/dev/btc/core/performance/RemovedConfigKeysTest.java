package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A setting deleted from btccore.yml is ignored out loud, never in silence.
 *
 * <p>Lives in this package rather than next to {@code BTCCoreConfig} because the test task only
 * runs the packages listed in the build file, and a test that is not listed stays green by not
 * running.
 */
class RemovedConfigKeysTest {

    private static YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(content);
        return config;
    }

    @Test
    @DisplayName("a file still carrying async.entity-tracker gets exactly one warning, naming the key")
    void oneWarningNamesTheRemovedKey() throws InvalidConfigurationException {
        List<String> warnings = BTCCoreConfig.removedKeyWarnings(yaml("""
            async:
              entity-tracker:
                enabled: true
                threads: 2
              mob-spawning:
                enabled: true
            """));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'async.entity-tracker'"), warnings.get(0));
    }

    @Test
    @DisplayName("each removed key present is warned about once, on its own line")
    void everyRemovedKeyIsNamed() throws InvalidConfigurationException {
        List<String> warnings = BTCCoreConfig.removedKeyWarnings(yaml("""
            async:
              entity-tracker:
                enabled: false
              pathfinding:
                enabled: true
                reject-policy: FLUSH_ALL
            """));

        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals(1, warnings.stream().filter(w -> w.contains("'async.entity-tracker'")).count());
        assertEquals(1, warnings.stream().filter(w -> w.contains("'async.pathfinding'")).count());
    }

    @Test
    @DisplayName("the shipped default btccore.yml carries no removed key")
    void defaultTemplateIsClean() throws IOException, InvalidConfigurationException {
        YamlConfiguration template = new YamlConfiguration();
        try (InputStream in = BTCCoreConfig.class.getClassLoader().getResourceAsStream("btccore.yml")) {
            assertNotNull(in, "btccore.yml must be on the classpath");
            template.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        assertEquals(List.of(), BTCCoreConfig.removedKeyWarnings(template));
    }
}
