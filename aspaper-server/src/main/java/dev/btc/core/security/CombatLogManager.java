package dev.btc.core.security;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTCCore: Combat Log Prevention System.
 * Tags players in combat and handles logout during combat.
 */
public final class CombatLogManager {

    private static final Map<UUID, Long> combatTags = new ConcurrentHashMap<>();
    private static long currentTick = 0;

    private CombatLogManager() {}

    /**
     * Called at the start of each server tick.
     */
    public static void onTickStart(long tick) {
        currentTick = tick;
    }

    /**
     * Tags a player as being in combat.
     *
     * @param player The player to tag
     */
    public static void tagPlayer(Player player) {
        if (!BTCCoreConfig.combatLogEnabled) {
            return;
        }
        combatTags.put(player.getUniqueId(), currentTick);
    }

    /**
     * Tags both players in a combat interaction.
     *
     * @param attacker The attacking player
     * @param victim The victim player
     */
    public static void tagCombat(Player attacker, Player victim) {
        tagPlayer(attacker);
        tagPlayer(victim);
    }

    /**
     * Checks if a player is currently in combat.
     *
     * @param player The player to check
     * @return true if the player is tagged for combat
     */
    public static boolean isInCombat(Player player) {
        if (!BTCCoreConfig.combatLogEnabled) {
            return false;
        }

        Long tagTick = combatTags.get(player.getUniqueId());
        if (tagTick == null) {
            return false;
        }

        long tagDurationTicks = BTCCoreConfig.combatLogTagDuration * 20L; // Convert seconds to ticks
        if (currentTick - tagTick > tagDurationTicks) {
            combatTags.remove(player.getUniqueId());
            return false;
        }

        return true;
    }

    /**
     * Removes a player's combat tag.
     *
     * @param player The player to untag
     */
    public static void untagPlayer(Player player) {
        combatTags.remove(player.getUniqueId());
    }

    /**
     * Gets the remaining combat tag time in seconds.
     *
     * @param player The player to check
     * @return Remaining time in seconds, or 0 if not tagged
     */
    public static int getRemainingCombatTime(Player player) {
        if (!BTCCoreConfig.combatLogEnabled) {
            return 0;
        }

        Long tagTick = combatTags.get(player.getUniqueId());
        if (tagTick == null) {
            return 0;
        }

        long tagDurationTicks = BTCCoreConfig.combatLogTagDuration * 20L;
        long elapsed = currentTick - tagTick;
        long remaining = tagDurationTicks - elapsed;

        return remaining > 0 ? (int) (remaining / 20) : 0;
    }

    /**
     * Handles a player logging out while in combat.
     *
     * @param player The player who is logging out
     * @return true if the player was killed for combat logging
     */
    public static boolean handleLogout(Player player) {
        if (!BTCCoreConfig.combatLogEnabled || !BTCCoreConfig.combatLogKillOnLogout) {
            combatTags.remove(player.getUniqueId());
            return false;
        }

        if (isInCombat(player)) {
            player.setHealth(0);
            Bukkit.getLogger().info("[BTCCore] Player " + player.getName() + " was killed for combat logging.");
            combatTags.remove(player.getUniqueId());
            return true;
        }

        combatTags.remove(player.getUniqueId());
        return false;
    }
}

