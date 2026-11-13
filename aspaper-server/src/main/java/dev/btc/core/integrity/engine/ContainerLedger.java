package dev.btc.core.integrity.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * The containers one session has had open, as far as the engine has seen them.
 *
 * <p>A container id is allocated by the server ({@code ServerPlayer#nextContainerCounter}, which cycles
 * through {@value #LAST_ID} values) and the client only echoes it back, so a click naming an id the
 * server never had open for this player is not a race to excuse: it is a client inventing one. What
 * the engine cannot see is every open, so an id is "known" when the server has shown it: it was the
 * open container when a click arrived, or it was the one being closed when a close event fired
 * (whatever closed it). That errs towards silence: a window opened and replaced before anything
 * touched it, with no close event, would be reported on a click in flight — a rare race the journal will size.
 *
 * <p>Bounded by the id space of the server, not by anything the client sends: the ids a client invents are
 * judged and forgotten, never remembered. Owned by the region thread that owns the player.
 */
final class ContainerLedger {

    /** The player's own inventory: always open, never judged. */
    static final int INVENTORY_ID = 0;

    /** The last id the server hands out; it wraps to 1 after it ({@code ServerPlayer#nextContainerCounter}). */
    static final int LAST_ID = 100;

    enum Verdict { OK, UNKNOWN }

    private final Set<Integer> known = new HashSet<>();

    /**
     * Judges a click in container {@code containerId} while the server has {@code openContainerId} open,
     * and learns from it: a click in the open container proves its id.
     */
    Verdict clicked(final int containerId, final int openContainerId) {
        if (containerId == INVENTORY_ID) {
            return Verdict.OK;
        }
        if (containerId == openContainerId && containerId <= LAST_ID) {
            known.add(containerId);
            return Verdict.OK;
        }
        if (containerId < 0 || containerId > LAST_ID) {
            return Verdict.UNKNOWN;
        }
        return known.contains(containerId) ? Verdict.OK : Verdict.UNKNOWN;
    }

    /** The container {@code containerId} was open and has just been closed, by whoever. */
    void closed(final int containerId) {
        if (containerId > INVENTORY_ID && containerId <= LAST_ID) {
            known.add(containerId);
        }
    }
}
