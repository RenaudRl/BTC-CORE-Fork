package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;

public class ScoreboardOptimization {
    public static List<ServerPlayer> filterViewers(List<ServerPlayer> players) {
        if (!BTCCoreConfig.scoreboardOptimization || players == null) return players;
        return players.stream()
            .filter(p -> p.getBukkitEntity().getScoreboard().getObjectives().stream().anyMatch(
                obj -> obj.getCriteria().equals("dummy") || obj.getDisplayName() != null))
            .toList();
    }
}
