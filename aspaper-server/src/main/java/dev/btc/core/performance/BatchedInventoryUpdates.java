package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BatchedInventoryUpdates {
    private static final Map<UUID, ConcurrentLinkedQueue<ClientboundContainerSetSlotPacket>> pending = new ConcurrentHashMap<>();

    public static boolean shouldBatch(ServerPlayer player) {
        return BTCCoreConfig.batchedInventoryUpdates && player != null;
    }

    public static void queuePacket(ServerPlayer player, ClientboundContainerSetSlotPacket packet) {
        if (!shouldBatch(player)) {
            player.connection.send(packet);
            return;
        }
        pending.computeIfAbsent(player.getUUID(), k -> new ConcurrentLinkedQueue<>()).add(packet);
    }

    public static void flush(ServerPlayer player) {
        var queue = pending.remove(player.getUUID());
        if (queue != null) {
            ClientboundContainerSetSlotPacket p;
            while ((p = queue.poll()) != null) {
                player.connection.send(p);
            }
        }
    }

    public static void flushAll() {
        pending.forEach((uuid, queue) -> {
            var player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player instanceof ServerPlayer sp) flush(sp);
        });
    }
}
