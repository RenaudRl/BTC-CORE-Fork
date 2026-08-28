package dev.btc.core.performance;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTCCore: Batched Inventory Updates.
 *
 * <p>Coalesces {@link ClientboundContainerSetSlotPacket}s per {@code (containerId, slot)} for the
 * duration of a player tick. Only the newest update for a given slot survives, so a menu that
 * rewrites its whole layout several times within one tick — the normal rendering pattern for
 * inventory-GUI plugins — costs one packet per slot instead of one per rewrite.
 *
 * <p>This is where the win comes from. Simply deferring packets without merging them sends the
 * same number of packets, only later, and buys nothing.
 *
 * <h2>Ordering</h2>
 * A deferred slot update must never overtake a packet the client applies to the same container
 * state, or it lands on the wrong screen or against a stale state id — the classic "the item is
 * invisible until I click a second time" desync. Two rules keep that from happening:
 * <ul>
 *   <li>{@link #isOrderSensitive(Packet)} names those packets, and the NMS send hook drains the
 *       batch before letting one through;</li>
 *   <li>{@link #flush(ServerPlayer)} runs at the end of the player's own tick, on the player's
 *       region thread, so the batch never spans a tick boundary.</li>
 * </ul>
 *
 * <p>Wired via:
 * <ul>
 *   <li>{@code ServerCommonPacketListenerImpl#send(Packet)} — queue and order-sensitive drain;</li>
 *   <li>{@code ServerPlayer#doTick()} — end-of-tick flush;</li>
 *   <li>{@code BTCCoreListener#onPlayerQuit} — {@link #remove(UUID)}.</li>
 * </ul>
 */
public final class BatchedInventoryUpdates {

    /** Newest pending slot packet per player, keyed by {@code (containerId, slot)}. */
    private static final Map<UUID, Map<Long, ClientboundContainerSetSlotPacket>> pending = new ConcurrentHashMap<>();

    private BatchedInventoryUpdates() {}

    private static long slotKey(final ClientboundContainerSetSlotPacket packet) {
        return ((long) packet.getContainerId() << 32) | (packet.getSlot() & 0xFFFFFFFFL);
    }

    /**
     * Absorbs a slot packet into the player's batch, replacing any pending update for the same slot.
     *
     * @return {@code true} when the packet was absorbed; {@code false} means the caller still has to
     *         send it itself. Never re-sends the packet, so the caller cannot recurse back in.
     */
    public static boolean queue(final ServerPlayer player, final ClientboundContainerSetSlotPacket packet) {
        if (player == null) {
            return false;
        }
        pending.computeIfAbsent(player.getUUID(), key -> new ConcurrentHashMap<>())
            .put(slotKey(packet), packet);
        return true;
    }

    /**
     * Packets the client applies to the same container state as a slot update. A deferred slot
     * update arriving after one of these would be applied to the wrong screen or against a stale
     * state id, so the batch has to be drained before they go out.
     */
    public static boolean isOrderSensitive(final Packet<?> packet) {
        return packet instanceof ClientboundContainerSetContentPacket
            || packet instanceof ClientboundContainerSetDataPacket
            || packet instanceof ClientboundOpenScreenPacket
            || packet instanceof ClientboundContainerClosePacket
            || packet instanceof ClientboundSetPlayerInventoryPacket
            || packet instanceof ClientboundSetCursorItemPacket
            || packet instanceof ClientboundMerchantOffersPacket;
    }

    /**
     * Sends every pending slot update for this player. Call on the player's own region thread.
     */
    public static void flush(final ServerPlayer player) {
        if (player == null) {
            return;
        }
        final Map<Long, ClientboundContainerSetSlotPacket> slots = pending.remove(player.getUUID());
        if (slots == null) {
            return;
        }
        for (final ClientboundContainerSetSlotPacket packet : slots.values()) {
            // Two-argument send on purpose: the single-argument overload is the one BTCCore hooks,
            // and re-entering it here would queue the packet straight back into the batch.
            player.connection.send(packet, null);
        }
    }

    /** Drops a disconnected player's batch so the map does not retain it. */
    public static void remove(final UUID playerId) {
        pending.remove(playerId);
    }
}
