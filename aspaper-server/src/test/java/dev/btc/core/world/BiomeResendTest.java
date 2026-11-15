package dev.btc.core.world;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code resendChunkBiomes}: which chunks are resent, and the chunk key arithmetic it relies on. */
class BiomeResendTest {

    /** Same formula as {@code org.bukkit.Chunk#getChunkKey(int, int)}. */
    private static long key(int x, int z) {
        return (long) x & 0xffffffffL | ((long) z & 0xffffffffL) << 32;
    }

    @Test
    @DisplayName("a loaded chunk is kept, an unloaded one (null) is dropped, the order is the order given")
    void keepsLoadedDropsUnloaded() {
        List<Long> keys = List.of(key(1, 1), key(2, 2), key(3, 3));
        List<String> kept = BiomeResend.loaded(keys, k -> k == key(2, 2) ? null : "chunk" + BiomeResend.chunkX(k));
        assertEquals(List.of("chunk1", "chunk3"), kept);
    }

    @Test
    @DisplayName("a key given twice is looked up and counted once")
    void duplicateCountedOnce() {
        List<Long> asked = new ArrayList<>();
        List<Long> kept = BiomeResend.loaded(List.of(key(5, 6), key(7, 8), key(5, 6)), k -> {
            asked.add(k);
            return k;
        });
        assertEquals(List.of(key(5, 6), key(7, 8)), kept);
        assertEquals(2, asked.size());
    }

    @Test
    @DisplayName("no key is no result, and nothing is looked up")
    void emptyIsEmpty() {
        assertTrue(BiomeResend.loaded(List.of(), k -> {
            throw new AssertionError("must not be called");
        }).isEmpty());
    }

    @Test
    @DisplayName("a key splits back into its chunk coordinates, negative ones included")
    void keyRoundTrip() {
        int[][] chunks = {{0, 0}, {1, -1}, {-1, 1}, {-30_000, 29_999}, {Integer.MIN_VALUE, Integer.MAX_VALUE}, {-7, -9}};
        for (int[] chunk : chunks) {
            long key = key(chunk[0], chunk[1]);
            assertEquals(chunk[0], BiomeResend.chunkX(key), "x of " + chunk[0] + "," + chunk[1]);
            assertEquals(chunk[1], BiomeResend.chunkZ(key), "z of " + chunk[0] + "," + chunk[1]);
        }
    }
}
