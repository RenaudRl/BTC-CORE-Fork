package com.infernalsuite.asp.api;

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
}

