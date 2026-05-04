package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;

public class ChunkPrefetch {
    public static void prefetch(ServerPlayer player, Location destination) {
        if (!BTCCoreConfig.chunkPrefetchEnabled || player == null || destination == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        int chunkX = destination.getBlockX() >> 4;
        int chunkZ = destination.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.getChunkSource().getChunk(chunkX + dx, chunkZ + dz, true);
            }
        }
    }
}
