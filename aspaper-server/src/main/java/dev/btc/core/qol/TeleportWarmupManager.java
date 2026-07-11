package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportWarmupManager {
    private static final Map<UUID, WarmupTask> warmups = new ConcurrentHashMap<>();

    private static Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("ASPaper");
    }

    public static boolean startWarmup(Player player, Location from, Location to, Runnable onComplete) {
        if (BTCCoreConfig.teleportWarmupTicks <= 0) return false;
        cancelWarmup(player);
        int ticks = BTCCoreConfig.teleportWarmupTicks;
        WarmupTask task = new WarmupTask(player, from, to, onComplete, ticks);
        warmups.put(player.getUniqueId(), task);
        task.start();
        return true;
    }

    public static void cancelWarmup(Player player) {
        WarmupTask existing = warmups.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
            player.sendActionBar(Component.text("Teleport cancelled", NamedTextColor.RED));
        }
    }

    public static void checkPlayerMove(Player player, Location to) {
        WarmupTask task = warmups.get(player.getUniqueId());
        if (task != null && task.from.distanceSquared(to) > 0.25) {
            cancelWarmup(player);
        }
    }

    public static boolean isWarmingUp(Player player) {
        return warmups.containsKey(player.getUniqueId());
    }

    private static class WarmupTask {
        final Player player;
        final Location from;
        final Location to;
        final Runnable onComplete;
        final int totalTicks;
        int remainingTicks;
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask;
        boolean completed = false;

        WarmupTask(Player player, Location from, Location to, Runnable onComplete, int totalTicks) {
            this.player = player;
            this.from = from;
            this.to = to;
            this.onComplete = onComplete;
            this.totalTicks = totalTicks;
            this.remainingTicks = totalTicks;
        }

        void start() {
            Plugin plugin = getPlugin();
            if (plugin == null) return;
            scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, task -> tick(), 0L, 1L
            );
        }

        void cancel() {
            if (scheduledTask != null && !scheduledTask.isCancelled()) {
                scheduledTask.cancel();
            }
        }

        void tick() {
            if (completed) return;
            remainingTicks--;
            if (remainingTicks <= 0) {
                completed = true;
                cancel();
                warmups.remove(player.getUniqueId());
                onComplete.run();
            } else {
                float seconds = remainingTicks / 20.0f;
                player.sendActionBar(Component.text(
                    String.format("Teleporting in %.1fs...", seconds), NamedTextColor.YELLOW
                ));
            }
        }
    }
}
