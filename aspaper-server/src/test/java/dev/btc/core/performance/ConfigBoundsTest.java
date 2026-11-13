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

/** Values out of range in btccore.yml are brought back within their bounds. One load per JVM: one test. */
class ConfigBoundsTest {

    @Test
    @DisplayName("a value out of range is held at its bound, whatever the file says")
    void outOfRangeValuesAreBounded(@TempDir Path dir) throws IOException {
        File file = dir.resolve("btccore.yml").toFile();
        Files.writeString(file.toPath(), """
            config-version: 1
            performance:
              hopper:
                throttling-ticks: 0
              sleeping-block-entities:
                wake-interval-ticks: 5
            world:
              freeze-at-rest:
                warm:
                  tick-interval: 1000000
                  max-catch-up-ticks: -3
            """);

        BTCCoreConfig.load(file);

        assertEquals(1, BTCCoreConfig.hopperThrottlingTicks, "a throttle of 0 divides by zero on every hopper tick");
        assertEquals(20, BTCCoreConfig.sleepingBlockEntitiesWakeIntervalTicks);
        assertEquals(BTCCoreConfig.WORLD_WARM_MAX_TICKS, BTCCoreConfig.worldWarmTickInterval);
        assertEquals(0, BTCCoreConfig.worldWarmMaxCatchUpTicks);
    }
}
