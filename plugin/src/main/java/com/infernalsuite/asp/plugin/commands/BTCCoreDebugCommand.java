package com.infernalsuite.asp.plugin.commands;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.performance.PerformanceManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * BTCCore: Debug command to display feature status.
 * Usage: /btccore
 *
 * <p>Lives in the plugin module (not the server) on purpose: it is registered as a
 * Paper {@link BasicCommand} from {@code SWPlugin}, so the {@link BasicCommand}
 * interface it implements must be resolved by the same classloader that performs the
 * registration. Keeping it here guarantees the plugin classloader owns this class and
 * resolves {@link BasicCommand} from the server via its parent — avoiding the
 * {@code IncompatibleClassChangeError} that occurs when a server-module class is passed
 * across the plugin/server classloader boundary as a {@link BasicCommand}.
 */
public class BTCCoreDebugCommand implements BasicCommand {

    private static final String PERMISSION = "btccore.admin";

    /** How far {@code /btccore redstone probe} looks for the block to start compiling from. */
    private static final int PROBE_RANGE = 12;

    @Override
    public @NotNull String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        final CommandSender sender = source.getSender();

        if (args.length > 0 && "redstone".equalsIgnoreCase(args[0])) {
            this.redstone(sender, args);
            return;
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

        // Vanilla content purge — reports what is actually registered, not what the config claims.
        sender.sendMessage(Component.text("\n── Vanilla Content ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Purge Advancements", BTCCoreConfig.purgeVanillaAdvancements);
        sendFeatureStatus(sender, "Purge Recipes", BTCCoreConfig.purgeVanillaRecipes);
        sendFeatureStatus(sender, "Preserve Special Recipes", BTCCoreConfig.preserveSpecialRecipes);
        sendNamespaceBreakdown(sender, "Advancements loaded", countAdvancementNamespaces());
        sendNamespaceBreakdown(sender, "Recipes loaded", countRecipeNamespaces());

        // World gating
        sender.sendMessage(Component.text("\n── Worlds ──", NamedTextColor.AQUA));
        sendFeatureStatus(sender, "Overworld Only", BTCCoreConfig.overworldOnly);
        sender.sendMessage(Component.text("  Loaded worlds: ", NamedTextColor.GRAY)
            .append(Component.text(org.bukkit.Bukkit.getWorlds().stream()
                .map(w -> w.getName() + "/" + w.getEnvironment())
                .collect(java.util.stream.Collectors.joining(", ")), NamedTextColor.WHITE)));

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
        sendFeatureStatus(sender, "Lazy Chunk Tickets", BTCCoreConfig.lazyChunkTicketsEnabled);
        sendFeatureStatus(sender, "Scoreboard Opt", BTCCoreConfig.scoreboardOptimization);
        sendFeatureStatus(sender, "Batched Inventory", BTCCoreConfig.batchedInventoryUpdates);
        sendFeatureStatus(sender, "NBT Compression", BTCCoreConfig.nbtCompressionCache);
        sendFeatureStatus(sender, "Chunk Prefetch", BTCCoreConfig.chunkPrefetchEnabled);
        sendFeatureStatus(sender, "Projectile Pooling", BTCCoreConfig.projectilePoolingEnabled);
        sendFeatureStatus(sender, "PreDamage Event", BTCCoreConfig.preDamageEventEnabled);
        sendFeatureStatus(sender, "Per-World Tick Rate", BTCCoreConfig.perWorldTickRateEnabled);
        sendFeatureStatus(sender, "Redstone Compiler", BTCCoreConfig.redstoneCompilerEnabled);

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
        sender.sendMessage(Component.text("Redstone Compile Threshold: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.redstoneCompilerActivityThreshold, NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Redstone Compile Max Nodes: ", NamedTextColor.YELLOW)
            .append(Component.text(BTCCoreConfig.redstoneCompilerMaxNodes, NamedTextColor.WHITE)));

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

        if (!BTCCoreConfig.redstoneCompilerWorlds.isEmpty()) {
            sender.sendMessage(Component.text("\n── Redstone Compiler Worlds ──", NamedTextColor.AQUA));
            for (String world : BTCCoreConfig.redstoneCompilerWorlds) {
                sender.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(world, NamedTextColor.WHITE)));
            }
        }

