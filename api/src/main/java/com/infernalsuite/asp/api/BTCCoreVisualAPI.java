package com.infernalsuite.asp.api;

import com.infernalsuite.asp.api.visual.VirtualDisplayHandle;
import com.infernalsuite.asp.api.visual.VirtualDisplaySpec;
import com.infernalsuite.asp.api.visual.VirtualDisplayUpdate;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.UUID;

/**
 * Advanced Asynchronous Visual Hooks for BTCCore.
 * Highly optimized APIs allowing extensions (BetterModel, AdvancedMenu) to 
 * bypass the Main Thread when instantiating fake entities and virtual inventories.
 */
public abstract class BTCCoreVisualAPI {
    private static BTCCoreVisualAPI instance;

    public static BTCCoreVisualAPI getInstance() {
        return instance;
    }

    public static void setInstance(BTCCoreVisualAPI impl) {
        if (instance != null) throw new IllegalStateException("BTCCoreVisualAPI already initialized!");
        instance = impl;
    }

    /**
     * Forges and dispatches a full inventory display to the client asynchronously.
     * Bypasses the NMS container loop checks and runs entirely off the Main Thread.
     * 
     * @param target The viewing player.
     * @param containerId The current network container ID.
     * @param stateId The window state ID (helps sync clicks, pass 0 if forcing UI).
     * @param contents The array of items mapped to the inventory.
     */
    public abstract void sendAsyncVirtualInventory(Player target, int containerId, int stateId, ItemStack[] contents);

    /**
     * Forges and dispatches a fake DisplayEntity purely as network packets.
     * Since it is entirely decoupled from the chunk or level logic, it costs 0 MSPT.
     *
     * @param target The viewing player.
     * @param entityId The arbitrary virtual Entity ID to use.
     * @param uniqueId The arbitrary virtual UUID.
     * @param location The exact coordinates and rotation.
     * @param displayType Usually "item", "block", or "text".
     * @param scale The geometric transform.
     */
    public abstract void spawnAsyncDisplayEntity(Player target, int entityId, UUID uniqueId, Location location, String displayType, Transformation scale);

    /**
     * Sends a raw packet manually to destroy the visual entity gracefully.
     * 
     * @param target The viewing player.
     * @param entityIds The IDs representing the fake entities.
     */
    public abstract void destroyAsyncDisplayEntity(Player target, int... entityIds);

    /**
     * Updates the position and/or transformation of a previously spawned async display entity.
     * Sends teleport + metadata packets without touching server-side entity tracking.
     *
     * @param target       The viewing player.
     * @param entityId     The virtual entity ID (must match the one passed to spawnAsyncDisplayEntity).
     * @param newLocation  The new absolute position and rotation.
     * @param newScale     The new geometric transform, or {@code null} to keep the current transformation.
     */
    public abstract void updateAsyncDisplayEntity(Player target, int entityId, Location newLocation, Transformation newScale);

    /**
     * Creates a typed packet-only display for one viewer.
     *
     * <p>The handle is allocated synchronously while packet delivery is safely
     * dispatched through the viewer's entity scheduler. The display never joins
     * a world entity tracker and therefore has no server tick cost.
     *
     * @param target viewer that receives the display
     * @param spec complete initial display state
     * @return handle used for updates and destruction
     * @throws IllegalArgumentException if either argument is null
     * @since 26.2
     */
    public abstract VirtualDisplayHandle spawnDisplay(Player target, VirtualDisplaySpec spec);

    /**
     * Applies a sparse update to a typed packet-only display.
     *
     * @param handle display handle
     * @param update changed properties
     * @since 26.2
     */
    public abstract void updateDisplay(VirtualDisplayHandle handle, VirtualDisplayUpdate update);

    /**
     * Removes a typed packet-only display from its viewer.
     *
     * @param handle display handle
     * @since 26.2
     */
    public abstract void destroyDisplay(VirtualDisplayHandle handle);
}

