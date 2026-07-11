package dev.btc.core.world;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk block value cache for island level calculation.
 * Caches the sum of block values per chunk to avoid re-scanning.
 * Used by BTC Sky for fast island level computation.
 *
 * Integration: BTC Sky calls getChunkValue() / updateChunkValue()
 * instead of scanning all blocks on every level check.
 */
public class BlockValueCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("BTCCore BlockValueCache");
    private static final ConcurrentHashMap<String, Long2DoubleOpenHashMap> WORLD_CACHES = new ConcurrentHashMap<>();

    // Point values per block (configurable)
    public static double DIRT_VALUE = 0.1;
    public static double COBBLESTONE_VALUE = 0.2;
    public static double STONE_VALUE = 0.3;
    public static double ORE_VALUE = 1.0;
    public static double DIAMOND_VALUE = 5.0;
    public static double NETHERITE_VALUE = 50.0;

    /**
     * Get the cached total block value for a chunk.
     * Returns -1 if not cached (caller should compute and cache).
     */
    public static double getChunkValue(Level level, int chunkX, int chunkZ) {
        Long2DoubleOpenHashMap cache = getCache(level);
        long key = packChunkKey(chunkX, chunkZ);
        return cache.containsKey(key) ? cache.get(key) : -1.0;
    }

    /**
     * Update the cached value for a chunk.
     */
    public static void setChunkValue(Level level, int chunkX, int chunkZ, double value) {
        Long2DoubleOpenHashMap cache = getCache(level);
        cache.put(packChunkKey(chunkX, chunkZ), value);
    }

    /**
     * Add delta to a chunk's cached value (for block place/break).
     */
    public static void addToChunkValue(Level level, int chunkX, int chunkZ, double delta) {
        Long2DoubleOpenHashMap cache = getCache(level);
        long key = packChunkKey(chunkX, chunkZ);
        cache.put(key, cache.getOrDefault(key, 0.0) + delta);
    }

    /**
     * Invalidate cache for a specific chunk.
     */
    public static void invalidateChunk(Level level, int chunkX, int chunkZ) {
        Long2DoubleOpenHashMap cache = getCache(level);
        cache.remove(packChunkKey(chunkX, chunkZ));
    }

    /**
     * Clear all cached values for a world.
     */
    public static void clearWorld(Level level) {
        WORLD_CACHES.remove(level.getWorld().getName());
    }

    /**
     * Get the point value of a block type using registry key matching.
     */
    public static double getBlockValue(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        String registryName = state.getBlock().builtInRegistryHolder().key().identifier().getPath();

        return switch (registryName) {
            case "dirt", "grass_block", "coarse_dirt", "podzol", "mycelium", "rooted_dirt" -> DIRT_VALUE;
            case "cobblestone", "mossy_cobblestone", "infested_cobblestone" -> COBBLESTONE_VALUE;
            case "stone", "granite", "diorite", "andesite", "deepslate", "tuff" -> STONE_VALUE;
            case "iron_ore", "copper_ore", "coal_ore", "deepslate_iron_ore", "deepslate_coal_ore", "deepslate_copper_ore" -> ORE_VALUE;
            case "diamond_ore", "emerald_ore", "deepslate_diamond_ore", "deepslate_emerald_ore" -> DIAMOND_VALUE;
            case "netherite_block", "ancient_debris" -> NETHERITE_VALUE;
            default -> 0.0;
        };
    }

    /**
     * Scan an entire chunk and cache its block values.
     * Uses chunk-local block state access for efficiency.
     */
    public static double scanAndCacheChunk(LevelChunk chunk) {
        double total = 0.0;
        Level level = chunk.getLevel();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    mutablePos.set(
                        chunk.getPos().getMinBlockX() + x,
                        y,
                        chunk.getPos().getMinBlockZ() + z
                    );
                    total += getBlockValue(level, mutablePos);
                }
            }
        }

        setChunkValue(level, chunk.getPos().x(), chunk.getPos().z(), total);
        return total;
    }

    private static Long2DoubleOpenHashMap getCache(Level level) {
        return WORLD_CACHES.computeIfAbsent(level.getWorld().getName(), k -> new Long2DoubleOpenHashMap());
    }

    private static long packChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static void init() {
        LOGGER.info("BlockValueCache initialized");
    }
}
