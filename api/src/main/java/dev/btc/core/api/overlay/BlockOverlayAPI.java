package dev.btc.core.api.overlay;

import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Entry point of the per-player block overlay. See the {@linkplain dev.btc.core.api.overlay package
 * documentation} for the threading and life-cycle contract.
 *
 * <pre>{@code
 * BlockOverlayAPI.find().ifPresentOrElse(
 *     api -> transport = new ServerOverlayTransport(api),   // BTC-CORE serves the overlay
 *     ()  -> transport = new PacketOverlayTransport());     // any other server: raw packets
 * }</pre>
 *
 * @since 26.3 (fork build after 26.3.build.1)
 */
public interface BlockOverlayAPI {

    /**
     * The overlay of a player, created empty on first use. The same instance is returned for the
     * whole connection; it is emptied, not replaced, on a world change.
     *
     * @param player an online player
     * @return the player's overlay, never {@code null}
     */
    PlayerBlockOverlay overlay(Player player);

    /**
     * Looks the implementation up without failing, to choose between this API and a packet fallback.
     *
     * @return the running implementation, or empty when the server is not BTC-CORE (or a build
     *         before the overlay)
     */
    static Optional<BlockOverlayAPI> find() {
        return Holder.INSTANCE;
    }

    /**
     * The implementation, for code that only runs on BTC-CORE.
     *
     * @return the running implementation
     * @throws IllegalStateException when the server does not provide the overlay
     */
    static BlockOverlayAPI instance() {
        return find().orElseThrow(() -> new IllegalStateException(
            "BlockOverlayAPI implementation not found - server must be running BTC-CORE"));
    }

    /** Lazy holder: the service is looked up once. */
    final class Holder {
        private static final Optional<BlockOverlayAPI> INSTANCE =
            net.kyori.adventure.util.Services.service(BlockOverlayAPI.class);

        private Holder() {
        }
    }
}
