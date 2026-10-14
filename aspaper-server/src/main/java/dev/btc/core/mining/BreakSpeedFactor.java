package dev.btc.core.mining;

import java.util.OptionalDouble;

/**
 * The arithmetic of the break-speed hook, kept free of server types so it can be tested on its own.
 *
 * <p>{@link BlockBreakSpeedRegistry} reads the player, the block and the attribute; every decision it
 * takes from those readings lives here: which factor wins between the platform tables and the bound
 * provider, whether the answer is a refusal, and whether the client has to be told.
 */
final class BreakSpeedFactor {

    private BreakSpeedFactor() {
    }

    /** Two factors closer than this are the same factor: no packet is worth sending for the gap. */
    static final double SAME_FACTOR_EPSILON = 1.0e-9;

    /**
     * What the hook decided for one block.
     *
     * @param factor  the final multiplier written to {@code minecraft:block_break_speed}, never
     *                negative and never {@code NaN}
     * @param refused whether the provider refused the block outright (a factor of zero or less): the
     *                caller must then stop the dig before it starts
     */
    record Resolution(double factor, boolean refused) {
    }

    /**
     * Whether a value handed back by a provider can be used as a factor.
     *
     * <p>{@code NaN} and the infinities are rejected rather than clamped: a provider that divides by
     * a zero hardness has a bug, and writing its answer to the attribute would turn a whole mining
     * session into a stuck or instant one. The caller declines the answer and says so.
     */
    static boolean isUsable(final double providerFactor) {
        return Double.isFinite(providerFactor);
    }

    /**
     * Picks the factor for one block.
     *
     * <p><b>A provider's factor replaces the tables, it does not multiply them.</b> The bound
     * provider carries the whole curve — affinity, hardness and tier — so multiplying by the platform
     * tables again would count the same thing twice. The tables apply alone only when no provider is
     * bound, or when the bound one declines ({@link OptionalDouble#empty()}).
     *
     * <p>A factor of zero or less is a refusal: the factor written is exactly {@code 0.0}, the
     * progression of every tick is nil, and {@link Resolution#refused()} is set so the caller can
     * stop the dig at the first hit instead of letting the server draw a first crack.
     *
     * @param table    the factor the platform tables resolved, {@code 1.0} meaning no change
     * @param provided the provider's answer, or empty when no provider is bound or it declined; an
     *                 unusable value (see {@link #isUsable}) counts as a decline
     */
    static Resolution resolve(final double table, final OptionalDouble provided) {
        if (provided.isPresent() && isUsable(provided.getAsDouble())) {
            final double factor = provided.getAsDouble();
            if (factor <= 0.0) return new Resolution(0.0, true);
            return new Resolution(factor, false);
        }
        return new Resolution(table, false);
    }

    /**
     * The amount of the {@code ADD_MULTIPLIED_TOTAL} modifier that yields a final factor.
     *
     * <p>That operation scales the finished value, so a factor of zero is an amount of {@code -1.0}.
     */
    static double modifierAmount(final double factor) {
        return factor - 1.0;
    }

    /**
     * Whether the client must be sent the attribute for a new factor.
     *
     * <p>The modifier the platform owns sits on the player's own attribute instance, which the client
     * is always resynchronised from; its amount is therefore the last factor the client was given.
     * Sending the same one again at every {@code START_DESTROY} is the whole cost of a client that
     * spams the packet, so an unchanged factor sends nothing.
     *
     * @param previousAmount the amount of the modifier currently on the attribute, or {@code null}
     *                       when there is none (the client holds the unmodified value, factor 1.0)
     * @param factor         the factor just decided
     */
    static boolean needsPacket(final Double previousAmount, final double factor) {
        final double current = previousAmount == null ? 0.0 : previousAmount;
        return Math.abs(modifierAmount(factor) - current) > SAME_FACTOR_EPSILON;
    }
}
