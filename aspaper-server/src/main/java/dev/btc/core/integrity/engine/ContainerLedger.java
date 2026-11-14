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
 * (whatever closed it). What the client closed is read from its close packet, which names the id. That errs towards silence: a window opened and replaced before anything
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

    /** The server's container counter cycles through 1..100 ({@code ServerPlayer#nextContainerCounter}). */
    static final int MAX_ID = 100;

    enum Verdict { OK, UNKNOWN, CLOSED }

    private final Set<Integer> known = new HashSet<>();
    private final Set<Integer> closedByClient = new HashSet<>();

    /** The last container the engine saw the server have open, {@link #INVENTORY_ID} before any. */
    private int lastSeenOpen = INVENTORY_ID;

    /**
     * Judges a click in container {@code containerId} while the server has {@code openContainerId} open,
     * and learns from it: a click in the open container proves its id.
     */
    Verdict clicked(final int containerId, final int openContainerId) {
        if (openContainerId > INVENTORY_ID) {
            lastSeenOpen = openContainerId;
        }
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

    /** The container {@code containerId} was open and the server has just closed it: a click in flight is a race. */
    void closedByServer(final int containerId) {
        if (containerId > INVENTORY_ID) {
            lastSeenOpen = containerId;
            known.add(containerId);
            closedByClient.remove(containerId);
        }
    }

    /**
     * The container {@code containerId} was open when the client's close event fired, but the id the client
     * closed is the one its packet names ({@link #clientClosed}): this only learns that the server showed it.
     */
    void shown(final int containerId) {
        if (containerId > INVENTORY_ID) {
            lastSeenOpen = containerId;
            known.add(containerId);
        }
    }

    /**
     * The client's close packet named {@code namedId} while the server had {@code openId} open, which the server
     * closes whatever the packet names. Returns false when the two differ for no reason a race explains: the open
     * one is then taken as closed by the client, so naming another id cannot keep it clickable.
     *
     * <p>A race is the server opening a menu the client has not seen yet: the packet names the last menu seen
     * open (or the client's own inventory) and the open one is newer than anything seen. The counter only moves
     * forward, so "not the last seen" stands for "never seen" without remembering a whole cycle of ids.
     */
    boolean clientClosed(final int namedId, final int openId) {
        final int lastSeen = lastSeenOpen;
        if (openId > INVENTORY_ID) {
            lastSeenOpen = openId;
        }
        if (openId <= INVENTORY_ID || namedId == openId) {
            // Nothing else was open: the packet names everything the server closes.
            remember(openId <= INVENTORY_ID ? namedId : openId);
            return true;
        }
        if ((namedId == lastSeen || namedId == INVENTORY_ID) && openId != lastSeen) {
            remember(namedId);
            return true;
        }
        remember(openId);
        return false;
    }

    /** An id the server cannot hand out is the client inventing one and is not remembered: the sets stay bounded. */
    private void remember(final int closedId) {
        if (closedId > INVENTORY_ID && closedId <= MAX_ID) {
            known.add(closedId);
            closedByClient.add(closedId);
        }
    }
}
