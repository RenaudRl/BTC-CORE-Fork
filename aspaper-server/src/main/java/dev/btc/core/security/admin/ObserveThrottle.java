package dev.btc.core.security.admin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits the observe-mode journal to one line per player per window, carrying the number of commands
 * left unreported since the previous line. Pure: the clock is injected, no server type, no I/O.
 *
 * <p>Only the generic line of an op outside the allowlist is limited. A line whose reason is an
 * {@code /op} or {@code /deop} (any namespace) is never limited, whoever types it: it is the trace of a
 * privilege change attempt, and the limiter must not be able to erase it. Only the count of silenced
 * commands is kept, never their text: arbitrary command arguments are not journalled.
 *
 * <p>Thread-safe: commands of different players run on different region threads. A player's entry is
 * dropped by {@link #forget} when they leave, so the map never outlives the online players.
 */
public final class ObserveThrottle {

    /** Whether to write a line now, and how many commands were silenced since the previous line. */
    public record Decision(boolean report, int suppressed) {}

    private static final Decision ALWAYS = new Decision(true, 0);

    private record Entry(long lastReportNanos, int suppressed) {}

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public ObserveThrottle(final long windowNanos, final LongSupplier nanoClock) {
        this.windowNanos = windowNanos;
        this.nanoClock = nanoClock;
    }

    /**
     * Counts one command by {@code player}. A command that is not limitable (enforce, an op command, or a
     * reason other than the standing op outside the list) is always reported and counted for nothing.
     * Otherwise the first one, and the first after each window, is reported.
     */
    public Decision onCommand(final UUID player, final OpLock.CommandVerdict verdict) {
        if (verdict.cancel() || verdict.reason() != OpLock.Reason.OP_OUTSIDE_LIST || verdict.opCommand()) {
            return ALWAYS;
        }
        final long now = this.nanoClock.getAsLong();
        final Decision[] decision = new Decision[1];
        this.entries.compute(player, (key, entry) -> {
            if (entry == null || now - entry.lastReportNanos() >= this.windowNanos) {
                decision[0] = new Decision(true, entry == null ? 0 : entry.suppressed());
                return new Entry(now, 0);
            }
            decision[0] = new Decision(false, 0);
            return new Entry(entry.lastReportNanos(), entry.suppressed() + 1);
        });
        return decision[0];
    }

    /** The player left: forgets them and returns how many commands were silenced since their last line. */
    public int forget(final UUID player) {
        final Entry entry = this.entries.remove(player);
        return entry == null ? 0 : entry.suppressed();
    }

    /** How many players are tracked, for tests. */
    int tracked() {
        return this.entries.size();
    }
}
