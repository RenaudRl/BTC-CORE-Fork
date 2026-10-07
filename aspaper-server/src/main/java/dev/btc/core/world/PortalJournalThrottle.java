package dev.btc.core.world;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets one journal line per entity through in each window.
 *
 * <p>An entity that keeps standing in a portal raises {@code EntityPortalReadyEvent} again every few
 * seconds, and a cancelled one starts over at once. Without this, one player waiting in a refused
 * portal writes a line every four seconds for as long as they stay.
 *
 * <p>Thread-safe and bounded, for Folia, where every region thread may ask at once: a
 * {@link ConcurrentHashMap} updated atomically per key, never holding more than {@link #MAX_TRACKED}
 * entities. When it is full of live entries, an unknown entity is let through unrecorded: the
 * journal then errs towards one line too many, never towards a lost refusal.
 */
public final class PortalJournalThrottle {

    /** One line per entity per window. */
    public static final long WINDOW_MILLIS = 30_000L;

    /** Upper bound on remembered entities. */
    public static final int MAX_TRACKED = 1024;

    private final ConcurrentHashMap<UUID, Long> lastLogged = new ConcurrentHashMap<>();

    /**
     * Whether this entity may be journaled now; {@code true} also records the line as written.
     *
     * @param nowMillis a monotonic clock reading in milliseconds
     */
    public boolean shouldLog(UUID entity, long nowMillis) {
        if (lastLogged.size() >= MAX_TRACKED) {
            lastLogged.values().removeIf(last -> nowMillis - last >= WINDOW_MILLIS);
            if (lastLogged.size() >= MAX_TRACKED && !lastLogged.containsKey(entity)) {
                return true;
            }
        }
        boolean[] allowed = {false};
        lastLogged.compute(entity, (id, last) -> {
            if (last == null || nowMillis - last >= WINDOW_MILLIS) {
                allowed[0] = true;
                return nowMillis;
            }
            return last;
        });
        return allowed[0];
    }

    /** Remembered entities, for tests. */
    int tracked() {
        return lastLogged.size();
    }
}
