package dev.btc.core.mining;

/**
 * Whether an arm swing belongs to a block being dug, so the click-rate limiter must not count it.
 *
 * <p>A client that digs sends one swing per tick, 20 a second, which is the limiter's ceiling: the
 * first network hiccup would show "Slow down!" to a player who is only mining. The swing of a dig is
 * not a click; the limiter keeps its whole effect on every other swing (attack clicks, autoclickers).
 *
 * <p>The grace after the last dig tick covers the pause the client keeps between two digs (vanilla's
 * {@code destroyDelay}, 5 ticks, during which it still swings) and the blocks that break at once,
 * which never register a dig on the server.
 */
public final class MiningSwing {

    /** Vanilla's 5-tick pause between two digs, plus one tick of network slack. */
    public static final long GRACE_TICKS = 6L;

    /** No dig seen yet; far enough from any tick that the subtraction cannot overflow. */
    public static final long NEVER = Long.MIN_VALUE / 2;

    private MiningSwing() {
    }

    /**
     * @param digging  a dig is registered now (the game mode's {@code isDestroyingBlock}, or a dig the
     *                 server measures)
     * @param now      the game-mode tick
     * @param lastDig  the last tick a dig was seen or started, or {@link #NEVER}
     */
    public static boolean isMining(final boolean digging, final long now, final long lastDig) {
        return digging || now - lastDig <= GRACE_TICKS;
    }
}
