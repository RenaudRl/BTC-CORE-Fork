package dev.btc.core.api.island;

import org.bukkit.Chunk;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired when a chunk of an owned island reaches the resume state after having been unloaded.
 *
 * <p>Fired on the region thread that owns the chunk, and only for a chunk that is both loaded and
 * inside the island's owned perimeter — a listener never has to test either. It is not fired for
 * the initial load of a world, which is covered once by {@link IslandActivationEvent}.
 *
 * <p>This is a resume notification, not a ticket. Receiving it does not keep the chunk loaded, and
 * nothing here lets a plugin — still less a player — pin a chunk in memory.
 */
public class ChunkResumeEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final IslandKey island;
    private final IslandLease lease;
    private final Chunk chunk;

    public ChunkResumeEvent(IslandKey island, IslandLease lease, Chunk chunk) {
        this.island = Objects.requireNonNull(island, "island");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.chunk = Objects.requireNonNull(chunk, "chunk");
    }

    /**
     * @return the island the chunk belongs to
     */
    public IslandKey getIsland() {
        return island;
    }

    /**
     * @return the lease this backend holds over the island
     */
    public IslandLease getLease() {
        return lease;
    }

    /**
     * @return the resumed chunk, valid only while handling this event
     */
    public Chunk getChunk() {
        return chunk;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