        sender.sendMessage(Component.text("\n═══════════════════════", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    /** Counts the advancements actually registered on the server, grouped by namespace. */
    private java.util.Map<String, Integer> countAdvancementNamespaces() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        java.util.Iterator<org.bukkit.advancement.Advancement> it = org.bukkit.Bukkit.advancementIterator();
        while (it.hasNext()) {
            counts.merge(it.next().getKey().getNamespace(), 1, Integer::sum);
        }
        return counts;
    }

    /** Counts the recipes actually registered on the server, grouped by namespace. */
    private java.util.Map<String, Integer> countRecipeNamespaces() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        java.util.Iterator<org.bukkit.inventory.Recipe> it = org.bukkit.Bukkit.recipeIterator();
        while (it.hasNext()) {
            org.bukkit.inventory.Recipe recipe = it.next();
            String namespace = recipe instanceof org.bukkit.Keyed keyed ? keyed.getKey().getNamespace() : "unkeyed";
            counts.merge(namespace, 1, Integer::sum);
        }
        return counts;
    }

    private void sendNamespaceBreakdown(CommandSender sender, String label, java.util.Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        String detail = counts.isEmpty()
            ? "none"
            : counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        int vanilla = counts.getOrDefault("minecraft", 0);
        sender.sendMessage(Component.text("  " + label + ": ", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(total), NamedTextColor.WHITE))
            .append(Component.text("  [" + detail + "]", vanilla == 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
    }

    private void redstone(CommandSender sender, String[] args) {
        if (args.length >= 2 && "bench".equalsIgnoreCase(args[1])) {
            this.redstoneBench(sender, args);
            return;
        }
        if (args.length >= 2 && "probe".equalsIgnoreCase(args[1])) {
            this.redstoneProbe(sender);
            return;
        }
        if (args.length >= 2 && "verify".equalsIgnoreCase(args[1])) {
            this.redstoneVerify(sender, args);
            return;
        }
        sender.sendMessage(Component.text("Usage: /btccore redstone bench [ticks] | probe | verify [ticks]",
            NamedTextColor.YELLOW));
    }

    /**
     * {@code /btccore redstone verify [ticks]} — runs the compiled graph beside the untouched world
     * and reports every tick on which the two disagree.
     *
     * <p>This is the only check that the graph is the <em>same circuit</em> as the blocks: compiling
     * successfully proves nothing about whether the topology was read correctly.
     */
    private void redstoneVerify(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(Component.text("This must be run by a player: verification starts from the block you look at.",
                NamedTextColor.RED));
            return;
        }
        final org.bukkit.block.Block target = player.getTargetBlockExact(PROBE_RANGE);
        if (target == null) {
            sender.sendMessage(Component.text("Look at a block of the circuit (within " + PROBE_RANGE + " blocks).",
                NamedTextColor.RED));
            return;
        }

        int ticks = 100;
        if (args.length >= 3) {
            try {
                ticks = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("'" + args[2] + "' is not a number of ticks.", NamedTextColor.RED));
                return;
            }
            if (ticks < 20 || ticks > 12000) {
                sender.sendMessage(Component.text("Verification length must be within 20..12000 ticks.", NamedTextColor.RED));
                return;
            }
        }

        final java.util.UUID id = player.getUniqueId();
        dev.btc.core.redstone.RedstoneVerifier.start(
            ((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle(),
            new net.minecraft.core.BlockPos(target.getX(), target.getY(), target.getZ()),
            ticks,
            line -> {
                final org.bukkit.entity.Player back = org.bukkit.Bukkit.getPlayer(id);
                if (back != null) {
                    back.sendMessage(Component.text("[redstone] " + line, NamedTextColor.AQUA));
                }
            });
    }

    /**
     * {@code /btccore redstone probe} — compiles the circuit containing the block the player is
     * looking at, right now, and reports either its size or the exact reason the compiler walked
     * away.
     *
     * <p>A dry run: the resulting graph is discarded rather than installed as a zone. It exists
     * because a benchmark can only report a refusal that the activity threshold happened to trigger,
     * which makes "is this circuit compilable?" an unanswerable question on a quiet circuit.
     */
    private void redstoneProbe(CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(Component.text("This must be run by a player: the probe starts from the block you look at.",
                NamedTextColor.RED));
            return;
        }
        final org.bukkit.block.Block target = player.getTargetBlockExact(PROBE_RANGE);
        if (target == null) {
            sender.sendMessage(Component.text("Look at a block of the circuit (within " + PROBE_RANGE + " blocks).",
                NamedTextColor.RED));
            return;
        }

        final net.minecraft.server.level.ServerLevel level =
            ((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle();
        final net.minecraft.core.BlockPos pos =
            new net.minecraft.core.BlockPos(target.getX(), target.getY(), target.getZ());

        sender.sendMessage(Component.text("[redstone] Probing " + target.getType() + " at "
            + target.getX() + "," + target.getY() + "," + target.getZ()
            + " in '" + player.getWorld().getName() + "' (whitelisted: "
            + (BTCCoreConfig.isRedstoneCompilerEnabledFor(player.getWorld().getName()) ? "yes" : "NO") + ")",
            NamedTextColor.AQUA));

        final dev.btc.core.redstone.compile.CompileResult result =
            dev.btc.core.redstone.compile.GraphCompiler.compile(level, pos,
                BTCCoreConfig.redstoneCompilerMaxNodes, BTCCoreConfig.redstoneCompilerMaxExtent);

        if (!result.succeeded()) {
            sender.sendMessage(Component.text("[redstone] REFUSED: " + result.refusal(), NamedTextColor.RED));
            return;
        }

        final dev.btc.core.redstone.graph.CompiledGraph graph = result.compilation().graph();
        sender.sendMessage(Component.text("[redstone] COMPILABLE: " + graph.size() + " node(s), box "
            + graph.minX() + "," + graph.minY() + "," + graph.minZ() + " to "
            + graph.maxX() + "," + graph.maxY() + "," + graph.maxZ(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("[redstone] Dry run: this graph was discarded. The manager "
            + "installs one only once the circuit is active.", NamedTextColor.GRAY));
    }

    /**
     * {@code /btccore redstone bench [ticks]} — measures the same circuit twice, once compiled and
     * once handed back to vanilla redstone, and reports both as an MSPT contribution.
     *
     * <p>Must be run by a player: the world being measured is the one the player stands in, since
     * the compiler only ever touches whitelisted worlds.
     */
    private void redstoneBench(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(Component.text("This must be run by a player: the measured world is the one you are in.",
                NamedTextColor.RED));
            return;
        }

        int ticks = 200;
        if (args.length >= 3) {
            try {
                ticks = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("'" + args[2] + "' is not a number of ticks.", NamedTextColor.RED));
                return;
            }
            if (ticks < 20 || ticks > 12000) {
                sender.sendMessage(Component.text("Sample length must be within 20..12000 ticks.", NamedTextColor.RED));
                return;
            }
        }

        final net.minecraft.server.level.ServerLevel level =
            ((org.bukkit.craftbukkit.CraftWorld) player.getWorld()).getHandle();

        final java.util.UUID id = player.getUniqueId();
        final boolean started = dev.btc.core.redstone.RedstoneProfiler.start(level, ticks, line -> {
            final org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(id);
            if (target != null) {
                target.sendMessage(Component.text("[redstone] " + line, NamedTextColor.AQUA));
            }
        });

        if (!started) {
            sender.sendMessage(Component.text("Could not start: a benchmark is already running, "
                + "or this world is not eligible.", NamedTextColor.RED));
        }
    }

    private void sendFeatureStatus(CommandSender sender, String name, boolean enabled) {
        sender.sendMessage(Component.text("  " + name + ": ", NamedTextColor.YELLOW)
            .append(Component.text(enabled ? "ON" : "OFF",
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }
}
