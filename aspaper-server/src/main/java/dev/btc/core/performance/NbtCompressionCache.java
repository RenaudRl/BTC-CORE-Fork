package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import java.util.LinkedHashMap;
import java.util.Map;

public class NbtCompressionCache {
    private static final int MAX_ENTRIES = 1000;
    private static final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public static byte[] getOrCompute(String key, java.util.function.Supplier<byte[]> computer) {
        if (!BTCCoreConfig.nbtCompressionCache) return computer.get();
        return cache.computeIfAbsent(key, k -> computer.get());
    }

    public static void clear() {
        cache.clear();
    }

    public static int size() {
        return cache.size();
    }
}
