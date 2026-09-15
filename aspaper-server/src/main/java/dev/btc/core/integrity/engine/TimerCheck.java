package dev.btc.core.integrity.engine;

/**
 * Movement-packet cadence, measured as a balance against real time.
 *
 * <p>A vanilla client sends at most one movement packet per client tick — 50 ms — and none at all
 * while nothing changes. A client that sends faster is running its game loop faster than the
 * server's: the classic Timer, which also underlies most speed and fly cheats since it multiplies
 * everything the client does per real second.
 *
 * <p>The measurement is a running balance: every packet earns {@link #TICK_MILLIS} of credit and
 * the real time elapsed since the previous packet is charged against it. An honest client oscillates
 * around zero. A client ahead of real time drifts positive and crosses {@link #VIOLATION_MILLIS}.
 *
 * <p>Lag is the reason the balance is allowed to go negative: a client that stalls for a second
 * and then flushes twenty packets in a burst is honest, and the debt it accumulated during the
 * stall pays for the burst. {@link #MAX_DEBT_MILLIS} bounds that forgiveness so that a stall
 * cannot bank unlimited credit for a later burst — the value is a guess to be replaced by the
 * measurement of 4.1, never by a feeling.
 *
 * <p>Pure: no clock, no world. The caller passes {@code nowNanos} so a test can drive it tick by tick.
 */
final class TimerCheck {

    /** One client tick. What a packet is worth. */
    static final double TICK_MILLIS = 50.0;

    /** Ahead of real time by this much, the cadence is no longer explainable by jitter. */
    static final double VIOLATION_MILLIS = 300.0;

    /** How far behind real time the balance may fall; bounds the burst forgiven after a stall. */
    static final double MAX_DEBT_MILLIS = -3_000.0;

    private TimerCheck() {
    }

    /**
     * Accounts for one movement packet.
     *
     * @return how many milliseconds ahead of real time the client is when it crosses the threshold,
     *     or {@code 0} when the cadence is acceptable. The balance is reset on a violation so that
     *     one sustained burst is reported once per threshold crossed, not once per packet.
     */
    static double observe(final SessionState state, final long nowNanos) {
        final long last = state.lastMoveNanos();
        state.lastMoveNanos(nowNanos);
        if (last == 0) {
            // The first packet starts the clock. It cannot be early or late relative to nothing.
            return 0;
        }
        final double elapsedMillis = (nowNanos - last) / 1_000_000.0;
        double balance = state.timerBalanceMillis() + TICK_MILLIS - elapsedMillis;
        if (balance < MAX_DEBT_MILLIS) {
            balance = MAX_DEBT_MILLIS;
        }
        if (balance > VIOLATION_MILLIS) {
            state.timerBalanceMillis(0);
            return balance;
        }
        state.timerBalanceMillis(balance);
        return 0;
    }
}
