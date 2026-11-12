package dev.btc.core.api.zone;

import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.UUID;

/**
 * A box of blocks in one world, registered with
 * {@link dev.btc.core.api.BTCCoreAPI#registerZone}: its {@link ZoneListener} is called when a
 * player enters it or leaves it.
 *
 * <p>The handle may be kept and used from any thread. It holds no reference to the world, so a
 * zone kept after its world unloaded keeps nothing in memory.
 *
 * @since 26.3.build.3-alpha
 */
public interface Zone {

    /** The plugin that registered the zone. */
    Plugin getOwner();

    /**
     * The UUID of the world, as it was when the zone was registered. A Slime world gets a new UUID
     * at every load: a zone never follows a world into its next load, register it again.
     */
    UUID getWorldId();

    /**
     * The blocks of the zone, as a new box from the lowest corner of its first block to the highest
     * corner of its last one: {@code BoundingBox.of(block1, block2)} for a zone registered from two
     * corner blocks.
     */
    BoundingBox getBounds();

    /**
     * Whether the zone is still registered: {@code false} once it was unregistered, its owner
     * disabled or its world unloaded.
     */
    boolean isRegistered();

    /**
     * Removes the zone. Players inside it are dropped from it without a call to
     * {@link ZoneListener#onLeave}. Does nothing when the zone is no longer registered.
     */
    void unregister();
}
