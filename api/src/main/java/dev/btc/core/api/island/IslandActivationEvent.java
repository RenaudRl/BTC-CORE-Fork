package dev.btc.core.api.island;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired once when an owned island world has been loaded, claimed and validated.
 *
 * <p>Fired on the global context, after the platform has resolved ownership, taken the lease and
 * clamped the window — so by the time a listener sees it, the island is known good. Registered
 * {@link CatchUpHandler}s have already been invoked; this event exists for the systems that only
 * need to know an island woke up, not to run progression.
 *
 * <p>Not cancellable. Vetoing an activation after the lease was taken would leave the claim held
 * with no one to release it. A system that must decline work declines it in its handler, by
 * returning {@link CatchUpResult#rejected}.
 */
public class IslandActivationEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final IslandKey island;
    private final IslandLease lease;
    private final World world;
    private final long fromEpochMillis;
    private final long toEpochMillis;
    private final boolean clamped;

    public IslandActivationEvent(IslandKey island, IslandLease lease, World world,
                                 long fromEpochMillis, long toEpochMillis, boolean clamped) {
        this.island = Objects.requireNonNull(island, "island");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.world = Objects.requireNonNull(world, "world");
        this.fromEpochMillis = fromEpochMillis;
        this.toEpochMillis = toEpochMillis;
        this.clamped = clamped;
    }

    /**
     * @return the island that woke up
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
     * @return the loaded world, valid only while handling this event
     */
    public World getWorld() {
        return world;
    }

    /**
     * @return the island's last durably committed tick, in epoch milliseconds
     */
    public long getFromEpochMillis() {
        return fromEpochMillis;
    }

    /**
     * @return the server clock at claim time, in epoch milliseconds
     */
    public long getToEpochMillis() {
        return toEpochMillis;
    }

    /**
     * @return {@code true} when real absence exceeded the platform's ceiling and was clamped
     */
    public boolean isClamped() {
        return clamped;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
