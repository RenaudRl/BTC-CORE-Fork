package dev.btc.core.mining;

/**
 * One player's server-driven mode and the dig the server measures for them, without any server type.
 *
 * <p><b>Two things in one place, because they gate each other.</b> The <em>mode</em> is the period
 * during which the client is shown a {@code block_break_speed} of 0, so it predicts no break and
 * sends no {@code STOP}: every dig started then has to be measured and finished by the server. The
 * <em>dig</em> is that measurement. The mode may not end while a dig runs, and a dig only runs
 * inside the mode.
 *
 * <p><b>Why an explicit sum and not vanilla's {@code rate × elapsed}.</b> Vanilla multiplies the rate
 * of the current tick by the ticks elapsed since the start, so a player who jumps for one tick at
 * the end of a dig is billed the airborne rate for the whole dig. The client sums tick after tick;
 * summing here as well is what makes the server's time match the one the player expects, and it is
 * what lets a tick without a swing count for nothing (the heartbeat pause).
 *
 * <p><b>Mutable on purpose.</b> One instance per player, held by the player's own game-mode object,
 * read and written only on the thread that ticks that player. Replacing it at every tick would
 * allocate twenty objects a second per digging player for no safety gained: nothing else ever sees
 * it.
 *
 * <p>Ticks are the game-mode's own counter; only differences between them matter.
 */
public final class ServerDigClock {

    /** What one tick of {@link #advance} did. */
    public enum Step {
        /** No dig, nothing done. */
        IDLE,
        /** The dig is waiting: no swing for too long, or the pause after the previous break. */
        PAUSED,
        /** Progress was added; the visible stage is unchanged. */
        ADVANCED,
        /** Progress was added and the visible stage changed: the crack must be sent. */
        STAGE_CHANGED,
        /** The progress reached 1: the block must be broken now. */
        COMPLETE
    }

    /** The last visible crack stage; the client draws 0 to 9, and -1 erases the crack. */
    public static final int MAX_STAGE = 9;

    /** No stage sent yet, or the crack erased. */
    public static final int NO_STAGE = -1;

    private boolean armed;
    private long lingerUntil;

    private boolean digging;
    private double progress;
    private int lastStage = NO_STAGE;
    private long lastSwing;
    private long pauseUntil = Long.MIN_VALUE;
    private long lastAdvance;

    /**
     * The most ticks one {@link #advance} may bill.
     *
     * <p>The counter is the lag-compensated tick: under load it moves by more than one per server
     * tick, and vanilla bills the dig in that time. Catching up keeps a server-driven dig as long, in
     * real time, as a vanilla one on a slow server; the cap keeps a single long stall from finishing a
     * dig at once.
     */
    static final long MAX_CATCH_UP_TICKS = 4L;

    /**
     * The floating-point margin of the sum: n additions of 1/n can land just under 1 (1/6 six times
     * gives 0.9999999999999999) and would cost one more tick than the rate says.
     */
    static final double COMPLETE_TOLERANCE = 1.0e-9;

    /** The most progress a taken-over dig keeps, so it still breaks through {@link #advance}. */
    static final double MAX_CARRIED_PROGRESS = 0.99;

    // ==================== MODE ====================

    /**
     * Arms the mode, or extends it, until {@code now + lingerTicks}.
     *
     * @return {@code true} when the mode was off and is now on: the caller must switch the client view
     */
    public boolean arm(final long now, final int lingerTicks) {
        final boolean entered = !this.armed;
        this.armed = true;
        this.lingerUntil = entered ? now + lingerTicks : Math.max(this.lingerUntil, now + lingerTicks);
        return entered;
    }

    /** Whether the mode is on. */
    public boolean isArmed() {
        return this.armed;
    }

    /**
     * Whether the mode has run out and may end now.
     *
     * @param destroying whether the game mode still registers a dig ({@code isDestroyingBlock});
     *                   a dig this clock measures keeps the mode on, whatever the linger says
     */
    public boolean shouldDisarm(final long now, final boolean destroying) {
        return this.armed && !(destroying && this.digging) && now >= this.lingerUntil;
    }

    /** Ends the mode. The caller restores the client view. */
    public void disarm() {
        this.armed = false;
    }

    // ==================== DIG ====================

    /**
     * Starts measuring a dig from zero. The start counts as a swing.
     *
     * <p>The pause after a previous break is kept: a player who breaks a crop and keeps the button
     * down lands on the farmland below, and vanilla waits five ticks before that second dig too.
     */
    public void startDig(final long now) {
        this.startDigAt(now, 0.0);
    }

