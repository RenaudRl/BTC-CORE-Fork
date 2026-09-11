package dev.btc.core.integrity.sanction;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Tells the other servers of the network that a player's sanctions changed.
 *
 * <p><b>The bus carries an invalidation, never a sanction.</b> A message says only "re-read this
 * player"; every server then reads the same PostgreSQL rows and reaches the same conclusion. Shipping
 * the sanction itself would mean two sources of truth that can disagree — a mute that exists on one
 * server and not on another, with no way to tell which is right.
 *
 * <p>The contract lives here, in the server module, because {@link SanctionService} calls it. The
 * implementation lives in the plugin module, which is where the Redis-protocol client actually is (and
 * where it is relocated at shadow time). Without that seam the server module would need a dependency it
 * does not have.
 *
 * <p><b>Both artifacts must be deployed together.</b> This interface is called across the
 * server/plugin boundary; shipping a new paperclip jar against an old plugin jar — or the reverse —
 * is the documented way to get a {@code NoSuchMethodError} at runtime instead of at build time.
 */
public interface SanctionBus {

    /** Publishes an invalidation for one player. Must never throw and never block the caller. */
    void publish(UUID player);

    /** Stops the bus and releases its connection. */
    void close();

    /**
     * What a node publishes and reads.
     *
     * @param nodeId the sender, so a node can ignore its own message
     * @param player whose sanctions changed
     */
    record Invalidation(UUID nodeId, UUID player) {

        /**
         * Encodes the message.
         *
         * <p>Two uuids and a separator. Deliberately not JSON: both fields are fixed-length and
         * contain no separator, so there is nothing to escape and nothing to get wrong. A format with
         * no edge cases needs no library.
         */
        public String encode() {
            return nodeId + ":" + player;
        }

        /**
         * Decodes a message, refusing anything malformed.
         *
         * <p>Returns empty rather than throwing: this runs on a subscriber callback fed by whatever is
         * on the channel, including another product's traffic if someone points two systems at the same
         * key. A stray message must be ignored, not kill the subscriber.
         */
        public static Optional<Invalidation> decode(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            int separator = raw.indexOf(':');
            if (separator < 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Invalidation(
                    UUID.fromString(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1))));
            } catch (IllegalArgumentException notUuids) {
                return Optional.empty();
            }
        }
    }

    /** Holder for the active bus. */
    final class Holder {

        private static final Logger LOG = Logger.getLogger("Sentinel");

        /**
         * A bus that does nothing.
         *
         * <p>The default, and the state of any single-server setup. It is not an error: it means the
         * network is one node, and a node does not need to tell itself anything.
         */
        private static final SanctionBus NONE = new SanctionBus() {
            @Override
            public void publish(UUID player) {
                // Single node: nobody to tell.
            }

            @Override
            public void close() {
            }
        };

        private static volatile SanctionBus active = NONE;

        private Holder() {}

        /** Installs the bus. Replacing an existing one closes it first. */
        public static synchronized void install(SanctionBus bus) {
            if (active != NONE) {
                active.close();
            }
            active = bus == null ? NONE : bus;
        }

        /** Removes the bus and closes it. */
        public static synchronized void uninstall() {
            install(null);
        }

        /** Whether a real bus is installed. */
        public static boolean isConnected() {
            return active != NONE;
        }

        /**
         * Publishes, absorbing any failure.
         *
         * <p>A bus that is down must not make a sanction fail: the sanction is already recorded in
         * PostgreSQL, and every other server will see it at that player's next connection. Losing the
         * immediacy is a real cost; losing the sanction would be a worse one.
         */
        public static void publish(UUID player) {
            try {
                active.publish(player);
            } catch (RuntimeException failure) {
                LOG.warning("[Sentinel] could not publish a sanction change for " + player
                    + "; other servers will pick it up on their next read. " + failure.getMessage());
            }
        }
    }
}
