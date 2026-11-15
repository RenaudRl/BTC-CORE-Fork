package dev.btc.core.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

/** Pure part of {@code BTCCoreAPI#resendChunkBiomes}: which chunks are resent, and the chunk key arithmetic. */
public final class BiomeResend {

    private BiomeResend() {
    }

    /**
     * The non-null results of {@code ifLoaded}, in the order of {@code chunkKeys}; a key given twice is looked
     * up once.
     */
    public static <T> List<T> loaded(Collection<Long> chunkKeys, LongFunction<T> ifLoaded) {
        Set<Long> seen = new HashSet<>();
        List<T> loaded = new ArrayList<>();
        for (Long key : chunkKeys) {
            if (!seen.add(key)) {
                continue;
            }
            T chunk = ifLoaded.apply(key);
            if (chunk != null) {
                loaded.add(chunk);
            }
        }
        return loaded;
    }

    /** Inverse of {@code org.bukkit.Chunk#getChunkKey(int, int)}: x is the low 32 bits. */
    public static int chunkX(long key) {
        return (int) key;
    }

    /** Inverse of {@code org.bukkit.Chunk#getChunkKey(int, int)}: z is the high 32 bits. */
    public static int chunkZ(long key) {
        return (int) (key >>> 32);
    }
}
