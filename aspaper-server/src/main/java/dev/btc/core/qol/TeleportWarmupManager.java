package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportWarmupManager {
    private static final Map<UUID, WarmupTask> warmups = new ConcurrentHashMap<>();

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
        BukkitTask bukkitTask;
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
            bukkitTask = Bukkit.getScheduler().runTaskTimer(
                Bukkit.getPluginManager().getPlugin("BTCCorePlugin"),
                this::tick, 0L, 1L
            );
        }

        void cancel() {
            if (bukkitTask != null && !bukkitTask.isCancelled()) {
                bukkitTask.cancel();
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
