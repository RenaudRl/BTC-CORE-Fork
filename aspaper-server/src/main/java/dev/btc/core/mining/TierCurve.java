package dev.btc.core.mining;

import java.util.List;

/**
 * The break-speed multiplier owed to each tool tier.
 *
 * <p>Pure data and one lookup: no server state, no NMS, so it can be tested on its own.
 *
 * <p>Tiers are one-based, as they are in game. A tier outside the curve is clamped rather than
 * refused — a progression system that grows past twelve tiers must widen the curve in
 * {@code btccore.yml}, and until it does, the last entry is the honest answer.
 */
public final class TierCurve {

    /**
     * The default curve, from {@code design-vitesse-de-casse.md} §3: 100 % at tier 1, 500 % at
     * tier 12.
     *
     * <p>It ends at 5.0 and not at 2.1 because vanilla already spans 4.5× between a wooden and a
     * netherite pickaxe. A gentler curve would make the last tier slower than a vanilla diamond
     * pickaxe, and the end of the game would feel like a regression.
     */
    public static final List<Double> DEFAULT = List.of(
        1.0, 1.25, 1.5, 1.8, 2.1, 2.45, 2.8, 3.2, 3.6, 4.0, 4.5, 5.0
    );

    private final List<Double> steps;

    public TierCurve(final List<Double> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("a break-speed curve needs at least one step");
        }
        this.steps = List.copyOf(steps);
    }

    /** The number of tiers the curve describes. */
    public int size() {
        return this.steps.size();
    }

    /**
     * The multiplier for a tier.
     *
     * @param tier the one-based tier
     * @return the multiplier, clamped to the first entry below tier 1 and to the last entry above
     *         the curve's length
     */
    public double multiplier(final int tier) {
        if (tier <= 1) return this.steps.get(0);
        if (tier >= this.steps.size()) return this.steps.get(this.steps.size() - 1);
        return this.steps.get(tier - 1);
    }
}
