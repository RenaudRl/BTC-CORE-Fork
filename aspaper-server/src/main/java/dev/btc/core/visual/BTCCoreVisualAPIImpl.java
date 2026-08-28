package dev.btc.core.visual;

import com.infernalsuite.asp.api.BTCCoreVisualAPI;
import com.infernalsuite.asp.api.visual.VirtualDisplayHandle;
import com.infernalsuite.asp.api.visual.VirtualDisplaySpec;
import com.infernalsuite.asp.api.visual.VirtualDisplayType;
import com.infernalsuite.asp.api.visual.VirtualDisplayUpdate;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Display;
import net.minecraft.util.Brightness;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: Visual API implementation.
 * Provides packet-based display entities and virtual inventories.
 * Uses Folia's region scheduler for thread-safe packet dispatch.
 */
public class BTCCoreVisualAPIImpl extends BTCCoreVisualAPI {

    private static BTCCoreVisualAPIImpl instance;
    private static final AtomicInteger NEXT_VIRTUAL_ENTITY_ID = new AtomicInteger(2_000_000_000);

    public static void init() {
        instance = new BTCCoreVisualAPIImpl();
        BTCCoreVisualAPI.setInstance(instance);
    }

    private org.bukkit.plugin.Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("BTCCore");
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

    @Override
    public VirtualDisplayHandle spawnDisplay(Player target, VirtualDisplaySpec spec) {
        if (target == null || spec == null) {
            throw new IllegalArgumentException("target and spec must not be null");
        }

        var handle = new VirtualDisplayHandle(
            spec.type(),
            NEXT_VIRTUAL_ENTITY_ID.getAndDecrement(),
            UUID.randomUUID(),
            target.getUniqueId()
        );
        var plugin = getPlugin();
        if (plugin == null) {
            throw new IllegalStateException("BTCCore is not available");
        }

        target.getScheduler().run(plugin, task -> {
            if (!target.isOnline()) {
                return;
            }
            var display = createDisplay(spec.type(), spec.location());
            applyInitialState(display, handle, spec);
            var connection = ((CraftPlayer) target).getHandle().connection;
            connection.send(new ClientboundAddEntityPacket(
                display.getId(),
                display.getUUID(),
                display.getX(),
                display.getY(),
                display.getZ(),
                display.getXRot(),
                display.getYRot(),
                display.getType(),
                0,
                display.getDeltaMovement(),
                display.getYHeadRot()
            ));
            connection.send(new ClientboundSetEntityDataPacket(
                display.getId(),
                display.getEntityData().getNonDefaultValues()
            ));
        }, null);

        if (spec.lifetimeTicks() > 0) {
            target.getScheduler().runDelayed(
                plugin,
                task -> destroyDisplay(handle),
                null,
                spec.lifetimeTicks()
            );
        }
        return handle;
    }

    @Override
    public void updateDisplay(VirtualDisplayHandle handle, VirtualDisplayUpdate update) {
        if (handle == null || update == null) {
            throw new IllegalArgumentException("handle and update must not be null");
        }
        var target = Bukkit.getPlayer(handle.viewerUuid());
        var plugin = getPlugin();
        if (target == null || plugin == null) {
            return;
        }

        target.getScheduler().run(plugin, task -> {
            if (!target.isOnline()) {
                return;
            }
            var baseLocation = update.location() != null
                ? update.location()
                : target.getLocation();
            var display = createDisplay(handle.type(), baseLocation);
            display.setId(handle.entityId());
            display.setUUID(handle.entityUuid());
            var connection = ((CraftPlayer) target).getHandle().connection;

            if (update.location() != null) {
                setPosition(display, update.location());
                connection.send(ClientboundTeleportEntityPacket.teleport(
                    display.getId(),
                    net.minecraft.world.entity.PositionMoveRotation.of(display),
                    java.util.Set.of(),
                    display.onGround()
                ));
            }

            applyUpdate(display, update);
            var dirty = display.getEntityData().packDirty();
            if (dirty != null && !dirty.isEmpty()) {
                connection.send(new ClientboundSetEntityDataPacket(handle.entityId(), dirty));
            }
        }, null);
    }

    @Override
    public void destroyDisplay(VirtualDisplayHandle handle) {
        if (handle == null) {
            return;
        }
        var target = Bukkit.getPlayer(handle.viewerUuid());
        var plugin = getPlugin();
        if (target == null || plugin == null) {
            return;
        }
        target.getScheduler().run(plugin, task -> {
            if (target.isOnline()) {
                ((CraftPlayer) target).getHandle().connection.send(
                    new ClientboundRemoveEntitiesPacket(handle.entityId())
                );
            }
        }, null);
    }

    @Override
    public void mountPassengers(Player target, int vehicleEntityId, int... passengerEntityIds) {
        if (target == null) {
            return;
        }
        var plugin = getPlugin();
        if (plugin == null) {
            return;
        }
        int[] passengers = passengerEntityIds == null ? new int[0] : passengerEntityIds.clone();
        target.getScheduler().run(plugin, task -> {
            if (target.isOnline()) {
                ((CraftPlayer) target).getHandle().connection.send(
                    new ClientboundSetPassengersPacket(vehicleEntityId, passengers)
                );
            }
        }, null);
    }

