package dev.btc.core.security;

import net.minecraft.world.phys.AABB;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Creates an AABB/Positional ghost cache for players to compensate for latency.
 * Stores up to MAX_TICKS of historical hitbox data.
 */
public class PlayerSimulationCache {
    
    private static final int MAX_TICKS = 40; // 2 seconds of history

    private static final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<GhostState>> simulations = new ConcurrentHashMap<>();

    public static class GhostState {
        public final double x, y, z;
        public final AABB boundingBox;
        public final long timestamp;

        public GhostState(double x, double y, double z, AABB boundingBox) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.boundingBox = boundingBox;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static void updateCache(UUID uuid, double x, double y, double z, AABB boundingBox) {
        if (!dev.btc.core.config.BTCCoreConfig.sentinelEnabled) return;

        ConcurrentLinkedDeque<GhostState> history = simulations.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        
        history.addFirst(new GhostState(x, y, z, boundingBox));
        
        while (history.size() > MAX_TICKS) {
            history.removeLast();
        }
    }

    public static ConcurrentLinkedDeque<GhostState> getHistory(UUID uuid) {
        return simulations.get(uuid);
    }

    public static void clear(UUID uuid) {
        simulations.remove(uuid);
        AsyncPacketValidator.clearPlayer(uuid);
    }

    public static GhostState getInterpolatedState(UUID target, long targetTimeMs) {
        ConcurrentLinkedDeque<GhostState> history = getHistory(target);
        if (history == null || history.isEmpty()) return null;

        GhostState closest = history.peekFirst();
        long smallestDiff = Long.MAX_VALUE;

        for (GhostState state : history) {
            long diff = Math.abs(state.timestamp - targetTimeMs);
            if (diff < smallestDiff) {
                smallestDiff = diff;
                closest = state;
            }
        }
        return closest;
    }
}

