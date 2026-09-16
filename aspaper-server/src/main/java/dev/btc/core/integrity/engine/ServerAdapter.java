package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
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
     */
    Optional<MovementContext> movementContext(UUID player, double x, double y, double z);

    /**
     * What the reach judgement needs for an attack on {@code targetEntityId}: the attacker's eye,
     * the target's box as the server has it and had it during {@code roundTripNanos}, and the reach
     * the server grants this attacker with the weapon in hand.
     *
     * <p>Empty when the target does not exist on this server — the handler will refuse the attack
     * itself — or the attacker is gone. {@code roundTripNanos} is negative before the first answer.
     */
    Optional<ReachContext> reachContext(UUID player, int targetEntityId, long roundTripNanos);

    /** Sends the client a ping carrying {@code id}; the answer comes back through the seam. */
    void ping(UUID player, int id);

    /** Writes one line to the violation journal, if journalling is on. */
    void journal(UUID player, String playerName, CheckId check, String detail);

    /** Publishes to extension subscribers, hopping to the owning thread as the bus requires. */
    void publish(UUID player, ViolationEvent event);

    /** Tells staff who asked for the verbose feed. Observation-phase violations go here, not to alerts. */
    void verbose(String line);
}
