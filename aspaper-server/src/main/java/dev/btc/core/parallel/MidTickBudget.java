package dev.btc.core.parallel;

/**
 * How long a world thread leaves its chunk task queue alone after a mid-tick run.
 *
 * <p>Same figures and same arithmetic as Moonrise's mid-tick tasks on the main thread
 * ({@code MinecraftServer#moonrise$executeMidTickTasks}); only the state moves, from two fields of
 * the server to each world thread, so that worlds ticking side by side do not share a back-off.
 */
public final class MidTickBudget {

    /** One run may take that long before it stops. */
    public static final long ALLOWANCE_NANOS = 1_000L;
    /** Pause after a run that used exactly its allowance. */
    public static final long GAP_NANOS = 25_000L;
    /** Shortest pause after a run that found the queue empty. */
    public static final long EMPTY_BACKOFF_NANOS = 5_000L;
    /** A run counts for that much over its allowance at most: a GC pause must not starve chunk loads. */
    public static final long MAX_OVERUSE_NANOS = 10_000_000L;

    private MidTickBudget() {
    }

    /**
     * @param spentNanos how long the run that just ended took
     * @param queueEmpty it stopped because there was nothing left to run
     * @return how long to wait before the next run; shorter than the gap when the run was quick
     */
    public static long delayNanos(long spentNanos, boolean queueEmpty) {
        long overuse = Math.min(spentNanos - ALLOWANCE_NANOS, MAX_OVERUSE_NANOS);
        long delay = GAP_NANOS + Math.round((double) overuse / ALLOWANCE_NANOS * GAP_NANOS);
        return queueEmpty ? Math.max(delay, EMPTY_BACKOFF_NANOS) : delay;
    }
}
