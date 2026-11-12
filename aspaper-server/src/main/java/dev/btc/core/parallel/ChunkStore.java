package dev.btc.core.parallel;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The chunks a Slime world holds in memory, by packed position, behind one monitor.
 *
 * <p>The thread that owns the world writes it (a chunk unloads, a chunk loads); a save, a clone or
 * a plugin reads it, and not always from that thread. Nobody is ever handed the live map: a reader
 * gets a {@link #snapshot}, which no later write reaches.
 *
 * <p>The monitor only keeps the table itself consistent. What a chunk holds is not protected here:
 * a live chunk is still to be read on the thread of its world, which is what the guard on
 * {@code SlimeLevelInstance.save()} and the copy taken by {@code SlimeInMemoryWorld.clone} are for.
 *
 * @param <C> the chunk type; {@code SlimeChunk} on the server, anything in a test
 */
public final class ChunkStore<C> {

    private final Long2ObjectOpenHashMap<C> chunks = new Long2ObjectOpenHashMap<>();

    public synchronized C get(long key) {
        return this.chunks.get(key);
    }

    public synchronized void put(long key, C chunk) {
        this.chunks.put(key, chunk);
    }

    public synchronized C remove(long key) {
        return this.chunks.remove(key);
    }

    /** Every chunk held right now, in a map of its own: never a view. */
    public synchronized Long2ObjectMap<C> snapshot() {
        return new Long2ObjectOpenHashMap<>(this.chunks);
    }
}
