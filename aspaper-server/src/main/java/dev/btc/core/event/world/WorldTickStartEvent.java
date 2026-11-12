package dev.btc.core.event.world;

import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.event.world.WorldEvent;

/**
 * Fired right before a world ticks, on the thread that ticks it: the main thread, or the world's own
 * thread under {@code parallel-world-ticking}.
 *
 * <p>Under {@code parallel-world-ticking} a listener runs for several worlds at the same time: it
 * must only touch the world it is given, and any state it shares between worlds must be
 * thread-safe.
 *
 * <p>Idea from SparklyPaper (SparklyPower); rewritten.
 */
public class WorldTickStartEvent extends WorldEvent {

    private static final HandlerList handlers = new HandlerList();

    private final int tick;

    public WorldTickStartEvent(World world, int tick) {
        super(world);
        this.tick = tick;
    }

    /**
     * @return the server tick this world tick belongs to
     */
    public int getTick() {
        return tick;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
