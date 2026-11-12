package dev.btc.core.performance;

/**
 * Which entity packets of the tracker are worth sending ({@code performance.network}).
 *
 * <p>{@link #emptyMove} is Purpur's "Dont send useless entity packets" (William Blake Galbreath, MIT),
 * as carried by Leaf. {@link #keepsMotion} follows Canvas' "Filter ClientboundSetEntityMotionPacket"
 * (Dueris, GPL-3.0-only), as carried by Leaf, narrowed: an explicit change of velocity (knockback, a
 * plugin, a deflected projectile) and a gliding entity are always sent, which Canvas filters too.
 */
public final class EntityPacketRules {

    /** The tracker's own threshold, squared, for "the entity is not where the players last saw it". */
    public static final double MOVED_DISTANCE_SQR = 7.62939453125E-6D;

    private EntityPacketRules() {}

    /**
     * A relative move that moves nothing and turns nothing: the one the tracker sends every few
     * seconds to an entity that stands still. A full position sync is never one.
     */
    public static boolean emptyMove(boolean relativeMove, boolean sendsRotation, int deltaX, int deltaY, int deltaZ) {
        return relativeMove && !sendsRotation && deltaX == 0 && deltaY == 0 && deltaZ == 0;
    }

    /**
     * Whether the velocity of an entity goes to the players that see it. The stream of small changes
     * of a walking mob does not: the client moves it from its position packets.
     *
     * @param explicitChange the velocity was set on purpose since the last tracker tick ({@code needsSync})
     * @param gliding the entity flies with an elytra
     * @param typeAlwaysSent the type is listed in {@code filter-entity-motion.always-send}
     * @param item the entity is a dropped item, whose client-side position drifts without its velocity
     * @param moved the entity is not where the players last saw it
     */
    public static boolean keepsMotion(boolean explicitChange, boolean gliding, boolean typeAlwaysSent, boolean item, boolean moved) {
        return explicitChange || gliding || typeAlwaysSent || item && moved;
    }
}
