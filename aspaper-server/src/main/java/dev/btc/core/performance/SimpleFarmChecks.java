package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;

import java.util.function.BooleanSupplier;

/**
 * Crops, stems and farmland that read one block of soil per tick ({@code performance.simple-farm-checks}).
 *
 * <p>Vanilla reads the 3x3 of soil and the eight neighbours of a crop on each of its random ticks, and
 * searches a 9x9x2 box for water on each random tick of each farmland block. Here a crop reads the
 * block under it, moist farmland is left alone until what grows on it is ripe, and only dry farmland
 * looks for water. This changes gameplay (speed no longer depends on the layout of the field), which
 * is why it is opt-in.
 *
 * <p>The idea is SparklyPaper's "Blazingly Simple Farm Checks" (MrPowerGamerBR, SparklyPower);
 * nothing of its text is copied, SparklyPaper carrying no licence file.
 */
public final class SimpleFarmChecks {

    /** Below this a crop would all but stop: {@code 25 / speed} is what the growth roll divides by. */
    public static final float MIN_GROWTH_SPEED = 0.1F;
    /** The best vanilla gives: moist soil all around, rows of different crops. */
    public static final float MAX_GROWTH_SPEED = 10.0F;
    /** What vanilla gives inside a field of one crop, on moist soil. */
    public static final float FIELD_MOIST_GROWTH_SPEED = 5.0F;
    /** What vanilla gives inside a field of one crop, on dry soil. */
    public static final float FIELD_DRY_GROWTH_SPEED = 2.0F;

    /** What a random tick does to farmland that holds no moisture. */
    public enum DryFarmlandTick { NOTHING, MOISTEN, REVERT_TO_BASE_BLOCK }

    private SimpleFarmChecks() {}

    /** A configured speed brought into [{@link #MIN_GROWTH_SPEED}, {@link #MAX_GROWTH_SPEED}]; not-a-number gives the minimum. */
    public static float boundedSpeed(double configured) {
        if (!(configured >= MIN_GROWTH_SPEED)) {
            return MIN_GROWTH_SPEED;
        }
        return (float) Math.min(configured, MAX_GROWTH_SPEED);
    }

    /** Stands for {@code CropBlock#getGrowthSpeed}: only the moisture of the soil under the plant counts. */
    public static float growthSpeed(boolean moistSoilBelow) {
        return moistSoilBelow ? BTCCoreConfig.simpleFarmChecksMoistGrowthSpeed : BTCCoreConfig.simpleFarmChecksDryGrowthSpeed;
    }

    /**
     * Dry farmland: moistened when watered, back to its base block when nothing grows on it.
     * {@code holdsAPlant} is not asked when the farmland is watered.
     */
    public static DryFarmlandTick dryFarmlandTick(BooleanSupplier watered, BooleanSupplier holdsAPlant) {
        if (watered.getAsBoolean()) {
            return DryFarmlandTick.MOISTEN;
        }
        return holdsAPlant.getAsBoolean() ? DryFarmlandTick.NOTHING : DryFarmlandTick.REVERT_TO_BASE_BLOCK;
    }

    /**
     * Whether the soil under a plant that is done growing dries, in one step. Water is not searched
     * for soil that holds no moisture.
     */
    public static boolean driesOut(int moisture, BooleanSupplier watered) {
        return moisture > 0 && !watered.getAsBoolean();
    }
}
