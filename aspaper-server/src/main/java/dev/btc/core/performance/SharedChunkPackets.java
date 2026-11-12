package dev.btc.core.performance;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.LongFunction;

/**
 * The chunk packets one world built while it was sending chunks to its players, so that players
 * sent the same chunk in the same pass share one packet object
 * ({@code performance.network.shared-chunk-packets}).
 *
 * <p>The window is the pass in which a world sends chunks to each of its players in turn
 * ({@code RegionizedPlayerChunkLoader#tick}): no game logic runs between two players, and a block
 * changed by a listener in between is broadcast after the pass to every player the chunk was sent
 * to. Nothing is kept past the window, so there is no invalidation to get wrong. Outside the
 * window, and on any thread but the one that opened it, every packet is built for its player, as
 * without the option.
 *
 * <p>What is shared is the packet object, which nobody writes once built; each connection encodes
 * it into its own buffer. Idea: SteelMC's cache emptied each tick; nothing is ported.
 *
 * @param <P> the packet
 */
public final class SharedChunkPackets<P> {

    private final Long2ObjectOpenHashMap<P> built = new Long2ObjectOpenHashMap<>();
    // Plain field: a thread that reads a stale value reads null or another thread, never itself
    private Thread window;

    /** Opens the window for the calling thread; what an earlier window left behind is dropped. */
    public void open() {
        this.built.clear();
        this.window = Thread.currentThread();
    }

    public void close() {
        this.window = null;
        this.built.clear();
    }

    /** Whether the calling thread may share: it opened the window and has not closed it. */
    public boolean isOpen() {
        return this.window == Thread.currentThread();
    }

    /** The packet of {@code chunk} built in this window, built now if it is the first time; always a new one outside the window. */
    public P share(long chunk, LongFunction<? extends P> build) {
        return this.isOpen() ? this.built.computeIfAbsent(chunk, build) : build.apply(chunk);
    }
}
