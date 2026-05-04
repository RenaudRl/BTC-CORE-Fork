package org.purpurmc.purpur;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Minimal PurpurConfig stub for BTC-CORE
 * Provides the config keys needed by Purpur feature patches.
 * Reads from purpur.yml (server root).
 */
public class PurpurConfig {
    public static YamlConfiguration config;
    private static File configFile;

    // Barrels and enderchests
    public static boolean enderChestSixRows = false;
    public static boolean enderChestPermissionRows = false;
    public static boolean enderChestPersistHiddenRows = false;
    public static int barrelRows = 3;

    // Ridables
    public static boolean cannotRideMob = false;
    public static boolean endermanShortHeight = false;

    // Minecarts (per-world, defaults here)
    public static boolean minecartControllable = true;
    public static double minecartMaxSpeed = 0.4D;
    public static float minecartControllableStepHeight = 1.0F;
    public static double minecartControllableHopBoost = 0.5D;
    public static boolean minecartControllableFallDamage = true;
    public static double minecartControllableBaseSpeed = 0.1D;

    // Elytra (per-world, defaults here)
    public static int elytraDamagePerSecond = 1;
    public static double elytraDamageMultiplyBySpeed = 0;
    public static int elytraDamagePerFireworkBoost = 0;
    public static int elytraDamagePerTridentBoost = 0;
    public static boolean elytraKineticDamage = true;

    // Silk touch spawners
    public static boolean silkTouchSpawners = true;

    // Ender pearls
    public static boolean enderPearlCooldown = true;
    public static int enderPearlCooldownTicks = 20;

    static {
        configFile = new File("purpur.yml");
        if (!configFile.exists()) {
            configFile = new File(Bukkit.getServer() != null ? 
                Bukkit.getServer().getWorldContainer() : new File("."), "purpur.yml");
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        load();
    }

    public static void init() {
        // Called by BTCCore plugin init
        load();
        Bukkit.getLogger().info("[BTCCore] Purpur features config loaded");
    }

    public static void registerCommands() {
        // Register purpur commands - stub for patches that call this
    }

    private static void load() {
        enderChestSixRows = getBoolean("settings.ender-chest.six-rows", enderChestSixRows);
        enderChestPermissionRows = getBoolean("settings.ender-chest.permission-rows", enderChestPermissionRows);
        barrelRows = getInt("settings.barrel.rows", barrelRows);

        cannotRideMob = getBoolean("settings.ridables.cannot-ride-mob", cannotRideMob);
        endermanShortHeight = getBoolean("settings.ridables.enderman-short-height", endermanShortHeight);

        minecartControllable = getBoolean("settings.minecart.controllable", minecartControllable);
        minecartMaxSpeed = getDouble("settings.minecart.max-speed", minecartMaxSpeed);
        minecartControllableStepHeight = (float) getDouble("settings.minecart.step-height", minecartControllableStepHeight);
        minecartControllableHopBoost = getDouble("settings.minecart.hop-boost", minecartControllableHopBoost);
        minecartControllableFallDamage = getBoolean("settings.minecart.fall-damage", minecartControllableFallDamage);
        minecartControllableBaseSpeed = getDouble("settings.minecart.base-speed", minecartControllableBaseSpeed);

        elytraDamagePerSecond = getInt("settings.elytra.damage-per-second", elytraDamagePerSecond);
        elytraKineticDamage = getBoolean("settings.elytra.kinetic-damage", elytraKineticDamage);

        silkTouchSpawners = getBoolean("settings.spawners.silk-touch", silkTouchSpawners);
        enderPearlCooldown = getBoolean("settings.ender-pearl.cooldown", enderPearlCooldown);
    }

    private static boolean getBoolean(String path, boolean def) { config.addDefault(path, def); return config.getBoolean(path, def); }
    private static int getInt(String path, int def) { config.addDefault(path, def); return config.getInt(path, def); }
    private static double getDouble(String path, double def) { config.addDefault(path, def); return config.getDouble(path, def); }
    private static String getString(String path, String def) { config.addDefault(path, def); return config.getString(path, def); }
}
