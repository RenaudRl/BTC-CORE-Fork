package dev.btc.core.mining;

/**
 * Decides when the server looks again at what a player aims at, for the server-driven mode.
 *
 * <p>The look itself is a ray cast through the world, the dearest thing the mode does at every tick.
 * It is skipped twice over: it only runs every {@code intervalTicks}, and only when the player's eye
 * moved or turned since the last one — a player standing still and staring at a crop keeps the answer
 * of the previous look. The answer is still refreshed after {@link #MAX_REUSE_TICKS} even without a
 * move, so a crop that grows or is planted under a fixed gaze is eventually seen.
 *
 * <p>Mutable, one per player, used only on the thread that ticks that player (see
 * {@link ServerDigClock} for why that is not a reason to allocate).
 */
public final class AimThrottle {

    /** How long a look is reused at most when the player does not move. */
    public static final int MAX_REUSE_TICKS = 20;

    /** Closer than this, two eye positions or angles are the same. */
    private static final double SAME_POSE_EPSILON = 1.0e-4;

    private long lastCheck = Long.MIN_VALUE / 2;
    private long lastLook = Long.MIN_VALUE / 2;
    private boolean hasPose;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean lastHit;

    /** Whether this tick is one of the checks. */
    public boolean isDue(final long now, final int intervalTicks) {
        if (now - this.lastCheck < intervalTicks) return false;
        this.lastCheck = now;
        return true;
    }

    /**
     * Whether a new look is needed for this pose, or the previous answer still holds.
     *
     * <p>Records the pose when it answers {@code true}; the caller then reports the result with
     * {@link #record}.
     */
    public boolean needsLook(final long now, final double x, final double y, final double z,
                             final float yaw, final float pitch) {
        final boolean samePose = this.hasPose
            && Math.abs(x - this.x) < SAME_POSE_EPSILON
            && Math.abs(y - this.y) < SAME_POSE_EPSILON
            && Math.abs(z - this.z) < SAME_POSE_EPSILON
            && Math.abs(yaw - this.yaw) < SAME_POSE_EPSILON
            && Math.abs(pitch - this.pitch) < SAME_POSE_EPSILON;
        if (samePose && now - this.lastLook < MAX_REUSE_TICKS) return false;
        this.hasPose = true;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.lastLook = now;
        return true;
    }

    /** Stores the result of the look just made. */
    public void record(final boolean hit) {
        this.lastHit = hit;
    }

    /** The result of the last look: whether an armed block was in sight. */
    public boolean lastHit() {
        return this.lastHit;
    }

    /** Forgets the pose and the answer, so the next check looks again. */
    public void reset() {
        this.hasPose = false;
        this.lastHit = false;
        this.lastCheck = Long.MIN_VALUE / 2;
        this.lastLook = Long.MIN_VALUE / 2;
    }
}
