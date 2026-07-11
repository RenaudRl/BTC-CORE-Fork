package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * BTCCore: Scoreboard Optimization.
 * Filters which players receive scoreboard objective updates based on
 * whether they actually have the scoreboard in their display slot.
 * Reduces unnecessary scoreboard packet dispatch.
 *
 * Uses pure Bukkit API for scoreboard inspection — no NMS scoreboard types needed.
 * Wired via NMS hook in apply-btccore-patches.py: intercepts ScoreboardServer broadcast.
 */
public class ScoreboardOptimization {

    /**
     * Filters the list of NMS ServerPlayers to only those who have
     * at least one objective in a display slot on their active Bukkit scoreboard.
     *
     * Called from NMS before broadcasting scoreboard objective updates.
     */
    public static List<ServerPlayer> filterViewers(List<ServerPlayer> players) {
        if (!BTCCoreConfig.scoreboardOptimization || players == null) return players;

        List<ServerPlayer> filtered = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            var bukkitPlayer = player.getBukkitEntity();
            var bukkitScoreboard = bukkitPlayer.getScoreboard();
            if (bukkitScoreboard == null) {
                filtered.add(player);
                continue;
            }

            boolean hasDisplaySlot = false;
            for (org.bukkit.scoreboard.DisplaySlot slot : org.bukkit.scoreboard.DisplaySlot.values()) {
                if (bukkitScoreboard.getObjective(slot) != null) {
                    hasDisplaySlot = true;
                    break;
                }
            }

            if (hasDisplaySlot) {
                filtered.add(player);
            }
        }
        return filtered;
    }
}
