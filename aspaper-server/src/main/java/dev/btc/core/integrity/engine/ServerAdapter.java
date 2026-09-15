package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything the engine needs from the running server, behind one seam.
 *
 * <p>The engine's decisions are pure functions of packets and session state; what it does with a
 * decision — journal it, publish it, tell a staff member — touches Bukkit. Keeping that behind an
 * interface is what lets {@code SentinelEngineTest} drive the whole engine with a recorder and no
 * server, the same way {@code SentinelHooksTest} does for the seam below it.
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

    /** Writes one line to the violation journal, if journalling is on. */
    void journal(UUID player, String playerName, CheckId check, String detail);

    /** Publishes to extension subscribers, hopping to the owning thread as the bus requires. */
    void publish(UUID player, ViolationEvent event);

    /** Tells staff who asked for the verbose feed. Observation-phase violations go here, not to alerts. */
    void verbose(String line);
}
