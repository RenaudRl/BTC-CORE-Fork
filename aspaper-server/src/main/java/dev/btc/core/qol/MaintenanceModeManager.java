package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.kyori.adventure.text.Component;

import java.util.Map;
import java.util.UUID;

/**
 * BTCCore: Maintenance Mode Manager.
 * Controls server maintenance state.
 */
public final class MaintenanceModeManager {

    private static boolean maintenanceMode = false;

    private MaintenanceModeManager() {}

    /**
     * Initializes maintenance mode from config.
     */
    public static void init() {
        maintenanceMode = BTCCoreConfig.maintenanceModeEnabled;
    }

    /**
     * Checks if maintenance mode is enabled.
     *
     * @return true if maintenance mode is active
     */
    public static boolean isEnabled() {
        return maintenanceMode;
    }

    /**
     * Enables maintenance mode.
     */
    public static void enable() {
        maintenanceMode = true;
    }

    /**
     * Disables maintenance mode.
     */
    public static void disable() {
        maintenanceMode = false;
    }

    /**
     * Toggles maintenance mode.
     *
     * @return The new state
     */
    public static boolean toggle() {
        maintenanceMode = !maintenanceMode;
        return maintenanceMode;
    }

    /**
     * Gets the maintenance mode kick message, in the language of the player it is for.
     *
     * @param viewer the player being refused (not necessarily online yet)
     * @return The kick message as a Component
     */
    public static Component getKickMessage(UUID viewer) {
        return PlayerTexts.shared().render(viewer, PlayerText.LOGIN_MAINTENANCE, Map.of());
    }

    /**
     * Checks if a player can bypass maintenance mode.
     *
     * @param player The player to check
     * @return true if they can bypass
     */
    public static boolean canBypass(org.bukkit.entity.Player player) {
        return player.hasPermission("btccore.maintenance.bypass");
    }
}

