package dev.btc.core.integrity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single seam through which packet handlers reach the integrity engine.
 *
 * <p>Every anchor the fork patches into {@code ServerGamePacketListenerImpl} is one static call on
 * this class and nothing else. That is deliberate: a patch that carries logic has to be re-read and
 * re-reasoned every time the upstream method moves, and a patch set with several insertion styles
 * drifts until one of them silently stops being applied. Here the patch carries a call, the logic
 * lives in normal server sources, and a hook that stops firing is a compile error rather than a
 * no-op.
 *
 * <p><b>Nothing is installed by default.</b> With no observer, each entry point is a volatile read
 * and a null check — the shape the JIT folds away — so an unarmed server pays nothing on its hottest
 * packet path. Phase 3 installs the prediction engine here; until then these calls are inert by
 * construction, not by configuration.
 *
 * <p><b>The engine never breaks the game.</b> Anything an observer throws is caught, reported once,
 * and the observer is dropped: a bug in a check must cost observation, never a player's session.
 * Re-arming is an explicit {@link #install(SessionObserver)} — a failure that silently repaired
 * itself would hide the incident it exists to report.
 *
 * <p>Thread-safety: called from the region thread that owns the player, after
 * {@code PacketUtils.ensureRunningOnSameThread}. An observer must be safe for concurrent calls about
 * different players.
 */
public final class SentinelHooks {

    /**
     * Not {@code Bukkit.getLogger()}: this class is reachable before the server is up, and an
     * integrity failure must be findable under its own name in the log.
     */
    private static final Logger LOG = Logger.getLogger("Sentinel");

    /**
     * What the engine implements to see raw client intent.
     *
     * <p>Arguments are primitives and a {@link UUID} rather than server types on purpose: the seam
     * must stay testable without a running server, and it must not hand an observer a live entity it
     * could mutate. An observer that needs the world state reads it itself, from the player it was
     * given, on the thread it was called on.
     */
    public interface SessionObserver {

        /**
         * A movement packet, before the server has moved anything.
         *
         * <p>{@code hasPosition} and {@code hasRotation} say which fields the client actually sent;
         * the others carry the player's current value, exactly as the handler defaults them. A check
         * that treats a rotation-only packet as a position claim invents movement that never
         * happened, which is the classic source of false flags.
         */
        void onMove(UUID player, double x, double y, double z, float yRot, float xRot,
                    boolean onGround, boolean hasPosition, boolean hasRotation);

        /** An attack on an entity, before the reach and the damage are resolved. */
        void onAttack(UUID player, int targetEntityId);

        /** An interaction with an entity, before the reach is resolved. */
        void onInteractEntity(UUID player, int targetEntityId,
                              double hitX, double hitY, double hitZ, boolean secondaryAction);

        /**
         * An item use. {@code againstBlock} separates the two client paths — a use on a block and a
         * use in the air — because only the first carries a claimed block position.
         */
        void onUseItem(UUID player, boolean againstBlock);

        /**
         * A click in an open container, before the menu acts on it.
         *
         * <p>{@code inputOrdinal} is the ordinal of Minecraft's {@code ContainerInput} rather than
         * the enum itself, so this seam does not pin a server enum into its own signature.
         */
        void onContainerClick(UUID player, int containerId, int slot, int button,
                              int inputOrdinal);

        /**
         * The server has moved the player and is waiting for the client to confirm it.
         *
         * <p>This is the fork's own notion of a pending teleport, taken where it is armed rather
         * than inferred: from here until {@link #onTeleportAcknowledged(UUID)}, the authoritative
         * position is the destination, and the positions the client keeps sending are the old ones
         * still in flight. A movement check that does not know this flags every teleport as a
         * blink — which is why the expectation is read from the fork and never declared by a
         * plugin. What a plugin declares adds intent to the verbose, never the destination itself.
         *
         * <p>The cause is deliberately absent: it is carried by {@code PlayerTeleportEvent}, which
         * ordinary listener code already sees. Threading it through the patch would buy nothing and
         * cost an anchor to maintain against upstream.
         */
        void onTeleportExpected(UUID player, double x, double y, double z);

        /** The client has confirmed the teleport; positions it sends from now on are about the new place. */
        void onTeleportAcknowledged(UUID player);

        /**
         * The client answered a ping the engine sent ({@code ServerboundPongPacket}).
         *
         * <p>The one hook that is <b>not</b> on the region thread: vanilla handles the pong on the
         * network thread and does nothing with it. It carries an id and a clock reading, nothing a
         * world could be read from, so the observer must treat it as a network-thread call and
         * touch only what is safe there.
         */
        void onPong(UUID player, int id);
    }

    /** The installed engine, or {@code null} when nothing observes. Read on every packet. */
    private static volatile SessionObserver observer;

    /** Guards the report of the failure that disarmed the seam, so it is loud exactly once. */
    private static final AtomicBoolean failureReported = new AtomicBoolean();

    private SentinelHooks() {
    }

    /**
     * Installs the engine. Replaces whatever observed before — there is one engine, not a chain:
     * several observers on the hottest path in the server would make the cost of observation a
     * function of how many things happen to be listening.
     *
     * @return a handle that uninstalls exactly this observer, and does nothing if another one has
     *     since taken its place
     */
    public static AutoCloseable install(final SessionObserver newObserver) {
        if (newObserver == null) {
            throw new IllegalArgumentException("installing nothing is uninstalling; close the handle instead");
        }
        observer = newObserver;
        failureReported.set(false);
        return () -> {
            if (observer == newObserver) {
                observer = null;
            }
        };
    }

    /** Whether an observer is installed. For tests and for the status command; not for the hooks. */
    public static boolean observing() {
        return observer != null;
    }

    /** Drops the observer. Used by tests and by a shutdown that must leave no engine behind. */
    public static void uninstall() {
        observer = null;
        failureReported.set(false);
    }

    public static void move(final UUID player, final double x, final double y, final double z,
                            final float yRot, final float xRot, final boolean onGround,
                            final boolean hasPosition, final boolean hasRotation) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onMove(player, x, y, z, yRot, xRot, onGround, hasPosition, hasRotation);
        } catch (final Throwable failure) {
            disarm("onMove", failure);
        }
    }

    public static void attack(final UUID player, final int targetEntityId) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onAttack(player, targetEntityId);
        } catch (final Throwable failure) {
            disarm("onAttack", failure);
        }
    }

    public static void interactEntity(final UUID player, final int targetEntityId,
                                      final double hitX, final double hitY, final double hitZ,
                                      final boolean secondaryAction) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onInteractEntity(player, targetEntityId, hitX, hitY, hitZ, secondaryAction);
        } catch (final Throwable failure) {
            disarm("onInteractEntity", failure);
        }
    }

    public static void useItem(final UUID player, final boolean againstBlock) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onUseItem(player, againstBlock);
        } catch (final Throwable failure) {
            disarm("onUseItem", failure);
        }
    }

    public static void containerClick(final UUID player, final int containerId, final int slot,
                                      final int button, final int inputOrdinal) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onContainerClick(player, containerId, slot, button, inputOrdinal);
        } catch (final Throwable failure) {
            disarm("onContainerClick", failure);
        }
    }

    public static void teleportExpected(final UUID player, final double x, final double y,
                                        final double z) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onTeleportExpected(player, x, y, z);
        } catch (final Throwable failure) {
            disarm("onTeleportExpected", failure);
        }
    }

    public static void teleportAcknowledged(final UUID player) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onTeleportAcknowledged(player);
        } catch (final Throwable failure) {
            disarm("onTeleportAcknowledged", failure);
        }
    }

    public static void pong(final UUID player, final int id) {
        final SessionObserver current = observer;
        if (current == null) {
            return;
        }
        try {
            current.onPong(player, id);
        } catch (final Throwable failure) {
            disarm("onPong", failure);
        }
    }

    /**
     * Stops observing and says why, once.
     *
     * <p>Dropping the observer rather than swallowing the throw is the whole point: a check that
     * throws on one packet will throw on the next thousand, and a seam that kept calling it would
     * turn one bug into a log flood on the packet path.
     */
    private static void disarm(final String hook, final Throwable failure) {
        observer = null;
        if (failureReported.compareAndSet(false, true)) {
            LOG.log(Level.SEVERE, "[Sentinel] " + hook + " threw; the integrity engine is now "
                + "uninstalled and the server keeps running unobserved. Reinstall it once the cause "
                + "is fixed.", failure);
        }
    }
}
