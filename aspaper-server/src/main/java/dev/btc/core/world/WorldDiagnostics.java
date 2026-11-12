package dev.btc.core.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code /btccore} reads of the worlds: what each one costs ({@code world.metrics},
 * {@link WorldTickMetrics}). Off by default: nothing here runs.
 */
public final class WorldDiagnostics {

    private WorldDiagnostics() {
    }

    /** The worlds that cost the most, the costliest first; empty with {@code world.metrics} off. Any thread. */
    public static List<WorldTickMetrics.Row> costliest(MinecraftServer server, int limit) {
        List<WorldTickMetrics.Row> rows = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.tickMetrics != null) rows.add(level.tickMetrics.row(level.getWorld().getName()));
        }
        return WorldTickMetrics.costliest(rows, limit);
    }
}
