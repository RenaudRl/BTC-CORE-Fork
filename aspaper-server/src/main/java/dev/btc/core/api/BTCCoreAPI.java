package dev.btc.core.api;

import dev.btc.core.performance.PerformanceManager;
import dev.btc.core.qol.JoinQueueManager;
import dev.btc.core.qol.MaintenanceModeManager;
import dev.btc.core.qol.VanishManager;
import dev.btc.core.security.CombatLogManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * BTCCore: stable plugin-facing facade.
 *
 * <p>Aggregates the read-only, plugin-relevant signals of BTC-CORE behind a single
 * stable class so that plugins (BetterHud, BetterModel, PlaceholderAPI, ...) can
 * consume them reflectively — via {@code Class.forName("dev.btc.core.api.BTCCoreAPI")}
 * then {@code instance()} — without a compile-time dependency on the server internals.
 * This keeps every plugin fork maximally aligned with its upstream (no hard BTC-CORE
 * dependency) while still benefiting from BTC-CORE optimizations when present.</p>
 *
 * <p>All methods delegate to the underlying managers and are safe to call from any
 * thread (the managers use concurrent state). Callers that run on a non-BTC-CORE
 * server simply never resolve this class and fall back to vanilla behaviour.</p>
 */
public final class BTCCoreAPI {

    private static final BTCCoreAPI INSTANCE = new BTCCoreAPI();

    private BTCCoreAPI() {
    }

    /**
     * @return The singleton facade instance.
     */
    public static BTCCoreAPI instance() {
        return INSTANCE;
    }

    // ----- Performance -----------------------------------------------------

    /**
     * @return The current server MSPT (average over ~5s), or 0.0 if unavailable.
     *         Plugins use this to throttle/back off async work under load.
     */
    public double getCurrentMspt() {
        return PerformanceManager.getCurrentMspt();
    }

    /**
     * @return {@code true} if a particle at {@code location} should be sent to {@code player}
     *         (distance-culling hint). Defaults to {@code true} when culling is disabled.
     */
    public boolean shouldSendParticle(Player player, Location location) {
        return PerformanceManager.shouldSendParticle(player, location);
    }

    /**
     * @return {@code true} if a sound at {@code location} should be sent to {@code player}.
     */
    public boolean shouldSendSound(Player player, Location location) {
        return PerformanceManager.shouldSendSound(player, location);
    }

    /**
     * @return {@code true} if a HUD element sourced at {@code location} should be sent to
     *         {@code player} (dedicated BetterHud distance-culling hint).
     */
    public boolean shouldSendBetterHud(Player player, Location location) {
        return PerformanceManager.shouldSendBetterHud(player, location);
    }

    // ----- Combat ----------------------------------------------------------

    /**
     * @return {@code true} if the player is currently combat-tagged.
     */
    public boolean isInCombat(Player player) {
        return CombatLogManager.isInCombat(player);
    }

    /**
     * @return Remaining combat-tag time in seconds, or 0 if not tagged.
     */
    public int getRemainingCombatTime(Player player) {
        return CombatLogManager.getRemainingCombatTime(player);
    }

    // ----- Vanish ----------------------------------------------------------

    /**
     * @return {@code true} if the player is vanished (vanish level &gt; 0).
     */
    public boolean isVanished(Player player) {
        return VanishManager.isVanished(player);
    }

    /**
     * @return The player's vanish level (0 = visible).
     */
    public int getVanishLevel(Player player) {
        return VanishManager.getVanishLevel(player);
    }

    // ----- Join queue ------------------------------------------------------

    /**
     * @return The 1-indexed queue position for the given UUID, or -1 if not queued.
     */
    public int getQueuePosition(UUID uuid) {
        return JoinQueueManager.getQueuePosition(uuid);
    }

    /**
     * @return The current number of players waiting in the join queue.
     */
    public int getQueueSize() {
        return JoinQueueManager.getQueueSize();
    }

    // ----- Maintenance -----------------------------------------------------

    /**
     * @return {@code true} if the server is currently in maintenance mode.
     */
    public boolean isMaintenanceMode() {
        return MaintenanceModeManager.isEnabled();
    }
}
