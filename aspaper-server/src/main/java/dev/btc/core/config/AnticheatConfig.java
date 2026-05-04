package dev.btc.core.config;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Native Async Anticheat Configuration Manager
 * Handles the configuration for the high-performance async packet validation system.
 */
public final class AnticheatConfig {

    private static File configFile;
    private static YamlConfiguration config;

    public static int version;
    public static boolean verbose;

    // Async Network Engine
    public static boolean asyncPacketValidationEnabled = true;
    public static int asyncValidationThreads = 2; // Default to 2 dedicated checking threads

    // Reach Validation
    public static boolean reachCheckEnabled = true;
    public static double reachMaxDistanceSurvival = 3.0; // Standard Vanilla
    public static double reachMaxDistanceCreative = 5.0; 
    public static double reachViolationBufferLimit = 3.0; // Fail 3 reach checks in a row to start blocking
    public static boolean reachStrictHitboxMath = true;
    public static boolean reachRaytraceEnabled = true;
    public static double reachRaytraceLeniency = 0.1;
    public static int reachViolationAction = 0; // 0 = Cancel, 1 = Cancel + Log, 2 = Kick

    // Velocity / Movement Validation
    public static boolean velocityCheckEnabled = true;
    public static double velocityMaxDeltaXZ = 0.8;
    public static double velocityMaxDeltaY = 1.2;
    public static int velocityViolationAction = 0; // 0 = Teleport back, 1 = Teleport back + Log, 2 = Kick

    public static void init(File file) {
        AnticheatConfig.configFile = file;
        AnticheatConfig.config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException ignore) {
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load anticheat.yml, please correct your syntax errors", ex);
            throw new RuntimeException(ex);
        }
        config.options().copyDefaults(true);

        version = getInt("config-version", 1);
        set("config-version", 1);

        verbose = getBoolean("verbose", false);

        // Load Settings
        loadAsyncSettings();
        loadReachSettings();
        loadVelocitySettings();

        try {
            config.save(configFile);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save anticheat.yml", ex);
        }
    }

    private static void loadAsyncSettings() {
        config.setComments("engine.async-packet-validation", java.util.List.of(
            "################################################################",
            "# NATIVE ASYNCHRONOUS CHECKING ENGINE",
            "# This is the core performance driver of the Sentinel Anticheat.",
            "# Validations are offloaded to an asynchronous ForkJoin pool.",
            "################################################################"
        ));
        asyncPacketValidationEnabled = getBoolean("engine.async-packet-validation.enabled", true);
        config.setComments("engine.async-packet-validation.threads", java.util.List.of(
            "Number of dedicated async threads assigned strictly to parsing combat and movement network arrays.",
            "Default: 2 (Safe for most standard servers)"
        ));
        asyncValidationThreads = getInt("engine.async-packet-validation.threads", 2);
    }

    private static void loadReachSettings() {
        config.setComments("checks.reach", java.util.List.of(
            "################################################################",
            "# ASYNC REACH & COMBAT VALIDATOR",
            "# Powered by PlayerSimulationCache (AABB hitboxes)",
            "################################################################"
        ));
        reachCheckEnabled = getBoolean("checks.reach.enabled", true);
        config.setComments("checks.reach.max-distance-survival", java.util.List.of("Maximum allowable raytrace block hit distance for survival interactions."));
        reachMaxDistanceSurvival = getDouble("checks.reach.max-distance-survival", 3.0);
        config.setComments("checks.reach.max-distance-creative", java.util.List.of("Maximum allowable raytrace block hit distance for creative interactions."));
        reachMaxDistanceCreative = getDouble("checks.reach.max-distance-creative", 5.0);
        config.setComments("checks.reach.violation-buffer-limit", java.util.List.of("Number of violations allowed before synchronous attack blocking triggers. Default 3.0."));
        reachViolationBufferLimit = getDouble("checks.reach.violation-buffer-limit", 3.0);
        config.setComments("checks.reach.strict-hitbox-math", java.util.List.of("Enforces strictly mathematics tracing bounds without leniency margins. Requires perfect connection."));
        reachStrictHitboxMath = getBoolean("checks.reach.strict-hitbox-math", true);
        config.setComments("checks.reach.raytrace-enabled", java.util.List.of("Enables Line of Sight checks between attacker and target."));
        reachRaytraceEnabled = getBoolean("checks.reach.raytrace-enabled", true);
        reachRaytraceLeniency = getDouble("checks.reach.raytrace-leniency", 0.1);
        config.setComments("checks.reach.violation-action", java.util.List.of("Action thresholds upon detection. 0 = Cancel Hit, 1 = Cancel + Alert, 2 = Kick"));
        reachViolationAction = getInt("checks.reach.violation-action", 0);
    }

    private static void loadVelocitySettings() {
        config.setComments("checks.velocity", java.util.List.of(
            "################################################################",
            "# FAST-MATH VELOCITY VALIDATOR",
            "# Ensures motion values accurately align with server-side physics.",
            "################################################################"
        ));
        velocityCheckEnabled = getBoolean("checks.velocity.enabled", true);
        config.setComments("checks.velocity.max-delta-xz", java.util.List.of("Maximum XZ (horizontal) movement un-aided speed change per tick"));
        velocityMaxDeltaXZ = getDouble("checks.velocity.max-delta-xz", 0.8);
        config.setComments("checks.velocity.max-delta-y", java.util.List.of("Maximum Y (vertical) jump/fall un-aided speed change per tick"));
        velocityMaxDeltaY = getDouble("checks.velocity.max-delta-y", 1.2);
        config.setComments("checks.velocity.violation-action", java.util.List.of("Action thresholds upon detection. 0 = Silent Setback, 1 = Setback + Alert, 2 = Setback + Kick"));
        velocityViolationAction = getInt("checks.velocity.violation-action", 0);
    }

    private static void set(String path, Object val) {
        config.addDefault(path, val);
        config.set(path, val);
    }

    private static boolean getBoolean(String path, boolean def) {
        config.addDefault(path, def);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static int getInt(String path, int def) {
        config.addDefault(path, def);
        return config.getInt(path, config.getInt(path));
    }

    private static double getDouble(String path, double def) {
        config.addDefault(path, def);
        return config.getDouble(path, config.getDouble(path));
    }

    private static String getString(String path, String def) {
        config.addDefault(path, def);
        return config.getString(path, config.getString(path));
    }
}

