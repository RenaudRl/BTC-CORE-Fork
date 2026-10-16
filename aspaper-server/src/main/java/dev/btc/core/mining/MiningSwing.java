package dev.btc.core.mining;

/**
 * The click-rate limit that applies to an arm swing, given whether a dig is under way.
 *
 * <p>A client that digs sends one swing per tick, 20 a second, which is the normal limit's ceiling:
 * the first network hiccup would show "Slow down!" to a player who is only mining. So while a dig is
 * under way the swings are counted against a higher ceiling, {@code mining-max}. They are still
 * counted: a client that opens a dig once and then floods swings hits that ceiling like any
 * autoclicker.
 *
 * <p>The dig is only ever seen by the server: the game mode stamps {@code lastDig} when a START on a
 * solid block is accepted and on each server tick that finds a solid block being destroyed. A swing
 * never stamps it, or the swings themselves would keep the window open.
 *
 * <p>The grace after the last dig tick covers the pause the client keeps between two digs (vanilla's
 * {@code destroyDelay}, 5 ticks, during which it still swings) and the blocks that break at once.
 */
public final class MiningSwing {

    /** Vanilla's 5-tick pause between two digs, plus one tick of network slack. */
    public static final long GRACE_TICKS = 6L;

    /** No dig seen yet; far enough from any tick that the subtraction cannot overflow. */
    public static final long NEVER = Long.MIN_VALUE / 2;

    private MiningSwing() {
    }

    /**
     * @param now      the game-mode tick
     * @param lastDig  the last tick the server saw a dig, or {@link #NEVER}
     */
    public static boolean isMining(final long now, final long lastDig) {
        final long sinceDig = now - lastDig;
        return sinceDig >= 0 && sinceDig <= GRACE_TICKS; // a negative delta is a clock gone back, not a dig
    }

    /** The clicks-per-second ceiling for a swing handled at {@code now}. */
    public static int limit(final long now, final long lastDig, final int max, final int miningMax) {
        return isMining(now, lastDig) ? Math.max(max, miningMax) : max;
    }
}
