package dev.btc.core.event.world;

import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.event.world.WorldEvent;

/**
 * Fired right after a world ticked, on the thread that ticked it; not fired when the tick threw.
 * The threading rules of {@link WorldTickStartEvent} apply.
 *
 * <p>Idea from SparklyPaper (SparklyPower); rewritten.
 */
public class WorldTickEndEvent extends WorldEvent {

    private static final HandlerList handlers = new HandlerList();

    private final int tick;

    public WorldTickEndEvent(World world, int tick) {
        super(world);
        this.tick = tick;
    }

    /**
     * @return the server tick this world tick belonged to
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
