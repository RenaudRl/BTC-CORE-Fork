package dev.btc.core.api.mining;

/**
 * What a {@link BreakSpeedProvider} decides for one block a player has just started digging.
 *
 * <p>Four answers, and only four, so the platform can tell every one of them apart:
 *
 * <ul>
 *   <li>{@link Declined} — the provider has nothing to say about this block: the platform tables
 *       apply alone;</li>
 *   <li>{@link Factor} — the complete break-speed multiplier, written into the player's
 *       {@code minecraft:block_break_speed} attribute; the client digs at that speed on its own;</li>
 *   <li>{@link Refused} — the block must not be dug at all; the dig stops before the first crack;</li>
 *   <li>{@link ServerDriven} — the server, not the client, measures the time and breaks the block.
 *       Only accepted for a vanilla ({@code minecraft:}) block of hardness 0 — a crop — whose time the
 *       attribute cannot change, because vanilla breaks a hardness-0 block in a single tick whatever
 *       the multiplier.</li>
 * </ul>
 *
 * <p>Sealed so a {@code switch} over a decision is exhaustive, and records so a decision carries no
 * state beyond its number: a provider builds one per block and the platform keeps none.
 *
 * @since 26.3 (fork build after 26.3.build.1)
 */
public sealed interface BreakDecision
    permits BreakDecision.Declined, BreakDecision.Factor, BreakDecision.Refused, BreakDecision.ServerDriven {

    /** The provider has nothing to say about this block; the platform tables apply alone. */
    record Declined() implements BreakDecision {
    }

    /**
     * The complete break-speed factor, where {@code 1.0} means vanilla speed.
     *
     * <p>It <b>replaces</b> the platform tables. A factor of zero or less refuses the block, exactly
     * like {@link Refused}; {@code NaN} and the infinities are treated as a decline and logged once.
     *
     * @param factor the multiplier
     */
    record Factor(double factor) implements BreakDecision {
    }

    /**
     * The block must not be dug: the attribute is set to zero and the server returns before it reads
     * any progress — no crack is drawn and nothing is registered as being destroyed.
     */
    record Refused() implements BreakDecision {
    }

    /**
     * The server measures the dig and breaks the block when the progress reaches 1.
     *
     * <p>The progress of each tick is {@code progressPerTick} times the player's environment — haste,
     * mining fatigue, the {@code block_break_speed} attribute without the platform's own modifier,
     * water and air — so the number given here carries the tool, the tier and every bonus, and none
     * of what the server reads tick after tick. {@code 1 / progressPerTick} is the dig time in ticks
     * for a player standing on the ground, dry and without effects.
     *
     * <p><b>Accepted only for a block state of hardness 0 in the {@code minecraft} namespace</b>, and
     * only while {@code mining.server-driven.enabled} is set and the player is not opted out (see
     * {@code BTCCoreAPI#setServerDrivenOptOut}); anywhere else it is treated as {@link Declined} and
     * logged once. A block of another plugin (CraftEngine) is never finished by the platform.
     *
     * <p>A value of zero or less refuses the block, like {@link Refused}; {@code NaN} and the
     * infinities are treated as a decline and logged once.
     *
     * @param progressPerTick the share of the block dug per tick, before the environment
     */
    record ServerDriven(double progressPerTick) implements BreakDecision {
    }

    /** The decline. */
    static BreakDecision declined() {
        return new Declined();
    }

    /** A factor; see {@link Factor}. */
    static BreakDecision factor(final double factor) {
        return new Factor(factor);
    }

    /** The refusal. */
    static BreakDecision refused() {
        return new Refused();
    }

    /** A dig the server measures; see {@link ServerDriven}. */
    static BreakDecision serverDriven(final double progressPerTick) {
        return new ServerDriven(progressPerTick);
    }
}
