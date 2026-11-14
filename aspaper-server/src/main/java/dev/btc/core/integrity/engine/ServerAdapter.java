package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything the engine needs from the running server, behind one seam.
 *
 * <p>The engine's decisions are pure functions of packets and session state; what it does with a
 * decision — journal it, publish it, tell a staff member — touches Bukkit, and so does what it reads
 * before deciding — attributes, effects, collision. Keeping both behind an interface is what lets
 * {@code SentinelEngineTest} drive the whole engine with a recorder and no server, the same way
 * {@code SentinelHooksTest} does for the seam below it.
 *
 * <p>Every method is called on the region thread that owns {@code player}.
 */
interface ServerAdapter {

    /** The player's current name, for the journal. Empty when they are already gone. */
    Optional<String> playerName(UUID player);

    /**
     * Whether a plugin declared a teleport to about this destination, consuming the declaration.
     *
     * <p>The declaration never decides whether the arrival is legitimate — the fork's own
     * expectation does that. It adds intent to the verbose record, nothing more (D15).
     */
    Optional<TeleportKind> consumeDeclaredTeleport(UUID player, double x, double y, double z);

    /**
     * What the prediction needs to judge a position claim: the player's live limits and what the
     * world has at the claimed position.
     *
     * <p>Empty when there is nothing sound to judge against — the player is gone, or the chunk the
     * claim points into is not loaded on this server. An absent context skips the judgement; it
     * never stands in for a permissive one.
     *
     * <p>Support is looked for under the claimed box and under the same box at {@code fromX, fromZ},
     * the horizontal position the tick started from: vanilla resolves the vertical axis first, there,
     * and slides afterwards ({@code Direction#axisStepOrder}), so a client stepping off an edge lands
     * on the block it leaves and ends the tick beside it, rightly claiming ground. A reference
     * position passes its own column.
     */
    Optional<MovementContext> movementContext(UUID player, double fromX, double fromZ,
                                              double x, double y, double z);

    /**
     * What the reach judgement needs for an attack on {@code targetEntityId}: the attacker's eye,
     * the target's box as the server has it and had it during {@code roundTripNanos}, and the reach
     * the server grants this attacker with the weapon in hand.
     *
     * <p>Empty when the target does not exist on this server — the handler will refuse the attack
     * itself — or the attacker is gone. {@code roundTripNanos} is negative before the first answer.
     */
    Optional<ReachContext> reachContext(UUID player, int targetEntityId, long roundTripNanos);

    /**
     * The id of the container the player has open on the server: {@code 0} for their own inventory,
     * which is also what is answered when the player is gone.
     */
    int openContainerId(UUID player);

    /** The player's latency as the server measures it ({@code Player#getPing()}), {@code -1} when gone. */
    long latencyMillis(UUID player);

    /** How long one server tick lasts at the current tick rate ({@code /tick rate}), in nanoseconds. */
    long tickNanos();

    /**
     * Whether the player's game mode is one the 16.7 checks do not judge: creative (its inventory is
     * a client-side screen and its interactions are not bounded the same way) and spectator (it
     * interacts with nothing).
     */
    boolean exemptGameMode(UUID player);

    /**
     * Whether a solid block collider lies on the straight line between two points of the player's
     * world.
     *
     * <p>{@code false} when the line is not sound to read — an end lies in a chunk this thread does not
     * own or that is not loaded: a missing answer must never read as a wall.
     */
    boolean blockedBetween(UUID player, double fromX, double fromY, double fromZ,
                           double toX, double toY, double toZ);

    /**
     * What the server sees on a line that {@link #blockedBetween} found blocked, for the journal line of a
     * wall hit: the block the line hits, the target, the attacker's pose. Read once per journalled line, on
     * the line of the target's current box. Empty when there is nothing to add.
     */
    default String describeWallHit(UUID player, int targetEntityId, double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ) {
        return "";
    }

    /** The origin the proxy bound to this session; {@code UNKNOWN} when it bound none. */
    ClientPlatform platformOf(UUID player);

    /** Sends the client a ping carrying {@code id}; the answer comes back through the seam. */
    void ping(UUID player, int id);

    /** Writes one line to the violation journal, if journalling is on. */
    void journal(UUID player, String playerName, CheckId check, String detail);

    /** Publishes to extension subscribers, hopping to the owning thread as the bus requires. */
    void publish(UUID player, ViolationEvent event);

    /** Tells staff who asked for the verbose feed. Observation-phase violations go here, not to alerts. */
    void verbose(String line);
}
