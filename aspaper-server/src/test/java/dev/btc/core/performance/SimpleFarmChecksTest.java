package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.performance.SimpleFarmChecks.DryFarmlandTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SimpleFarmChecksTest {

    private static final int MOIST = 7;
    private static final int DRY = 0;
    private static final BooleanSupplier YES = () -> true;
    private static final BooleanSupplier NO = () -> false;
    private static final BooleanSupplier NOT_ASKED = () -> fail("must not be asked");

    @Test
    @DisplayName("dry farmland is moistened when watered, without looking at what grows on it")
    void wateredDryFarmlandIsMoistened() {
        assertEquals(DryFarmlandTick.MOISTEN, SimpleFarmChecks.dryFarmlandTick(YES, NOT_ASKED));
    }

    @Test
    @DisplayName("unwatered dry farmland stays while a plant grows on it, and goes back to its base block when bare")
    void unwateredDryFarmlandDependsOnThePlant() {
        assertEquals(DryFarmlandTick.NOTHING, SimpleFarmChecks.dryFarmlandTick(NO, YES));
        assertEquals(DryFarmlandTick.REVERT_TO_BASE_BLOCK, SimpleFarmChecks.dryFarmlandTick(NO, NO));
    }

    @Test
    @DisplayName("under a ripe plant, moist soil dries only when unwatered, and water is not searched for dry soil")
    void soilDriesUnderARipePlantOnlyWhenUnwatered() {
        assertTrue(SimpleFarmChecks.driesOut(MOIST, NO));
        assertFalse(SimpleFarmChecks.driesOut(MOIST, YES));
        assertFalse(SimpleFarmChecks.driesOut(DRY, NOT_ASKED));
    }

    @Test
    @DisplayName("a configured speed is brought into vanilla's scale, not-a-number included")
    void speedsAreBounded() {
        assertEquals(SimpleFarmChecks.MIN_GROWTH_SPEED, SimpleFarmChecks.boundedSpeed(0.0));
        assertEquals(SimpleFarmChecks.MIN_GROWTH_SPEED, SimpleFarmChecks.boundedSpeed(-3.0));
        assertEquals(SimpleFarmChecks.MIN_GROWTH_SPEED, SimpleFarmChecks.boundedSpeed(Double.NaN));
        assertEquals(SimpleFarmChecks.MAX_GROWTH_SPEED, SimpleFarmChecks.boundedSpeed(64.0));
        assertEquals(3.5F, SimpleFarmChecks.boundedSpeed(3.5));
    }

    @Test
    @DisplayName("a crop gets vanilla's field speeds until the file says otherwise, then the speeds of the file, bounded")
    void speedsComeFromTheFile(@TempDir Path dir) throws IOException {
        // BTCCoreConfig loads once per JVM (one JVM per test class): before it, the fields hold the defaults.
        assertEquals(SimpleFarmChecks.FIELD_MOIST_GROWTH_SPEED, SimpleFarmChecks.growthSpeed(true));
        assertEquals(SimpleFarmChecks.FIELD_DRY_GROWTH_SPEED, SimpleFarmChecks.growthSpeed(false));

        File file = dir.resolve("btccore.yml").toFile();
        Files.writeString(file.toPath(), """
            config-version: 1
            performance:
              simple-farm-checks:
                moist-growth-speed: 8.0
                dry-growth-speed: 99.0
            """);
        BTCCoreConfig.load(file);

        assertEquals(8.0F, SimpleFarmChecks.growthSpeed(true));
        assertEquals(SimpleFarmChecks.MAX_GROWTH_SPEED, SimpleFarmChecks.growthSpeed(false));
    }
}
