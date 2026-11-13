package dev.btc.core.integrity.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * The containers one session has had open, as far as the engine has seen them.
 *
 * <p>A container id is allocated by the server ({@code ServerPlayer#nextContainerCounter}, which cycles
 * through a hundred values) and the client only echoes it back, so a click naming an id the
 * server never had open for this player is not a race to excuse: it is a client inventing one. What
 * the engine cannot see is every open, so an id is "known" when the server has shown it: it was the
 * open container when a click arrived, or it was the one being closed when a close event fired
 * (whatever closed it). That errs towards silence: a window opened and replaced before anything
 * touched it, with no close event, would be reported on a click in flight — a rare race the journal will size.
 *
 * <p>A container the <em>client</em> closed is different from one the server closed. The client sends its
 * close packet before anything it sends later, so a click in that container after it can only come from a
 * client that did not close it. A container the server closed leaves clicks in flight that are a race,
 * and those are not judged.
 *
 * <p>Bounded by the id space of the server, not by anything the client sends: only ids the server showed are
 * remembered, the ones a client invents are judged and forgotten. Owned by the region thread that owns the player.
 */
final class ContainerLedger {

    /** The player's own inventory: always open, never judged. */
    static final int INVENTORY_ID = 0;

    enum Verdict { OK, UNKNOWN, CLOSED }

    private final Set<Integer> known = new HashSet<>();
    private final Set<Integer> closedByClient = new HashSet<>();

    /**
     * Judges a click in container {@code containerId} while the server has {@code openContainerId} open,
     * and learns from it: a click in the open container proves its id.
     */
    Verdict clicked(final int containerId, final int openContainerId) {
        if (containerId == INVENTORY_ID) {
            return Verdict.OK;
        }
        if (containerId == openContainerId) {
            // The server opened this id again (the counter wrapped): it is a new container.
            known.add(containerId);
            closedByClient.remove(containerId);
            return Verdict.OK;
        }
        if (closedByClient.contains(containerId)) {
            return Verdict.CLOSED;
        }
        return known.contains(containerId) ? Verdict.OK : Verdict.UNKNOWN;
    }

    /** The container {@code containerId} was open and has just been closed, by the client or by the server. */
    void closed(final int containerId, final boolean byClient) {
        if (containerId > INVENTORY_ID) {
            known.add(containerId);
            if (byClient) {
                closedByClient.add(containerId);
            } else {
                closedByClient.remove(containerId);
            }
        }
    }
}
