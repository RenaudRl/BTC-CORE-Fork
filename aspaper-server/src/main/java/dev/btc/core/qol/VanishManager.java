package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanishManager {
    private static final Map<UUID, Integer> vanishLevels = new ConcurrentHashMap<>();
    private static Plugin plugin;

    public static void init(Plugin pl) {
        plugin = pl;
        vanishLevels.clear();
    }

    public static void setVanishLevel(Player player, int level) {
        if (!BTCCoreConfig.vanishLevelsEnabled || plugin == null) return;
        int oldLevel = vanishLevels.getOrDefault(player.getUniqueId(), 0);
        vanishLevels.put(player.getUniqueId(), level);
        if (level > 0 && oldLevel == 0) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) other.hidePlayer(plugin, player);
            }
        } else if (level == 0 && oldLevel > 0) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) other.showPlayer(plugin, player);
            }
        }
    }

    public static int getVanishLevel(Player player) {
        return vanishLevels.getOrDefault(player.getUniqueId(), 0);
    }

    public static boolean isVanished(Player player) {
        return getVanishLevel(player) > 0;
    }

    public static boolean isInteractionBlocked(Player player) {
        return getVanishLevel(player) >= 2;
    }

    public static void onPlayerJoin(Player newPlayer) {
        if (!BTCCoreConfig.vanishLevelsEnabled || plugin == null) return;
        for (Map.Entry<UUID, Integer> entry : vanishLevels.entrySet()) {
            if (entry.getValue() > 0) {
                Player vanished = Bukkit.getPlayer(entry.getKey());
                if (vanished != null && !vanished.equals(newPlayer)) {
                    newPlayer.hidePlayer(plugin, vanished);
                }
            }
        }
    }

    public static void onPlayerQuit(Player player) {
        vanishLevels.remove(player.getUniqueId());
    }
}
