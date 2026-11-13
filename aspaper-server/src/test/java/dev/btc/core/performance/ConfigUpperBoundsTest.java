package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ConfigBoundsTest} for the upper bounds: a class of its own, BTCCoreConfig loads once per JVM. */
class ConfigUpperBoundsTest {

    @Test
    @DisplayName("a value above its range is held at its upper bound")
    void tooHighValuesAreBounded(@TempDir Path dir) throws IOException {
        File file = dir.resolve("btccore.yml").toFile();
        Files.writeString(file.toPath(), """
            config-version: 1
            performance:
              sleeping-block-entities:
                wake-interval-ticks: 1000000
            world:
              freeze-at-rest:
                warm:
                  max-catch-up-ticks: 1000000
            """);

        BTCCoreConfig.load(file);

        assertEquals(1200, BTCCoreConfig.sleepingBlockEntitiesWakeIntervalTicks);
        assertEquals(BTCCoreConfig.WORLD_WARM_MAX_TICKS, BTCCoreConfig.worldWarmMaxCatchUpTicks);
    }
}
