package dev.btc.core.performance;

import dev.btc.core.api.BTCCoreAPI;

/**
 * The tracking range one entity carries in place of the range of its type
 * ({@code performance.per-entity-tracking-range}).
 *
 * <p>The idea is SparklyPaper's "per-entity tracking range overrides" (SparklyPower); nothing of
 * its text is copied, SparklyPaper carrying no licence file. Unlike it, the entity is not removed
 * from the tracker and added again when the range changes: the tracker reads the override each
 * time it works out a range, so no viewer sees the entity vanish and come back.
 */
public final class TrackingRangeOverride {

    private TrackingRangeOverride() {}

    /**
     * What an entity holds for a range asked through the API: below 0 clears the override,
     * anything else is brought into the bounds the API documents.
     */
    public static int bounded(int requested) {
        if (requested < 0) return BTCCoreAPI.TRACKING_RANGE_DEFAULT;
        return Math.clamp(requested, BTCCoreAPI.TRACKING_RANGE_MIN, BTCCoreAPI.TRACKING_RANGE_MAX);
    }

    /** The range the tracker starts from: the override when the entity holds one, else its type's. */
    public static int effective(int typeRange, int override) {
        return override < 0 ? typeRange : override;
    }
}
