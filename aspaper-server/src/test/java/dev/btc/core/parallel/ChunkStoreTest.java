package dev.btc.core.parallel;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk table of a Slime world read by a thread that does not own it: test T17 of the port map
 * ({@code conception-slime-sous-pwt.md}, 1.5).
 */
class ChunkStoreTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int CHUNKS = 4096;
    private static final int SNAPSHOTS = 2000;

    @Test
    @DisplayName("T17 - a snapshot taken while the owner unloads and reloads chunks is whole: every chunk but the one in flight, each under its own key")
    void chunk_store_snapshot_is_consistent_under_concurrent_unload() throws Exception {
        ChunkStore<long[]> store = new ChunkStore<>();
        for (long key = 0; key < CHUNKS; key++) store.put(key, new long[] {key});
        AtomicBoolean stop = new AtomicBoolean();
        Thread owner = new Thread(() -> { // unload then promote, on the same key, as the thread of the world does
            for (long key = 0; !stop.get(); key = (key + 1) & (CHUNKS - 1)) {
                store.remove(key);
                store.put(key, new long[] {key});
            }
        }, "test chunk owner");
        owner.setDaemon(true);
        owner.start();
        try {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                for (int i = 0; i < SNAPSHOTS; i++) { // the foreign thread that saves or clones
                    Long2ObjectMap<long[]> snapshot = store.snapshot();
                    assertTrue(snapshot.size() == CHUNKS - 1 || snapshot.size() == CHUNKS, "size " + snapshot.size());
                    for (Long2ObjectMap.Entry<long[]> entry : snapshot.long2ObjectEntrySet()) {
                        assertEquals(entry.getLongKey(), entry.getValue()[0]);
                    }
                }
            });
        } finally {
            stop.set(true);
            owner.join();
        }
    }

    @Test
    @DisplayName("a snapshot is a map of its own: what is written or removed afterwards never reaches it, and writing it changes nothing")
    void snapshot_is_detached_from_later_writes() {
        ChunkStore<String> store = new ChunkStore<>();
        store.put(1L, "one");
        store.put(2L, "two");

        Long2ObjectMap<String> snapshot = store.snapshot();
        store.put(3L, "three");
        store.remove(1L);
        store.put(2L, "other");

        assertEquals(2, snapshot.size());
        assertEquals("one", snapshot.get(1L));
        assertEquals("two", snapshot.get(2L));
        assertFalse(snapshot.containsKey(3L));

        snapshot.remove(2L);
        snapshot.put(4L, "four");
        assertEquals("other", store.get(2L));
        assertNull(store.get(4L));
    }

    @Test
    @DisplayName("put replaces, remove hands back what was held, get answers null for a chunk never held")
    void get_put_remove_are_those_of_the_map_it_replaces() {
        ChunkStore<String> store = new ChunkStore<>();
        String first = "first";
        store.put(7L, first);
        store.put(7L, "second");

        assertEquals("second", store.get(7L));
        assertEquals("second", store.remove(7L));
        assertNull(store.get(7L));
        assertNull(store.remove(7L));
        store.put(7L, first);
        assertSame(first, store.get(7L));
    }
}
