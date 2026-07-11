package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;

/**
 * BTCCore: Chunk Prefetch on Teleport.
 * Pre-loads chunks at the destination before the player arrives,
 * reducing teleport lag and visual pop-in.
 *
 * Uses the chunk system's async scheduling to queue chunk loads without blocking.
 * Wired via Bukkit event: PlayerTeleportEvent in BTCCoreListener.
 */
public class ChunkPrefetch {

    /**
     * Pre-loads a 5x5 chunk grid around the destination location.
     * Queues chunk loads asynchronously via the Moonrise chunk system.
     */
    public static void prefetch(ServerPlayer player, Location destination) {
        if (!BTCCoreConfig.chunkPrefetchEnabled || player == null || destination == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        int chunkX = destination.getBlockX() >> 4;
        int chunkZ = destination.getBlockZ() >> 4;

        // Queue async chunk loads for the 5x5 grid around destination.
        // getChunk(x, z, FULL, false) returns cached chunk or null (non-blocking),
        // which triggers a background load request in Moonrise.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = chunkX + dx;
                int z = chunkZ + dz;
                // Check if already loaded
                if (level.getChunkIfLoaded(x, z) != null) continue;
                // Schedule a chunk load — non-blocking, will be ready by the time teleport completes
                level.getChunkSource().getChunkFuture(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            }
        }
    }

    /**
     * Prefetch for a Bukkit player (used from event hooks).
     */
    public static void prefetch(org.bukkit.entity.Player player, Location destination) {
        if (!BTCCoreConfig.chunkPrefetchEnabled || player == null || destination == null) return;
        if (!(player instanceof org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer)) return;
        prefetch(craftPlayer.getHandle(), destination);
    }
}
