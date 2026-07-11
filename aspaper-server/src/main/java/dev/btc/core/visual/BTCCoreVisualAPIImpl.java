package dev.btc.core.visual;

import com.infernalsuite.asp.api.BTCCoreVisualAPI;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Display;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * BTCCore: Visual API implementation.
 * Provides packet-based display entities and virtual inventories.
 * Uses Folia's region scheduler for thread-safe packet dispatch.
 */
public class BTCCoreVisualAPIImpl extends BTCCoreVisualAPI {

    private static BTCCoreVisualAPIImpl instance;

    public static void init() {
        instance = new BTCCoreVisualAPIImpl();
        BTCCoreVisualAPI.setInstance(instance);
    }

    private org.bukkit.plugin.Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("ASPaper");
    }

    @Override
    public void sendAsyncVirtualInventory(Player target, int containerId, int stateId, ItemStack[] contents) {
        if (target == null || contents == null) return;

        org.bukkit.plugin.Plugin plugin = getPlugin();
        if (plugin == null) return;

        Bukkit.getRegionScheduler().run(plugin, target.getLocation(), task -> {
            CraftPlayer craftPlayer = (CraftPlayer) target;
            var connection = craftPlayer.getHandle().connection;

            connection.send(new ClientboundContainerSetContentPacket(
                containerId,
                stateId,
                java.util.Arrays.stream(contents)
                    .map(item -> item == null ? net.minecraft.world.item.ItemStack.EMPTY : CraftItemStack.asNMSCopy(item))
                    .collect(java.util.stream.Collectors.toList()),
                net.minecraft.world.item.ItemStack.EMPTY
            ));
        });
    }

    @Override
    public void spawnAsyncDisplayEntity(Player target, int entityId, UUID uniqueId, Location location, String displayType, Transformation scale) {
        if (target == null || location == null) return;

        org.bukkit.plugin.Plugin plugin = getPlugin();
        if (plugin == null) return;

        Bukkit.getRegionScheduler().run(plugin, location, task -> {
            CraftPlayer craftPlayer = (CraftPlayer) target;
            var connection = craftPlayer.getHandle().connection;

            net.minecraft.world.entity.Display displayEntity;
            switch (displayType.toLowerCase()) {
                case "item" -> {
                    Display.ItemDisplay itemDisplay = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle());
                    itemDisplay.setItemStack(CraftItemStack.asNMSCopy(new ItemStack(org.bukkit.Material.STONE)));
                    displayEntity = itemDisplay;
                }
                case "block" -> {
                    Display.BlockDisplay blockDisplay = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle());
                    displayEntity = blockDisplay;
                }
                case "text" -> {
                    Display.TextDisplay textDisplay = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle());
                    textDisplay.setBillboardConstraints(Display.BillboardConstraints.CENTER);
                    displayEntity = textDisplay;
                }
                default -> {
                    Display.ItemDisplay fallback = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle());
                    displayEntity = fallback;
                }
            }

            displayEntity.setId(entityId);
            displayEntity.setUUID(uniqueId != null ? uniqueId : UUID.randomUUID());
            displayEntity.setPos(location.getX(), location.getY(), location.getZ());
            displayEntity.setYRot(location.getYaw());
            displayEntity.setXRot(location.getPitch());

            if (scale != null) {
                displayEntity.setTransformation(new com.mojang.math.Transformation(
                    scale.getTranslation(),
                    scale.getLeftRotation(),
                    scale.getScale(),
                    scale.getRightRotation()
                ));
            }

            connection.send(new ClientboundAddEntityPacket(
                displayEntity.getId(), displayEntity.getUUID(),
                displayEntity.getX(), displayEntity.getY(), displayEntity.getZ(),
                displayEntity.getXRot(), displayEntity.getYRot(),
                displayEntity.getType(), 0,
                displayEntity.getDeltaMovement(), displayEntity.getYHeadRot()));
            connection.send(new ClientboundSetEntityDataPacket(entityId, displayEntity.getEntityData().getNonDefaultValues()));
        });
    }

    @Override
    public void destroyAsyncDisplayEntity(Player target, int... entityIds) {
        if (target == null || entityIds == null || entityIds.length == 0) return;

        org.bukkit.plugin.Plugin plugin = getPlugin();
        if (plugin == null) return;

        Bukkit.getRegionScheduler().run(plugin, target.getLocation(), task -> {
            CraftPlayer craftPlayer = (CraftPlayer) target;
            var connection = craftPlayer.getHandle().connection;
            connection.send(new ClientboundRemoveEntitiesPacket(entityIds));
        });
    }

    @Override
    public void updateAsyncDisplayEntity(Player target, int entityId, Location newLocation, Transformation newScale) {
        if (target == null || newLocation == null) return;

        org.bukkit.plugin.Plugin plugin = getPlugin();
        if (plugin == null) return;

        Bukkit.getRegionScheduler().run(plugin, newLocation, task -> {
            CraftPlayer craftPlayer = (CraftPlayer) target;
            var connection = craftPlayer.getHandle().connection;
            var level = ((org.bukkit.craftbukkit.CraftWorld) newLocation.getWorld()).getHandle();

            // Create a temporary display entity to build packets from its NMS state.
            // The entity is never added to the world — we only use it to serialize
            // position, rotation, and transformation data into client-bound packets.
            Display.ItemDisplay tempEntity = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            tempEntity.setId(entityId);
            tempEntity.setPos(newLocation.getX(), newLocation.getY(), newLocation.getZ());
            tempEntity.setYRot(newLocation.getYaw());
            tempEntity.setXRot(newLocation.getPitch());

            // Position + rotation update via teleport packet
            connection.send(ClientboundTeleportEntityPacket.teleport(tempEntity.getId(), net.minecraft.world.entity.PositionMoveRotation.of(tempEntity), java.util.Set.of(), tempEntity.onGround()));

            // Transformation update via metadata packet (only if a new scale is provided)
            if (newScale != null) {
                tempEntity.setTransformation(new com.mojang.math.Transformation(
                    newScale.getTranslation(),
                    newScale.getLeftRotation(),
                    newScale.getScale(),
                    newScale.getRightRotation()
                ));
                connection.send(new ClientboundSetEntityDataPacket(entityId, tempEntity.getEntityData().getNonDefaultValues()));
            }
        });
    }
}
