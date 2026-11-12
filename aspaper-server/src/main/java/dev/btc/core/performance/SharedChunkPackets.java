package dev.btc.core.performance;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.LongFunction;

/**
 * The chunk packets one world built while it was sending chunks to its players, so that players
 * sent the same chunk in the same pass share one packet object
 * ({@code performance.network.shared-chunk-packets}).
 *
 * <p>The window is the pass in which a world sends chunks to each of its players in turn
 * ({@code RegionizedPlayerChunkLoader#tick}). Plugins do run inside it, between two players: the
 * listeners of {@code PlayerChunkLoadEvent} and {@code PlayerChunkUnloadEvent}, and those of
 * whatever a ticket update fires (a chunk that loads). So a chunk that changes inside the window
 * is forgotten ({@link #forget}, from {@code LevelChunk#markUnsaved}) and the next player is sent
 * a packet built after the change, as without the option. A change that does not mark its chunk
 * as to be saved is not seen: such a write is not saved either. Nothing is kept past the window.
 * Outside the window, and on any thread but the one that opened it, every packet is built for its
 * player, as without the option.
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
        if (!this.isOpen()) return build.apply(chunk);
        P packet = this.built.get(chunk);
        if (packet == null) {
            // Built outside the map: what builds a packet may change a chunk, and forget it
            packet = build.apply(chunk);
            this.built.put(chunk, packet);
        }
        return packet;
    }

    /** {@code chunk} changed: its packet of this window is dropped. Nothing on a thread that did not open the window. */
    public void forget(long chunk) {
        if (this.isOpen()) this.built.remove(chunk);
    }
}
