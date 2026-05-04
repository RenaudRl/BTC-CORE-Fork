package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

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
     * Gets the maintenance mode kick message.
     *
     * @return The kick message as a Component
     */
    public static Component getKickMessage() {
        return MiniMessage.miniMessage().deserialize(BTCCoreConfig.maintenanceModeMessage);
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

