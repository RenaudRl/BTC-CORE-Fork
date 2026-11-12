package dev.btc.core.performance;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.util.MathUtilities;
import com.logisticscraft.occlusionculling.util.Vec3d;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Which entities one viewer cannot see, worked out away from the world
 * ({@code performance.raytrace-culling}): rays of the MIT library {@code occlusionculling}
 * (LogisticsCraft) through an {@link OpacityIndex}, on boxes copied on the thread of the world.
 *
 * <p>Whatever cannot be decided is visible: a block the index does not know, a box that reaches
 * past the range or is larger than {@link #MAX_BOX_SIDE}, a ray that fails (the library answers
 * "visible").
 */
public final class OcclusionScan {

    public static final int MIN_RANGE = 8;
    /** The cache of the library grows with the cube of the range: 4 MiB per worker thread here. */
    public static final int MAX_RANGE = 128;
    /** How far past its box an entity is looked for; the default of the library. */
    public static final double BOX_MARGIN = 0.5D;
    /**
     * A box with a side longer than this, in blocks, is always seen: half the width of an ender
     * dragon. Rays cost with the volume of the box, and a wall rarely hides something that large.
     */
    public static final double MAX_BOX_SIDE = 8.0D;
    /** Blocks at the edge of its reach the library answers "hidden" for without looking. */
    private static final int REACH_EDGE = 2;

    /** An entity as it stood when the world copied it. */
    public record Candidate(int id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    private final int reach;
    private final ThreadLocal<Tracer> tracers;

    public OcclusionScan(int range) {
        this.reach = boundedRange(range);
        this.tracers = ThreadLocal.withInitial(() -> new Tracer(this.reach));
    }

    public static int boundedRange(int range) {
        return Math.clamp(range, MIN_RANGE, MAX_RANGE);
    }

    /** An entity seen through walls, or tied to another, is never hidden. */
    public static boolean neverHidden(boolean alwaysVisibleType, boolean glowing, boolean nameShown, boolean ridingOrRidden) {
        return alwaysVisibleType || glowing || nameShown || ridingOrRidden;
    }

    /** Any thread but one at a time per thread: the ids of the candidates the eye cannot see. */
    public IntSet hidden(OpacityIndex index, double eyeX, double eyeY, double eyeZ, List<Candidate> candidates) {
        Tracer tracer = this.tracers.get();
        tracer.lookFrom(index, eyeX, eyeY, eyeZ);
        try {
            IntSet hidden = new IntOpenHashSet();
            for (Candidate candidate : candidates) {
                if (!tooLarge(candidate) && this.inReach(candidate, eyeX, eyeY, eyeZ) && !tracer.sees(candidate)) {
                    hidden.add(candidate.id());
                }
            }
            return hidden;
        } finally {
            tracer.lookFrom(null, eyeX, eyeY, eyeZ); // the thread does not hold on to a world
        }
    }

    /** The library looks at every block of the box: a giant one is seen without a ray. */
    private static boolean tooLarge(Candidate box) {
        return box.maxX() - box.minX() > MAX_BOX_SIDE
            || box.maxY() - box.minY() > MAX_BOX_SIDE
            || box.maxZ() - box.minZ() > MAX_BOX_SIDE;
    }

    private boolean inReach(Candidate box, double eyeX, double eyeY, double eyeZ) {
        return this.inReach(box.minX(), box.maxX(), eyeX)
            && this.inReach(box.minY(), box.maxY(), eyeY)
            && this.inReach(box.minZ(), box.maxZ(), eyeZ);
    }

    private boolean inReach(double min, double max, double eye) {
        int camera = MathUtilities.floor(eye);
        int furthest = this.reach - REACH_EDGE;
        return MathUtilities.floor(max + BOX_MARGIN) - camera <= furthest
            && camera - MathUtilities.floor(min - BOX_MARGIN) <= furthest;
    }

    /** The library and what it reuses from one ray to the next; one per thread. */
    private static final class Tracer implements DataProvider {

        private final OcclusionCullingInstance culler;
        private final Vec3d eye = new Vec3d(0, 0, 0);
        private final Vec3d min = new Vec3d(0, 0, 0);
        private final Vec3d max = new Vec3d(0, 0, 0);
        private @Nullable OpacityIndex index;
        private boolean sectionRead;
        private long sectionKey;
        private @Nullable AtomicLongArray section;

        Tracer(int reach) {
            this.culler = new OcclusionCullingInstance(reach, this, new ArrayOcclusionCache(reach), BOX_MARGIN);
        }

        void lookFrom(@Nullable OpacityIndex index, double x, double y, double z) {
            this.index = index;
            this.sectionRead = false;
            this.section = null;
            this.eye.set(x, y, z);
            this.culler.resetCache();
        }

        boolean sees(Candidate box) {
            this.min.set(box.minX(), box.minY(), box.minZ());
            this.max.set(box.maxX(), box.maxY(), box.maxZ());
            return this.culler.isAABBVisible(this.min, this.max, this.eye);
        }

        @Override
        public boolean prepareChunk(int chunkX, int chunkZ) {
            return true; // "not ready" would hide the entity; a chunk the index does not know hides nothing
        }

        @Override
        public boolean isOpaqueFullCube(int x, int y, int z) {
            long key = OpacityIndex.keyAt(x, y, z);
            if (!this.sectionRead || key != this.sectionKey) { // a ray stays in one section for up to 16 blocks
                this.section = this.index.section(key);
                this.sectionKey = key;
                this.sectionRead = true;
            }
            return OpacityIndex.opaque(this.section, x, y, z);
        }
    }
}
