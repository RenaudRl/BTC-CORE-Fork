package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * BTCCore: NBT Compression Cache.
 * Caches compressed NBT byte arrays to avoid repeated GZIP compression
 * for identical NBT structures (e.g., entity data, chunk section data).
 *
 * Thread-safe: uses Collections.synchronizedMap with LRU eviction.
 * Wired via NMS hook in apply-btccore-patches.py: intercepts NbtIo.compress() calls.
 */
public class NbtCompressionCache {
    private static final int MAX_ENTRIES = 1000;

    private static final Map<String, byte[]> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_ENTRIES;
            }
        }
    );

    /**
     * Gets cached compressed data or computes and caches it.
     * The key should be a unique identifier for the NBT structure (e.g., Arrays.hashCode hash).
     */
    public static byte[] getOrCompute(String key, Supplier<byte[]> computer) {
        if (!BTCCoreConfig.nbtCompressionCache) return computer.get();

        synchronized (cache) {
            byte[] cached = cache.get(key);
            if (cached != null) return cached;

            byte[] computed = computer.get();
            cache.put(key, computed);
            return computed;
        }
    }

    /**
     * Gets cached compressed data or computes and caches it, using byte[] key.
     * The key is derived from Arrays.hashCode of the input data.
     */
    public static byte[] getOrCompute(byte[] nbtData, Supplier<byte[]> computer) {
        if (!BTCCoreConfig.nbtCompressionCache) return computer.get();

        String key = Integer.toHexString(Arrays.hashCode(nbtData));
        return getOrCompute(key, computer);
    }

    public static void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public static int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