    /**
     * Takes over a dig already under way at {@code progress} — a vanilla dig running when the mode
     * switched on, which the client can no longer finish under the zero view.
     *
     * @param progress the progress reached so far; kept within {@code [0, 1)} so the dig still has one
     *                 tick to go and breaks through {@link #advance}, never here
     */
    public void startDigAt(final long now, final double progress) {
        this.digging = true;
        this.progress = Double.isNaN(progress) ? 0.0 : Math.clamp(progress, 0.0, MAX_CARRIED_PROGRESS);
        this.lastStage = NO_STAGE;
        this.lastSwing = now;
        // The tick of the start counts, as vanilla's rate x (elapsed + 1) counts it.
        this.lastAdvance = now - 1;
    }

    /** Whether a dig is being measured. */
    public boolean isDigging() {
        return this.digging;
    }

    /** Records a swing: the client still holds the button. */
    public void swing(final long now) {
        this.lastSwing = now;
    }

    /**
     * Adds one tick of progress.
     *
     * <ul>
     *   <li>no swing for more than {@code heartbeatTicks}, or still inside the pause after the last
     *       break: nothing is added, the dig waits — it is never aborted from here, an honest client
     *       aborts on its own when the button is released;</li>
     *   <li>a rate that is not a number, or not positive, adds nothing (mining fatigue can bring it
     *       to zero, which is vanilla's "never");</li>
     *   <li>an infinite rate completes the dig at once.</li>
     * </ul>
     *
     * <p>The progress added is {@code rate} times the ticks of the counter elapsed since the previous
     * call, at most {@link #MAX_CATCH_UP_TICKS}: one on a server at full speed. Paused ticks are not
     * billed.
     *
     * @param rate the progress of one tick, environment included
     */
    public Step advance(final long now, final double rate, final int heartbeatTicks) {
        if (!this.digging) return Step.IDLE;
        final long elapsed = Math.clamp(now - this.lastAdvance, 0L, MAX_CATCH_UP_TICKS);
        this.lastAdvance = now;
        if (now < this.pauseUntil || now - this.lastSwing > heartbeatTicks) return Step.PAUSED;
        if (Double.isNaN(rate) || rate <= 0.0) return Step.PAUSED;

        this.progress = Double.isInfinite(rate) ? 1.0 : this.progress + rate * elapsed;
        if (this.progress >= 1.0 - COMPLETE_TOLERANCE) return Step.COMPLETE;

        final int stage = stageOf(this.progress);
        if (stage == this.lastStage) return Step.ADVANCED;
        this.lastStage = stage;
        return Step.STAGE_CHANGED;
    }

    /** The progress so far, 0 to 1. */
    public double progress() {
        return this.progress;
    }

    /** The crack stage last reported by {@link #advance}, or {@link #NO_STAGE}. */
    public int stage() {
        return this.lastStage;
    }

    /**
     * The crack stage of a progress: tenths, kept within the ten textures the client has.
     *
     * <p>A progress below a tenth is stage 0 — the dig is visibly started — and anything from 0.9
     * up is stage 9, the last texture before the block falls.
     */
    public static int stageOf(final double progress) {
        if (!(progress > 0.0)) return 0;
        // the same float margin as the completion: 8 x 0.1 is 0.7999999999999999, still the 8th tenth
        return Math.min(MAX_STAGE, (int) (progress * 10.0 + COMPLETE_TOLERANCE));
    }

    /**
     * Ends a dig that broke its block: the mode lasts {@code lingerTicks} more, and the next dig waits
     * {@code pauseTicks}.
     */
    public void finishDig(final long now, final int pauseTicks, final int lingerTicks) {
        this.digging = false;
        this.progress = 0.0;
        this.lastStage = NO_STAGE;
        this.pauseUntil = now + pauseTicks;
        this.arm(now, lingerTicks);
    }

    /**
     * Starts the same dig again from zero, the button still held.
     *
     * <p>For a break that was refused at the last moment ({@code BlockBreakEvent} cancelled, the
     * block restored) and for another block that took the place of the one being dug.
     */
    public void restartDig() {
        this.progress = 0.0;
        this.lastStage = NO_STAGE;
    }

    /** Ends a dig that did not break its block (released button, block gone, out of reach). */
    public void abortDig() {
        this.digging = false;
        this.progress = 0.0;
        this.lastStage = NO_STAGE;
    }

    /** Forgets everything: mode off, no dig, no pause. */
    public void reset() {
        this.armed = false;
        this.lingerUntil = 0L;
        this.abortDig();
        this.pauseUntil = Long.MIN_VALUE;
    }
}
