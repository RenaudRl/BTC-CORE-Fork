package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * BTCCore: Batched Inventory Updates.
 * Queues ClientboundContainerSetSlotPackets per player and flushes them
 * at the end of each tick, reducing packet spam for rapid inventory changes.
 *
 * Wired via:
 * - NMS hook in apply-btccore-patches.py: intercepts slot packet sends in ServerPlayer.connection
 * - Flush called from BTCCoreListener.onTick() (global region scheduler)
 */
public class BatchedInventoryUpdates {
    private static final Map<UUID, ConcurrentLinkedQueue<ClientboundContainerSetSlotPacket>> pending = new ConcurrentHashMap<>();
    private static volatile long currentTick = 0;

    public static boolean shouldBatch(ServerPlayer player) {
        return BTCCoreConfig.batchedInventoryUpdates && player != null;
    }

    /**
     * Queues a slot packet for batched sending, or sends immediately if batching is disabled.
     * Called from the NMS hook in ServerGamePacketListenerImpl.
     */
    public static void queuePacket(ServerPlayer player, ClientboundContainerSetSlotPacket packet) {
        if (!shouldBatch(player)) {
            player.connection.send(packet);
            return;
        }
        pending.computeIfAbsent(player.getUUID(), k -> new ConcurrentLinkedQueue<>()).add(packet);
    }

    /**
     * Flushes all pending packets for a specific player.
     * Called at end-of-tick from BTCCoreListener.
     */
    public static void flush(ServerPlayer player) {
        var queue = pending.remove(player.getUUID());
        if (queue != null) {
            ClientboundContainerSetSlotPacket p;
            while ((p = queue.poll()) != null) {
                player.connection.send(p);
            }
        }
    }

    /**
     * Flushes all pending packets for all online players.
     * Called from the global region scheduler tick task.
     */
    public static void flushAll() {
        pending.forEach((uuid, queue) -> {
            var player = Bukkit.getPlayer(uuid);
            if (player instanceof ServerPlayer sp) {
                flush(sp);
            } else {
                // Player offline, discard
                pending.remove(uuid);
            }
        });
    }

    /**
     * Called at tick start to track current tick.
     */
    public static void onTickStart(long tick) {
        currentTick = tick;
    }
}
