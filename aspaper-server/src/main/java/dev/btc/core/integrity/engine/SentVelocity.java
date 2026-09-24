package dev.btc.core.integrity.engine;

/**
 * The velocity actually sent to a player when {@code PlayerVelocityEvent} fires.
 *
 * <p>The event does not always carry it. On the attack path ({@code Player#attack}), CraftBukkit
 * builds the event from the target's movement <em>before</em> the knockback, then sends a packet
 * built from the entity — which by then carries the knockback — and only afterwards restores the old
 * movement on the server side. A listener therefore sees a stale vector in the event and the real
 * one on the player. On every other path ({@code ServerEntity#sendChanges}) the two are equal.
 *
 * <p>The larger of the two is kept. That is exactly the sent vector on both vanilla paths; when a
 * plugin <em>lowered</em> the event vector on the attack path, it over-allows by the difference —
 * acceptable while the checks only observe, and to be revisited with an outgoing-packet read if the
 * journal ever shows it (measured on 24/09/2026: 217 honest {@code speed} lines in ten minutes came
 * from reading the event alone).
 */
public final class SentVelocity {

    private SentVelocity() {
    }

    /**
     * @param eventVector {@code PlayerVelocityEvent#getVelocity()}, as x, y, z
     * @param currentVector the player's velocity while the event is being handled, as x, y, z
     * @return the vector to fold into the prediction
     */
    public static double[] of(final double[] eventVector, final double[] currentVector) {
        return squaredLength(currentVector) > squaredLength(eventVector) ? currentVector : eventVector;
    }

    private static double squaredLength(final double[] v) {
        return v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
    }
}
