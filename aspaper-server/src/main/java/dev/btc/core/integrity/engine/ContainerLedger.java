package dev.btc.core.integrity.engine;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The containers one session has had open, as far as the engine has seen them.
 *
 * <p>A container id is allocated by the server ({@code ServerPlayer#nextContainerCounter}, which cycles
 * through a hundred values) and the client only echoes it back, so a click naming an id the
 * server never had open for this player is not a race to excuse: it is a client inventing one. An id is
 * "known" once the server opened it ({@code initMenu}), was the open container when a click arrived, or was
 * the one being closed when a close event fired (whatever closed it). What the client closed is read from its
 * close packet, checked against the container the server closes for it.
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

    /** Added to the player's latency: a close sent just before the open reached the client still arrives after it. */
    static final long RACE_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /** The longest race excused, whatever the latency claims: a client delaying its answers widens no further. */
    static final long RACE_CAP_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** The last container the server opened, and the one it opened before; server truth, never the client's. */
    private int lastOpened = INVENTORY_ID;
    private int openedBefore = INVENTORY_ID;
    /** When the server opened {@link #lastOpened}, on the engine's clock. */
    private long lastOpenedAt;

    /** How long after an open a close naming the previous menu can still be a race: latency plus a margin, capped. */
    static long raceWindowNanos(final long latencyMillis) {
        return Math.min(TimeUnit.MILLISECONDS.toNanos(Math.max(0, latencyMillis)) + RACE_MARGIN_NANOS, RACE_CAP_NANOS);
    }

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

    /** The container {@code containerId} was open and the server has just closed it: a click in flight is a race. */
    void closedByServer(final int containerId) {
        if (containerId > INVENTORY_ID) {
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
            known.add(containerId);
        }
    }

    /** The server opened container {@code containerId} for this player (every path ends in {@code initMenu}). */
    void opened(final int containerId, final long nowNanos) {
        if (containerId > INVENTORY_ID) {
            openedBefore = lastOpened;
            lastOpened = containerId;
            lastOpenedAt = nowNanos;
            known.add(containerId);
            closedByClient.remove(containerId);
        }
    }

    /**
     * The client's close packet named {@code namedId} while the server had {@code openId} open, which the server
     * closes whatever the packet names. Returns false when the two differ for no reason a race explains: the open
     * one is then taken as closed by the client, so naming another id cannot keep it clickable.
     *
     * <p>A race is the server opening a menu while the close was in flight: the open one is the last the server
     * opened, less than {@code raceWindowNanos} ago on the server's clock ({@link #raceWindowNanos}), and the packet
     * names the menu the server had opened before it (or the client's own inventory). Nothing the client sends or
     * withholds (tick ends, clicks) widens the window beyond its cap.
     */
    boolean clientClosed(final int namedId, final int openId, final long nowNanos, final long raceWindowNanos) {
        if (openId <= INVENTORY_ID || namedId == openId) {
            // Nothing else was open: the packet names everything the server closes.
            remember(openId <= INVENTORY_ID ? namedId : openId);
            return true;
        }
        if (openId == lastOpened && nowNanos - lastOpenedAt <= raceWindowNanos
            && (namedId == openedBefore || namedId == INVENTORY_ID)) {
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