    @Override
    public void unmountPassengers(Player target, int vehicleEntityId) {
        if (target == null) {
            return;
        }
        var plugin = getPlugin();
        if (plugin == null) {
            return;
        }
        target.getScheduler().run(plugin, task -> {
            if (target.isOnline()) {
                ((CraftPlayer) target).getHandle().connection.send(
                    new ClientboundSetPassengersPacket(vehicleEntityId, new int[0])
                );
            }
        }, null);
    }

    private Display createDisplay(VirtualDisplayType type, Location location) {
        var level = ((org.bukkit.craftbukkit.CraftWorld) location.getWorld()).getHandle();
        return switch (type) {
            case TEXT -> new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
            case ITEM -> new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level);
            case BLOCK -> new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
        };
    }

    private void applyInitialState(
        Display display,
        VirtualDisplayHandle handle,
        VirtualDisplaySpec spec
    ) {
        display.setId(handle.entityId());
        display.setUUID(handle.entityUuid());
        setPosition(display, spec.location());
        display.setTransformation(new com.mojang.math.Transformation(
            spec.transformation().getTranslation(),
            spec.transformation().getLeftRotation(),
            spec.transformation().getScale(),
            spec.transformation().getRightRotation()
        ));
        display.setBillboardConstraints(toNmsBillboard(spec.billboard()));
        display.setTransformationInterpolationDuration(spec.interpolationDuration());
        // Display interpolation has two independent channels in vanilla:
        // transformation interpolation and position/rotation interpolation.
        // The public visual API exposes one duration, so apply it to both
        // channels. Without this metadata every packet teleport is rendered
        // as a one-tick snap, which is especially visible on moving mobs.
        display.getEntityData().set(
            Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID,
            Math.max(0, Math.min(59, spec.interpolationDuration()))
        );
        display.setViewRange(spec.viewRange());
        display.setShadowRadius(spec.shadowRadius());
        display.setShadowStrength(spec.shadowStrength());
        if (spec.brightness() != null) {
            display.setBrightnessOverride(new Brightness(
                spec.brightness().getBlockLight(),
                spec.brightness().getSkyLight()
            ));
        }

        switch (spec.type()) {
            case TEXT -> {
                var textDisplay = (Display.TextDisplay) display;
                textDisplay.setText(PaperAdventure.asVanilla(
                    spec.text() == null ? net.kyori.adventure.text.Component.empty() : spec.text()
                ));
                textDisplay.setTextOpacity((byte) spec.textOpacity());
                textDisplay.getEntityData().set(
                    Display.TextDisplay.DATA_LINE_WIDTH_ID,
                    spec.lineWidth()
                );
                textDisplay.getEntityData().set(
                    Display.TextDisplay.DATA_BACKGROUND_COLOR_ID,
                    spec.backgroundColor()
                );
            }
            case ITEM -> {
                var itemDisplay = (Display.ItemDisplay) display;
                itemDisplay.setItemStack(
                    spec.item() == null
                        ? net.minecraft.world.item.ItemStack.EMPTY
                        : CraftItemStack.asNMSCopy(spec.item())
                );
            }
            case BLOCK -> {
                if (spec.block() != null) {
                    ((Display.BlockDisplay) display).setBlockState(
                        ((CraftBlockData) spec.block()).getState()
                    );
                }
            }
        }
    }

    private void applyUpdate(Display display, VirtualDisplayUpdate update) {
        if (update.transformation() != null) {
            display.setTransformation(new com.mojang.math.Transformation(
                update.transformation().getTranslation(),
                update.transformation().getLeftRotation(),
                update.transformation().getScale(),
                update.transformation().getRightRotation()
            ));
        }
        if (update.interpolationDuration() != null) {
            int duration = Math.max(0, Math.min(59, update.interpolationDuration()));
            display.setTransformationInterpolationDuration(duration);
            display.getEntityData().set(
                Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID,
                duration
            );
        }
        if (update.brightness() != null) {
            display.setBrightnessOverride(new Brightness(
                update.brightness().getBlockLight(),
                update.brightness().getSkyLight()
            ));
        }
        if (display instanceof Display.TextDisplay textDisplay) {
            if (update.text() != null) {
                textDisplay.setText(PaperAdventure.asVanilla(update.text()));
            }
            if (update.textOpacity() != null) {
                textDisplay.setTextOpacity(update.textOpacity().byteValue());
            }
        }
        if (display instanceof Display.ItemDisplay itemDisplay && update.item() != null) {
            itemDisplay.setItemStack(CraftItemStack.asNMSCopy(update.item()));
        }
    }

    private void setPosition(Display display, Location location) {
        display.setPos(location.getX(), location.getY(), location.getZ());
        display.setYRot(location.getYaw());
        display.setXRot(location.getPitch());
    }

    private Display.BillboardConstraints toNmsBillboard(org.bukkit.entity.Display.Billboard billboard) {
        return switch (billboard) {
            case FIXED -> Display.BillboardConstraints.FIXED;
            case VERTICAL -> Display.BillboardConstraints.VERTICAL;
            case HORIZONTAL -> Display.BillboardConstraints.HORIZONTAL;
            case CENTER -> Display.BillboardConstraints.CENTER;
        };
    }
}
