package dev.btc.core.placeholder;

import dev.btc.core.api.BTCCoreAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion exposing BTC-CORE server signals.
 *
 * <p>All values are read from the stable {@link BTCCoreAPI} facade. Registered from
 * {@code SWPlugin#onEnable()} only when PlaceholderAPI is installed, so BTC-CORE keeps
 * PlaceholderAPI as a soft (compile-only) dependency.</p>
 *
 * <p>Server placeholders: {@code %btccore_mspt%}, {@code %btccore_mspt_int%},
 * {@code %btccore_tps%}, {@code %btccore_maintenance%}, {@code %btccore_queue_size%}.<br>
 * Player placeholders: {@code %btccore_in_combat%}, {@code %btccore_combat_time%},
 * {@code %btccore_vanished%}, {@code %btccore_vanish_level%}, {@code %btccore_queue_position%}.</p>
 */
public final class BTCCorePlaceholderExpansion extends PlaceholderExpansion {

    @Override
    public @NotNull String getIdentifier() {
        return "btccore";
    }

    @Override
    public @NotNull String getAuthor() {
        return "BTC";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        // Keep the expansion registered across PlaceholderAPI reloads.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        final BTCCoreAPI api = BTCCoreAPI.instance();

        // ----- server-scope placeholders -----
        switch (params.toLowerCase()) {
            case "mspt":
                return String.format("%.2f", api.getCurrentMspt());
            case "mspt_int":
                return String.valueOf(Math.round(api.getCurrentMspt()));
            case "tps": {
                double mspt = api.getCurrentMspt();
                double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
                return String.format("%.2f", tps);
            }
            case "maintenance":
                return String.valueOf(api.isMaintenanceMode());
            case "queue_size":
                return String.valueOf(api.getQueueSize());
            default:
                break;
        }

        // ----- player-scope placeholders -----
        final Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
        if (player == null) {
            return "";
        }
        switch (params.toLowerCase()) {
            case "in_combat":
                return String.valueOf(api.isInCombat(player));
            case "combat_time":
                return String.valueOf(api.getRemainingCombatTime(player));
            case "vanished":
                return String.valueOf(api.isVanished(player));
            case "vanish_level":
                return String.valueOf(api.getVanishLevel(player));
            case "queue_position":
                return String.valueOf(api.getQueuePosition(player.getUniqueId()));
            default:
                return null;
        }
    }
}
