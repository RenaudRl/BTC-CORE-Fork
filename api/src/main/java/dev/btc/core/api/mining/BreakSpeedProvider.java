package dev.btc.core.api.mining;

import java.util.OptionalDouble;

/**
 * Supplies the part of the break speed that depends on the player rather than on the block.
 *
 * <p>The platform knows blocks and tools; it does not know progression. A server that ties digging
 * speed to a tier, a profession level or any other statistic binds one of these and answers with the
 * factor that statistic is worth. The platform multiplies it by its own tables and writes the result
 * into the player's {@code minecraft:block_break_speed} attribute.
 *
 * <p><b>Called on the region thread that owns the block, inside the packet handler.</b> It must
 * return from memory: a provider that queries a database here stalls the player's first swing.
 */
@FunctionalInterface
public interface BreakSpeedProvider {

    /**
     * The statistic factor for this block, where {@code 1.0} means "no change".
     *
     * @param context the block just entered
     * @return the factor, or {@link OptionalDouble#empty()} to decline — the platform tables then
     *         apply alone, which is a meaningful result and not a failure
     */
    OptionalDouble statisticMultiplier(BreakSpeedContext context);
}
