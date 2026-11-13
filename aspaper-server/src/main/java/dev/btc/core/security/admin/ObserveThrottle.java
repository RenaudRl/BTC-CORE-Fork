package dev.btc.core.security.admin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits the observe-mode journal to one line per player per window, carrying the number of commands
 * left unreported since the previous line. Pure: the clock is injected, no server type, no I/O.
 *
 * <p>Thread-safe: commands of different players run on different region threads. A player's entry is
 * dropped by {@link #forget} when they leave, so the map never outlives the online players.
 */
public final class ObserveThrottle {

    /** Whether to write a line now, and how many commands were silenced since the previous line. */
    public record Decision(boolean report, int suppressed) {}

    private record Entry(long lastReportNanos, int suppressed) {}

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public ObserveThrottle(final long windowNanos, final LongSupplier nanoClock) {
        this.windowNanos = windowNanos;
        this.nanoClock = nanoClock;
    }

    /** Counts one command by {@code player}. The first one, and the first after each window, is reported. */
    public Decision onCommand(final UUID player) {
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

    /** The player left: forgets them. Their pending silenced count is dropped with them. */
    public void forget(final UUID player) {
        this.entries.remove(player);
    }

    /** How many players are tracked, for tests. */
    int tracked() {
        return this.entries.size();
    }
}
