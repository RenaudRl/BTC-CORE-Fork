package dev.btc.core.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a plugin make a block emit a redstone signal it otherwise never could — a custom machine
 * block driving a comparator or a wire, which no vanilla API allows.
 *
 * <p>The emitted level is consulted by {@link SignalGetter} on every redstone read, so the lookup is
 * gated by {@link #anyEmitters}: with no active emitter the hot path is a single volatile read and
 * costs nothing. Only when at least one block is emitting does a per-position map lookup happen.
 *
 * <p>Wired to plugins through {@code dev.btc.core.api.BTCCoreAPI.setEmittedRedstonePower}.
 */
public final class RedstoneEmitterManager {

    private static final int SIGNAL_MIN = 0;
    private static final int SIGNAL_MAX = 15;

    /** Per-dimension map of packed block position to the analog level emitted there. */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> EMITTERS = new ConcurrentHashMap<>();

    /** Fast exit for the common no-emitter case, avoiding a map lookup on every redstone read. */
    private static volatile boolean anyEmitters = false;

    private RedstoneEmitterManager() {
    }

    /**
     * The redstone level emitted at {@code pos} in {@code getter}'s world, or {@code 0} when nothing
     * is emitting there. Safe to call from any region thread performing redstone calculations.
     */
    public static int emitted(final SignalGetter getter, final BlockPos pos) {
        if (!anyEmitters) {
            return SIGNAL_MIN;
        }
        if (!(getter instanceof Level level)) {
            return SIGNAL_MIN;
        }
        final Map<Long, Integer> map = EMITTERS.get(level.dimension());
        if (map == null) {
            return SIGNAL_MIN;
        }
        return map.getOrDefault(pos.asLong(), SIGNAL_MIN);
    }

    /**
     * Sets — or, when {@code power <= 0}, clears — the redstone level emitted at a block, then
     * refreshes the neighbouring redstone so the change propagates immediately.
     *
     * <p>Must be called on the region that owns the block, since it touches world state.
     */
    public static void set(final World world, final int x, final int y, final int z, final int power) {
        final ServerLevel level = ((CraftWorld) world).getHandle();
        final ResourceKey<Level> dimension = level.dimension();
        final BlockPos pos = new BlockPos(x, y, z);
        final long key = pos.asLong();

        if (power <= SIGNAL_MIN) {
            final Map<Long, Integer> map = EMITTERS.get(dimension);
            if (map != null) {
                map.remove(key);
                if (map.isEmpty()) {
                    EMITTERS.remove(dimension);
                }
            }
        } else {
            EMITTERS.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .put(key, Math.min(SIGNAL_MAX, power));
        }
        anyEmitters = !EMITTERS.isEmpty();

        level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
    }
}
