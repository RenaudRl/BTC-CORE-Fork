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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            performance:
              scoreboard-optimization: true
              nbt-compression-cache: true
              chunk-prefetch: true
            """));

        assertEquals(4, warnings.size(), warnings.toString());
        for (String removed : List.of("async.entity-tracker", "async.pathfinding",
                "performance.scoreboard-optimization", "performance.nbt-compression-cache")) {
            assertEquals(1, warnings.stream().filter(w -> w.contains("'" + removed + "'")).count(), removed);
        }
    }

    @Test
    @DisplayName("warnRemovedKeys warns about a removed key present, then deletes it from the config")
    void warnRemovedKeysWarnsThenDeletesTheKey() throws InvalidConfigurationException {
        YamlConfiguration config = yaml("""
            async:
              entity-tracker:
                enabled: true
              mob-spawning:
                enabled: true
            """);
        List<String> sink = new ArrayList<>();

        BTCCoreConfig.warnRemovedKeys(config, sink::add);

        assertEquals(1, sink.size(), sink.toString());
        assertTrue(sink.get(0).contains("'async.entity-tracker'"), sink.get(0));
        assertFalse(config.contains("async.entity-tracker", true));
        assertTrue(config.getBoolean("async.mob-spawning.enabled"), "unrelated keys must survive");
    }

    @Test
    @DisplayName("warnRemovedKeys with no removed key stays silent and leaves the config unchanged")
    void warnRemovedKeysLeavesACleanConfigAlone() throws InvalidConfigurationException {
        String content = """
            async:
              mob-spawning:
                enabled: true
            """;
        YamlConfiguration config = yaml(content);
        List<String> sink = new ArrayList<>();

        BTCCoreConfig.warnRemovedKeys(config, sink::add);

        assertEquals(List.of(), sink);
        assertEquals(yaml(content).saveToString(), config.saveToString());
    }

    @Test
    @DisplayName("warnRemovedKeys on a second pass over the same config stays silent")
    void warnRemovedKeysDoesNotRepeatItself() throws InvalidConfigurationException {
        YamlConfiguration config = yaml("""
            async:
              entity-tracker:
                enabled: true
            performance:
              scoreboard-optimization: true
            """);
        BTCCoreConfig.warnRemovedKeys(config, w -> { });
        List<String> secondPass = new ArrayList<>();

        BTCCoreConfig.warnRemovedKeys(config, secondPass::add);

        assertEquals(List.of(), secondPass);
    }

    @Test
    @DisplayName("warnRemovedKeys drops a parent section that the removal leaves empty")
    void warnRemovedKeysPrunesTheEmptyParentSection() throws InvalidConfigurationException {
        YamlConfiguration config = yaml("""
            async:
              entity-tracker:
                enabled: true
            """);

        BTCCoreConfig.warnRemovedKeys(config, w -> { });

        assertFalse(config.contains("async", true), config.saveToString());
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
