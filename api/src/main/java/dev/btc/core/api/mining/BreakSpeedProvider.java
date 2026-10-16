package dev.btc.core.api.mining;

import java.util.OptionalDouble;

/**
 * Supplies the break speed that depends on the player, and owns the curve.
 *
 * <p>The platform knows blocks and tools; it does not know progression. A server that ties digging
 * speed to a tier, a profession level or any other statistic binds one of these and answers with the
 * complete multiplier for the block just entered. The platform writes that number into the player's
 * {@code minecraft:block_break_speed} attribute.
 *
 * <p><b>The answer replaces the platform tables, it does not multiply them.</b> The tool-affinity
 * and hardness-correction tables are still computed and handed over in
 * {@link BreakSpeedContext#tableMultiplier()}, so a provider may build on them or ignore them, but
 * applying them again on top of the answer would count the same thing twice. They apply on their own
 * only when no provider is bound or when the bound one declines.
 *
 * <p><b>Two ways to answer.</b> The platform asks {@link #decide}. A provider written before it only
 * implements {@link #statisticMultiplier}, which {@code decide} wraps by default: such a provider
 * keeps working unchanged and can never ask for a server-driven dig. A provider that overrides
 * {@code decide} should still implement {@code statisticMultiplier} sensibly — an older platform build
 * only knows that one.
 *
 * <p><b>Called on the thread that ticks the player, inside the packet handler</b> (the main thread
 * on this fork, the owning region thread under a regionised build). It must return from memory:
 * no file or database access, no blocking wait, no scheduler round trip. A provider that does any of
 * that here stalls the player's first swing, and every other player on the same thread with it.
 */
@FunctionalInterface
public interface BreakSpeedProvider {

    /**
     * The complete break-speed factor for this block, where {@code 1.0} means "vanilla speed".
     *
     * <ul>
     *   <li>a value above zero is the factor, used as is;</li>
     *   <li>{@code 0.0} (or any value below) <b>refuses the block</b>: the dig does not start. The
     *       attribute is set to zero and sent, and the server returns before it computes any
     *       progress, exactly as if a {@code BlockDamageEvent} had been cancelled — no crack is
     *       drawn, nothing is registered as being destroyed;</li>
     *   <li>{@code NaN} and the infinities are treated as a decline, and logged once.</li>
     * </ul>
     *
     * @param context the block just entered
     * @return the factor, or {@link OptionalDouble#empty()} to decline — the platform tables then
     *         apply alone, which is a meaningful result and not a failure
     */
    OptionalDouble statisticMultiplier(BreakSpeedContext context);

    /**
     * The decision for this block: decline, factor, refusal, or a dig the server measures.
     *
     * <p>Called once per block entered, on {@code START_DESTROY_BLOCK}, and again only if another
     * block takes the place of the one being dug. The default wraps
     * {@link #statisticMultiplier}: an empty answer is {@link BreakDecision.Declined}, a value is a
     * {@link BreakDecision.Factor} — which is exactly what the platform did before this method
     * existed. A {@code null} answer, or an exception, is a decline.
     *
     * @param context the block just entered
     * @return the decision, never {@code null}
     * @since 26.3 (fork build after 26.3.build.1)
     */
    default BreakDecision decide(final BreakSpeedContext context) {
        final OptionalDouble factor = this.statisticMultiplier(context);
        if (factor == null || factor.isEmpty()) return BreakDecision.declined();
        return BreakDecision.factor(factor.getAsDouble());
    }
}
