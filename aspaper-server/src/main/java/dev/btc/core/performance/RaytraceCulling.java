package dev.btc.core.performance;

import com.mojang.logging.LogUtils;
import dev.btc.core.config.BTCCoreConfig;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entities behind opaque blocks are not tracked for the player who cannot see them
 * ({@code performance.raytrace-culling}).
 *
 * <p>The thread of a world does two things each tick: it reads a few chunk sections into the
 * {@link OpacityIndex} of that world, and every {@code interval-ticks} it copies, for each of its
 * players, the eye and the boxes of the entities around. The rays run on a worker thread, on those
 * copies and on the index only, and leave a set of ids the tracker reads. Nothing here is shared
 * between two worlds but the worker threads, which keep nothing from one scan to the next.
 *
 * <p>The tracking is ours; the rays are the MIT library {@code occlusionculling} (LogisticsCraft).
 * The eye is that of the player: a third-person camera sees round a corner the eye does not.
 */
public final class RaytraceCulling {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SECTION_WIDTH = 16;
    /**
     * A scan older than this many {@code interval-ticks} hides nothing: the workers are shared by
     * every world, and when they run late an entity is shown rather than left hidden.
     */
    private static final int STALE_AFTER_INTERVALS = 3;
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private static volatile Set<EntityType<?>> alwaysVisible;

    private RaytraceCulling() {}

    /** Created on the first scan: nothing with the option off. */
    private static final class Worker {
        static final OcclusionScan SCAN = new OcclusionScan(BTCCoreConfig.raytraceCullingRange);
        static final ExecutorService POOL = Executors.newFixedThreadPool(BTCCoreConfig.raytraceCullingThreads, new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "BTCCore Raytrace Culling #" + this.count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** Any thread, a read: the tracker asks it from its pool as well. */
    public static boolean hides(ServerPlayer viewer, Entity entity) {
        ViewerCulling view = viewer.btc$culling;
        return view != null && view.hides(entity.getId(), viewer.tickCount, STALE_AFTER_INTERVALS * BTCCoreConfig.raytraceCullingIntervalTicks);
    }

    /** Thread of the world, once per tick. */
    public static void tick(ServerLevel level, OpacityIndex index) {
        for (long key : index.due(BTCCoreConfig.raytraceCullingSectionsPerTick)) {
            read(level, index, key);
        }
        for (ServerPlayer player : level.players()) {
            if (player.tickCount % BTCCoreConfig.raytraceCullingIntervalTicks == 0) {
                scan(level, index, player);
            }
        }
    }

    private static void read(ServerLevel level, OpacityIndex index, long key) {
        LevelChunk chunk = level.getChunkIfLoaded(OpacityIndex.sectionX(key), OpacityIndex.sectionZ(key));
        if (chunk == null) {
            index.forget(key); // asked first: a section above or below the world leaves with its chunk too
            return;
        }
        int sectionY = OpacityIndex.sectionY(key);
        if (sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) {
            index.index(key, null); // above or below the world
            return;
        }
        index.index(key, opaqueBits(chunk.getSection(level.getSectionIndexFromSectionY(sectionY))));
    }

    private static long @Nullable [] opaqueBits(LevelChunkSection section) {
        if (section.hasOnlyAir() || !section.maybeHas(BlockBehaviour.BlockStateBase::isSolidRender)) {
            return null;
        }
        long[] bits = new long[OpacityIndex.WORDS];
        for (int y = 0; y < SECTION_WIDTH; y++) {
            for (int z = 0; z < SECTION_WIDTH; z++) {
                for (int x = 0; x < SECTION_WIDTH; x++) {
                    if (section.getBlockState(x, y, z).isSolidRender()) {
                        OpacityIndex.mark(bits, x, y, z);
                    }
                }
            }
        }
        return bits;
    }

    private static void scan(ServerLevel level, OpacityIndex index, ServerPlayer player) {
        ViewerCulling view = player.btc$culling;
        if (view == null) {
            view = new ViewerCulling();
            player.btc$culling = view;
        }
        if (!view.claim()) {
            return; // the last scan of this player still runs
        }
        ViewerCulling claimed = view;
        int takenAt = player.tickCount;
        try {
            Vec3 eye = player.getEyePosition();
            // a spectator sees through walls: an empty scan shows everything again
            List<OcclusionScan.Candidate> candidates = player.isSpectator() ? List.of() : candidates(level, player, eye);
            Worker.POOL.execute(() -> claimed.scan(() -> Worker.SCAN.hidden(index, eye.x, eye.y, eye.z, candidates), takenAt, RaytraceCulling::report));
        } catch (Throwable failure) { // the tick goes on: an entity shown for nothing costs less than a world stopped
            claimed.publish(IntSets.EMPTY_SET, takenAt);
            report(failure);
        }
    }

    private static List<OcclusionScan.Candidate> candidates(ServerLevel level, ServerPlayer player, Vec3 eye) {
        double width = 2.0D * BTCCoreConfig.raytraceCullingRange;
        List<Entity> around = level.getEntities(player, AABB.ofSize(eye, width, width, width), RaytraceCulling::mayBeHidden);
        List<OcclusionScan.Candidate> candidates = new ArrayList<>(around.size());
        for (Entity entity : around) {
            AABB box = entity.getBoundingBox();
            candidates.add(new OcclusionScan.Candidate(entity.getId(), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        return candidates;
    }

    private static boolean mayBeHidden(Entity entity) {
        return !OcclusionScan.neverHidden(alwaysVisible().contains(entity.getType()), entity.isCurrentlyGlowing(),
            entity.isCustomNameVisible(), entity.isPassenger() || entity.isVehicle());
    }

    private static Set<EntityType<?>> alwaysVisible() {
        Set<EntityType<?>> resolved = alwaysVisible;
        if (resolved == null) { // immutable and the same whichever thread resolves it
            resolved = EntityMotionTypes.resolve("performance.raytrace-culling.always-visible", BTCCoreConfig.raytraceCullingAlwaysVisible);
            alwaysVisible = resolved;
        }
        return resolved;
    }


    private static void report(Throwable failure) {
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            LOGGER.error("[BTCCore] performance.raytrace-culling: a scan failed, its player sees every entity (reported once, later failures at debug level)", failure);
        } else {
            LOGGER.debug("[BTCCore] performance.raytrace-culling: a scan failed", failure);
        }
    }
}
