/*
 * Derived from Petal "Async Pathfinding" (peaches94, GPL-3.0-only, https://github.com/Bloom-host/Petal),
 * fixed and updated by Kaiiju (GPL-3.0-only, https://github.com/KaiijuMC/Kaiiju), as carried by Leaf
 * (org.dreeam.leaf.async.path.AsyncPath; HaHaWTH, Taiyou06, Altiami, MrlingXD, hayanesuru), GPL-3.0.
 *
 * Changed for BTC-CORE: the cross-thread state lives in PathHandoff, and a search that throws
 * publishes an empty path instead of leaving the mob waiting for a result that never comes.
 */
package dev.btc.core.async.path;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A path whose search may still be running on the pool. The pool only publishes the result; it is
 * copied into this path by the entity's own thread, the first time that thread asks
 * ({@link #isProcessed()}) or cannot wait ({@link #process()}, which then searches on the spot).
 */
public final class AsyncPath extends Path {

    private final PathHandoff<Path> handoff = new PathHandoff<>();
    /** The positions this path could lead to. */
    private final Set<BlockPos> positions;
    private final PathFinder finder;
    private @Nullable Function<PathFinder, @Nullable Path> search;

    // While the search runs the target could be anywhere, so "close" and "reachable" are the defaults.
    private float distToTarget = 0;
    private boolean canReach = true;

    public AsyncPath(final PathFinder finder, final List<Node> emptyNodeList, final Set<BlockPos> positions,
                     final Function<PathFinder, @Nullable Path> search) {
        super(emptyNodeList, BlockPos.ZERO, false);
        this.finder = finder;
        this.positions = positions;
        this.search = search;

        AsyncPathProcessor.queue(() -> this.search(search));
    }

    /** Pool thread, or the owner when it cannot wait. One search per path, one at a time per finder. */
    private void search(final Function<PathFinder, @Nullable Path> search) {
        synchronized (this.finder) {
            if (this.handoff.published() != null) {
                return;
            }
            Path result = null;
            try {
                result = search.apply(this.finder);
            } catch (final Throwable thrown) {
                AsyncPathProcessor.LOGGER.error("Path search failed, the mob gets an empty path", thrown);
            }
            if (result == null) {
                // Never leave the mob waiting: an unreachable, empty path makes it ask again.
                result = new Path(List.of(), this.positions.iterator().next(), false);
            }
            this.handoff.publish(result);
        }
    }

    @Override
    public boolean isProcessed() {
        return this.handoff.applyIfPublished(this::complete);
    }

    /** Owner thread. Runs {@code action} once this path is processed, unless it is no longer current then. */
    public void whenApplied(final BooleanSupplier stillCurrent, final Runnable action) {
        this.handoff.whenApplied(stillCurrent, action);
    }

    /** Whether a new request for {@code positions} is the one already being searched. */
    public boolean hasSameProcessingPositions(final Set<BlockPos> positions) {
        return this.positions.equals(positions);
    }

    /** Owner thread. Makes the result available now, searching here if the pool has not got to it. */
    public void process() {
        if (this.handoff.isApplied()) {
            return;
        }
        if (this.handoff.published() == null) {
            this.search(Objects.requireNonNull(this.search));
        }
        this.handoff.applyIfPublished(this::complete);
    }

    private void complete(final Path bestPath) {
        this.nodes = bestPath.nodes;
        this.target = bestPath.getTarget();
        this.distToTarget = bestPath.getDistToTarget();
        this.canReach = bestPath.canReach();
        final Path.DebugData debugData = bestPath.debugData();
        if (debugData != null) {
            this.setDebug(debugData.openSet(), debugData.closedSet(), debugData.targetNodes());
        }
        this.search = null; // lets go of the region the search read
    }

    // The fields these read are final in Path, or only meaningful once processed.

    @Override
    public BlockPos getTarget() {
        this.process();
        return this.target;
    }

    @Override
    public float getDistToTarget() {
        this.process();
        return this.distToTarget;
    }

    @Override
    public boolean canReach() {
        this.process();
        return this.canReach;
    }

    @Override
    public Path.@Nullable DebugData debugData() {
        return this.isProcessed() ? super.debugData() : null;
    }

    @Override
    public boolean isDone() {
        return this.isProcessed() && super.isDone();
    }

    @Override
    public void advance() {
        this.process();
        super.advance();
    }

    @Override
    public boolean notStarted() {
        this.process();
        return super.notStarted();
    }

    @Override
    public @Nullable Node getEndNode() {
        this.process();
        return super.getEndNode();
    }

    @Override
    public Node getNode(final int index) {
        this.process();
        return super.getNode(index);
    }

    @Override
    public void truncateNodes(final int length) {
        this.process();
        super.truncateNodes(length);
    }

    @Override
    public void replaceNode(final int index, final Node node) {
        this.process();
        super.replaceNode(index, node);
    }

    @Override
    public int getNodeCount() {
        this.process();
        return super.getNodeCount();
    }

    @Override
    public int getNextNodeIndex() {
        this.process();
        return super.getNextNodeIndex();
    }

    @Override
    public void setNextNodeIndex(final int nodeIndex) {
        this.process();
        super.setNextNodeIndex(nodeIndex);
    }

    @Override
    public Vec3 getEntityPosAtNode(final Entity entity, final int index) {
        this.process();
        return super.getEntityPosAtNode(entity, index);
    }

    @Override
    public BlockPos getNodePos(final int index) {
        this.process();
        return super.getNodePos(index);
    }

    @Override
    public Vec3 getNextEntityPos(final Entity entity) {
        this.process();
        return super.getNextEntityPos(entity);
    }

    @Override
    public BlockPos getNextNodePos() {
        this.process();
        return super.getNextNodePos();
    }

    @Override
    public Node getNextNode() {
        this.process();
        return super.getNextNode();
    }

    @Override
    public @Nullable Node getPreviousNode() {
        this.process();
        return super.getPreviousNode();
    }
}
