package dev.btc.core.commands;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.performance.PerformanceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * BTCCore: Debug command to display feature status.
 * Usage: /btccore debug
 */
public class BTCCoreDebugCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("btccore.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("═══ BTC Core Debug ═══", NamedTextColor.GOLD, TextDecoration.BOLD));

        // Version
        sender.sendMessage(Component.text("Version: ", NamedTextColor.YELLOW)
            .append(Component.text("26.2.build.1-alpha", NamedTextColor.WHITE)));

        // Zero Features
        sender.sendMessage(Component.text("\n── Zero Features ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Recipes", BTCCoreConfig.zfRecipesEnabled);
        sendFeatureStatus(sender, "Advancements", BTCCoreConfig.zfAdvancementsEnabled);
        sendFeatureStatus(sender, "Stats", BTCCoreConfig.zfStatsEnabled);
        sendFeatureStatus(sender, "Light Engine", BTCCoreConfig.zfLightEngineEnabled);
        sendFeatureStatus(sender, "Collisions", BTCCoreConfig.zfCollisionsEnabled);
        sendFeatureStatus(sender, "Cramming", BTCCoreConfig.zfCrammingEnabled);
        sendFeatureStatus(sender, "Block Updates", BTCCoreConfig.zfBlockUpdatesEnabled);
        sendFeatureStatus(sender, "Sleep Tick", BTCCoreConfig.zfSleepTickEnabled);
        sendFeatureStatus(sender, "Void Generator", BTCCoreConfig.zfForceVoidGenerator);

        // Performance
        sender.sendMessage(Component.text("\n── Performance ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Async Entity Tracker", BTCCoreConfig.asyncEntityTrackerEnabled);
        sendFeatureStatus(sender, "Async Pathfinding", BTCCoreConfig.asyncPathfindingEnabled);
        sendFeatureStatus(sender, "Async Mob Spawning", BTCCoreConfig.asyncMobSpawningEnabled);
        sendFeatureStatus(sender, "DEAR (DAB)", BTCCoreConfig.dearEnabled);
        sendFeatureStatus(sender, "Suffocation Opt", BTCCoreConfig.suffocationOptimization);
        sendFeatureStatus(sender, "Inactive Goal Throttle", BTCCoreConfig.inactiveGoalSelectorThrottle);
        sendFeatureStatus(sender, "Hopper Throttle", BTCCoreConfig.hopperThrottlingEnabled);
        sendFeatureStatus(sender, "Collision Throttle", BTCCoreConfig.collisionThrottleEnabled);
        sendFeatureStatus(sender, "Particle Culling", BTCCoreConfig.particleCullingEnabled);
        sendFeatureStatus(sender, "Sound Culling", BTCCoreConfig.soundCullingEnabled);
        sendFeatureStatus(sender, "Light Throttle", BTCCoreConfig.lightThrottleEnabled);
        sendFeatureStatus(sender, "Redstone Throttle", BTCCoreConfig.redstoneThrottleEnabled);
        sendFeatureStatus(sender, "Lazy Chunk Tickets", BTCCoreConfig.lazyChunkTicketsEnabled);
        sendFeatureStatus(sender, "Scoreboard Opt", BTCCoreConfig.scoreboardOptimization);
        sendFeatureStatus(sender, "Batched Inventory", BTCCoreConfig.batchedInventoryUpdates);
        sendFeatureStatus(sender, "NBT Compression", BTCCoreConfig.nbtCompressionCache);
        sendFeatureStatus(sender, "Chunk Prefetch", BTCCoreConfig.chunkPrefetchEnabled);
        sendFeatureStatus(sender, "Projectile Pooling", BTCCoreConfig.projectilePoolingEnabled);
        sendFeatureStatus(sender, "Async Block Updates", BTCCoreConfig.asyncBlockUpdatesEnabled);
        sendFeatureStatus(sender, "PreDamage Event", BTCCoreConfig.preDamageEventEnabled);
        sendFeatureStatus(sender, "Per-World Tick Rate", BTCCoreConfig.perWorldTickRateEnabled);
        sendFeatureStatus(sender, "Static Redstone Graph", BTCCoreConfig.rpgRedstoneStaticGraphEnabled);

        // MSPT
        sender.sendMessage(Component.text("\n── MSPT ──", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Current MSPT: ", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.2f", PerformanceManager.getCurrentMspt()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("MSPT Threshold: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.msptThreshold + "ms", NamedTextColor.WHITE)));

        // Vanilla Tick Suppression
        sender.sendMessage(Component.text("\n── Vanilla Tick Suppression ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "AI", BTCCoreConfig.vanillaTickSuppressionAi);
        sendFeatureStatus(sender, "Brain", BTCCoreConfig.vanillaTickSuppressionBrain);
        sendFeatureStatus(sender, "Sensors", BTCCoreConfig.vanillaTickSuppressionSensors);

        // Security
        sender.sendMessage(Component.text("\n── Security ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Sentinel Anticheat", BTCCoreConfig.sentinelEnabled);
        sendFeatureStatus(sender, "Freedom Chat", BTCCoreConfig.freedomChatEnabled);
        sendFeatureStatus(sender, "Combat Log", BTCCoreConfig.combatLogEnabled);
        sendFeatureStatus(sender, "CPS Limit", BTCCoreConfig.cpsLimitEnabled);
        sendFeatureStatus(sender, "Reach Validation", BTCCoreConfig.reachValidationEnabled);
        sendFeatureStatus(sender, "Flight Detection", BTCCoreConfig.flightDetectionEnabled);
        sendFeatureStatus(sender, "Exploit Logging", BTCCoreConfig.exploitLoggingEnabled);

        // QoL
        sender.sendMessage(Component.text("\n── Quality of Life ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Join Queue", BTCCoreConfig.joinQueueEnabled);
        sendFeatureStatus(sender, "Vanish Levels", BTCCoreConfig.vanishLevelsEnabled);
        sendFeatureStatus(sender, "Maintenance Mode", BTCCoreConfig.maintenanceModeEnabled);
        sendFeatureStatus(sender, "Player Data Backup", BTCCoreConfig.playerDataBackupEnabled);
        sendFeatureStatus(sender, "Async Tab Complete", BTCCoreConfig.asyncTabCompleteEnabled);
        sendFeatureStatus(sender, "Async Chunk Loading", BTCCoreConfig.asyncChunkLoading);

        // Entity settings
        sender.sendMessage(Component.text("\n── Entity Settings ──", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("DEAR Start Distance: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.dearStartDistance, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Collision Throttle Max: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.collisionThrottleMaxEntities, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Hopper Throttle Ticks: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.hopperThrottlingTicks, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Light Throttle Max: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.lightThrottleMaxPerTick, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Redstone Throttle Max: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.redstoneThrottleMaxPerChunk, NamedTextColor.WHITE)));

        // Async Pools
        sender.sendMessage(Component.text("\n── Async Pools ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Entity Tracker Pool", BTCCoreConfig.asyncEntityTrackerEnabled);
        sendFeatureStatus(sender, "Pathfinding Pool", BTCCoreConfig.asyncPathfindingEnabled);

        // World patterns
        if (!BTCCoreConfig.zfWorldPatterns.isEmpty()) {
            sender.sendMessage(Component.text("\n── Zero Feature World Patterns ──", NamedTextColor.AQUA));
            for (String pattern : BTCCoreConfig.zfWorldPatterns) {
                sender.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(pattern, NamedTextColor.WHITE)));
            }
        }

        if (!BTCCoreConfig.rpgRedstoneStaticGraphWorlds.isEmpty()) {
            sender.sendMessage(Component.text("\n── Static Redstone Worlds ──", NamedTextColor.AQUA));
            for (String world : BTCCoreConfig.rpgRedstoneStaticGraphWorlds) {
                sender.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(world, NamedTextColor.WHITE)));
            }
        }

        sender.sendMessage(Component.text("\n═══════════════════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        return true;
    }

    private void sendFeatureStatus(CommandSender sender, String name, boolean enabled) {
        sender.sendMessage(Component.text("  " + name + ": ", NamedTextColor.YELLOW)
            .append(Component.text(enabled ? "ON" : "OFF",
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }
}
