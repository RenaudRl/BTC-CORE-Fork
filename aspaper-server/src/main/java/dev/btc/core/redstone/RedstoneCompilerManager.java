package dev.btc.core.redstone;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.redstone.compile.Compilation;
import dev.btc.core.redstone.compile.CompileResult;
import dev.btc.core.redstone.compile.GraphCompiler;
import dev.btc.core.redstone.graph.CompiledGraph;
import dev.btc.core.redstone.graph.GraphRuntime;
import dev.btc.core.redstone.graph.Node;
import dev.btc.core.redstone.graph.NodeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Owns every compiled redstone circuit of one world.
 *
 * <p>Life cycle of a zone:
 * <ol>
 *   <li>redstone activity concentrates in a chunk, so the circuit around it is compiled;</li>
 *   <li>while compiled, the world copy of those blocks is frozen: vanilla neighbour updates aimed
 *       inside the zone are swallowed, and the graph is ticked instead;</li>
 *   <li>when the tick queue empties, every node that moved is written back to the world;</li>
 *   <li>any structural edit inside the zone writes back, drops the graph and hands the blocks back
 *       to vanilla; the circuit is compiled again once the edits stop and it is active again.</li>
 * </ol>
 *
 * <p>Every method is called from the thread ticking this level, from the NMS hooks in
 * {@code ServerLevel}. Nothing here is safe to call from anywhere else.
 */
public final class RedstoneCompilerManager {

    /** How often the world whitelist is re-read, so a config reload takes effect without a restart. */
    private static final int WHITELIST_REFRESH_TICKS = 200;

    private final ServerLevel level;

    private final List<Zone> zones = new ArrayList<>();

    /** Chunk key to the zones overlapping it, so the hot path never scans every zone. */
    private final Map<Long, List<Zone>> zonesByChunk = new HashMap<>();

    /** Redstone updates seen per chunk in the current window, the signal that a circuit is running. */
    private final Map<Long, Activity> activity = new HashMap<>();

    /** Chunk key to the tick before which no compilation will be attempted there. */
    private final Map<Long, Long> cooldownUntil = new HashMap<>();

    /** True while write-back is running, so our own block writes are not mistaken for player edits. */
    private boolean writingBack;

    private boolean whitelisted;
    private boolean whitelistChecked;
    private long whitelistCheckedTick;

    /** Compilation attempts and refusals, the only way to tell an idle gate from a refused circuit. */
    private int compileAttempts;
    private int compileRefusals;

    /** Why the last attempt in this world was refused, or {@code null} when none ever was. */
    private String lastRefusal;

    /**
     * Zones installed and zones handed back, counted for the whole life of this manager.
     *
     * <p>Peak zone count is sampled once per world tick and therefore cannot see a zone that is
     * installed and released between two samples. Without these two counters a benchmark can report
     * "40 attempts, 0 refused, 0 zones" and leave no way to tell a compiler that never engaged from
     * one that engages and is thrown out again immediately.
     */
    private int zonesCreated;
    private int zonesReleased;

    /**
     * Set once the first world edit has thrown a zone out, so the reason is stated once instead of
     * once per release. A benchmark that reports "248 installed, 248 released" says the compiler
     * never survived; it does not say what evicted it, and that is the only thing worth knowing.
     */
    private boolean evictionReported;

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger(RedstoneCompilerManager.class);

    public RedstoneCompilerManager(final ServerLevel level) {
        this.level = level;
    }

    // --- hot path ----------------------------------------------------------------------------

    /**
     * Called for every neighbour update in this world.
     *
     * @return true when the graph owns this position, meaning vanilla must not process the update
     */
    public boolean absorbNeighborChanged(final BlockPos pos, final BlockState state) {
        if (this.writingBack) {
            return false;
        }
        if (this.zones.isEmpty()) {
            this.recordActivity(pos, state);
            return false;
        }
        final Zone zone = this.zoneAt(pos);
        if (zone == null) {
            this.recordActivity(pos, state);
            return false;
        }
        this.refreshContainer(zone, pos);
        return true;
    }

    /** True when the graph, not the world, decides this block's scheduled ticks. */
    public boolean ownsBlockTick(final BlockPos pos) {
        if (this.writingBack || this.zones.isEmpty()) {
            return false;
        }
        final Zone zone = this.zoneAt(pos);
        if (zone == null) {
            return false;
        }
        final int index = zone.graph.nodeAt(pos.asLong());
        if (index < 0) {
            return false;
        }
        // Buttons and pressure plates keep their own vanilla timer: their delays are far longer than
        // the graph's tick queue can hold, and vanilla releasing them arrives here as an input change.
        return switch (zone.graph.nodes()[index].type) {
            case WIRE, REPEATER, COMPARATOR, TORCH, LAMP -> true;
            default -> false;
        };
    }

    /** A compilation the activity gate asked for, carried to the head of the next world tick. */
    private long pendingChunk;
    private long pendingOrigin;
    private boolean compilePending;

    // --- world edits -------------------------------------------------------------------------

    /** Called for every block change in this world, after the world has been updated. */
    public void onBlockUpdated(final BlockPos pos, final BlockState oldState, final BlockState newState) {
        if (this.writingBack || this.zones.isEmpty()) {
            return;
        }
        final Zone zone = this.zoneAt(pos);
        if (zone == null) {
            return;
        }

        final int index = zone.graph.nodeAt(pos.asLong());
        if (index >= 0 && oldState.getBlock() == newState.getBlock()) {
            final Node node = zone.graph.nodes()[index];
            final int strength = inputStrength(node.type, newState, this.level, pos);
            if (strength >= 0) {
                if (zone.runtime.setInput(index, strength)) {
                    zone.changed = true;
                }
                return;
            }
        }

        // Anything else — a block placed, broken, replaced, or a graph-owned state written by someone
        // other than us — means the world no longer matches the graph. Give the circuit back.
        if (!this.evictionReported) {
            this.evictionReported = true;
            final CompiledGraph g = zone.graph;
            LOGGER.warn("[redstone] zone evicted by a world edit at {},{},{}: {} -> {}; node index {} "
                    + "({} node(s) simulated, box {},{},{} to {},{},{}). A position inside the box that "
                    + "the graph does not own evicts the zone on every vanilla state change there.",
                pos.getX(), pos.getY(), pos.getZ(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(oldState.getBlock()),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(newState.getBlock()),
                index, g.size(),
                g.minX(), g.minY(), g.minZ(), g.maxX(), g.maxY(), g.maxZ());
        }
        this.release(zone);
        this.cooldownUntil.put(chunkKey(pos), this.now() + BTCCoreConfig.redstoneCompilerRecompileDelayTicks);
    }

    // --- world tick --------------------------------------------------------------------------

    /** True when this world currently holds at least one compiled circuit. */
    public boolean hasZones() {
        return !this.zones.isEmpty();
    }

    public void tick() {
        // Closes the previous tick before this one starts accumulating.
        RedstoneProfiler.endOfTick(this.level);
        // Compares graph against world before either side moves this tick.
        RedstoneVerifier.step(this.level);

        final boolean profiling = RedstoneProfiler.samples(this.level);
        final long started = profiling ? System.nanoTime() : 0L;
        try {
            this.drainPendingCompile();
            this.tickZones();
        } finally {
            if (profiling) {
                RedstoneProfiler.recordCompiled(System.nanoTime() - started);
                int nodes = 0;
                for (final Zone zone : this.zones) {
                    nodes += zone.graph.size();
                }
                RedstoneProfiler.recordEngagement(
                    this.zones.size(), nodes, this.compileAttempts, this.compileRefusals,
                    this.zonesCreated, this.zonesReleased, this.lastRefusal);
            }
        }
    }

    private void tickZones() {
        if (!this.zones.isEmpty() && !this.isWhitelisted()) {
            this.releaseAll();
            return;
        }
        for (int i = this.zones.size() - 1; i >= 0; i--) {
            final Zone zone = this.zones.get(i);
            if (!this.chunksStillLoaded(zone)) {
                this.release(zone);
                continue;
            }
            if (zone.runtime.tick()) {
                zone.changed = true;
            }
            if (zone.changed && zone.graph.isQuiescent()) {
                this.writeBack(zone);
                zone.changed = false;
            }
        }
    }

    // --- compilation -------------------------------------------------------------------------

    private void recordActivity(final BlockPos pos, final BlockState state) {
        if (!this.isWhitelisted() || RedstoneVerifier.active(this.level)) {
            return;
        }
        final long chunk = chunkKey(pos);
        final long tick = this.now();

        final Long cooldown = this.cooldownUntil.get(chunk);
        if (cooldown != null) {
            if (tick < cooldown) {
                return;
            }
            this.cooldownUntil.remove(chunk);
        }

        final Activity seen = this.activity.computeIfAbsent(chunk, key -> new Activity());
        if (tick - seen.windowStart > BTCCoreConfig.redstoneCompilerActivityWindowTicks) {
            seen.windowStart = tick;
            seen.count = 0;
            seen.hasOrigin = false;
        }
        // The update that happens to cross the threshold usually lands on a bystander — the floor
        // under a wire, the air beside a torch — and a flood fill started there finds nothing. Keep
        // the last position in this chunk that actually held a component, and start from that.
        if (GraphCompiler.isComponent(state)) {
            seen.origin = pos.asLong();
            seen.hasOrigin = true;
        }
        if (++seen.count < BTCCoreConfig.redstoneCompilerActivityThreshold) {
            return;
        }

        this.activity.remove(chunk);
        if (!seen.hasOrigin) {
            // Busy chunk, but the traffic never touched a component: nothing here to compile.
            this.cooldownUntil.put(chunk, tick + BTCCoreConfig.redstoneCompilerRecompileDelayTicks);
            return;
        }
        // Queued rather than compiled here: see drainPendingCompile.
        this.pendingChunk = chunk;
        this.pendingOrigin = seen.origin;
        this.compilePending = true;
    }

    /**
     * Compiles what the activity gate asked for, at the head of a world tick.
     *
     * <p>This must not happen inside {@link #recordActivity}. That runs from the neighbour updates of
     * a {@code Level#setBlock} that is still in flight, and the tail of that same setBlock calls
     * {@code sendBlockUpdated} — which reaches {@link #onBlockUpdated}, finds a state the brand new
     * graph does not drive, and hands the circuit straight back. A zone installed there is destroyed
     * a few stack frames below its own creation, which is why the benchmark reported the compiler
     * engaging and being thrown out on every single attempt without ever running a compiled tick.
     */
    private void drainPendingCompile() {
        if (!this.compilePending) {
            return;
        }
        this.compilePending = false;
        if (!this.compileAt(BlockPos.of(this.pendingOrigin))) {
            // Nothing compilable there: stop trying until the delay has passed.
            this.cooldownUntil.put(this.pendingChunk,
                this.now() + BTCCoreConfig.redstoneCompilerRecompileDelayTicks);
        }
    }

    private boolean compileAt(final BlockPos origin) {
        this.compileAttempts++;
        final CompileResult result = GraphCompiler.compile(
            this.level, origin,
            BTCCoreConfig.redstoneCompilerMaxNodes,
            BTCCoreConfig.redstoneCompilerMaxExtent);
        if (!result.succeeded()) {
            this.compileRefusals++;
            this.lastRefusal = result.refusal();
            return false;
        }

        final Zone zone = new Zone(result.compilation());
        this.zonesCreated++;
        this.zones.add(zone);
        for (final long chunk : zone.chunks) {
            this.zonesByChunk.computeIfAbsent(chunk, key -> new ArrayList<>()).add(zone);
            this.activity.remove(chunk);
        }
        return true;
    }

    /** Writes the circuit back one last time and returns its blocks to vanilla redstone. */
    private void release(final Zone zone) {
        if (zone.changed) {
            this.writeBack(zone);
        }
        this.wake(zone);
        this.zonesReleased++;
        this.zones.remove(zone);
        for (final long chunk : zone.chunks) {
            final List<Zone> inChunk = this.zonesByChunk.get(chunk);
            if (inChunk != null && inChunk.remove(zone) && inChunk.isEmpty()) {
                this.zonesByChunk.remove(chunk);
            }
        }
    }

    private void releaseAll() {
        for (final Zone zone : new ArrayList<>(this.zones)) {
            this.release(zone);
        }
    }

    /**
     * Hands a released circuit back to vanilla in a state it can carry on from.
     *
     * <p>Without this, a circuit dropped mid-run would simply stop: the graph held its pending ticks,
     * and vanilla has none of its own for those blocks. One neighbour update per component makes each
     * diode look at the world again and schedule what it needs.
     */
    private void wake(final Zone zone) {
        this.writingBack = true;
        try {
            for (final Node node : zone.graph.nodes()) {
                if (!node.type.acceptsInput()) {
                    continue;
                }
                final BlockPos pos = BlockPos.of(node.pos);
                this.level.neighborChanged(this.level.getBlockState(pos), pos, Blocks.REDSTONE_WIRE, null, false);
            }
        } finally {
            this.writingBack = false;
        }
    }

    // --- write-back --------------------------------------------------------------------------

    /**
     * Pushes every node that moved back into the world.
     *
     * <p>Only the nodes the runtime recorded as dirty are visited. Scanning the whole graph instead
     * made the write-back cost proportional to the circuit's size rather than to what actually
     * changed, on a circuit where most ticks move nothing.
     *
     * <p>Two phases on purpose: the blocks that carry a state are written first, so that when the
     * output blocks are finally poked, what vanilla reads around them is already correct.
     */
    private void writeBack(final Zone zone) {
        final int count = zone.runtime.dirtyCount();
        if (count == 0) {
            return;
        }
        final int[] dirty = zone.runtime.dirtyNodes();
        final Node[] nodes = zone.graph.nodes();
        // Read once rather than per block: on a server with no listener this makes the whole
        // event path disappear, and on one with a listener it costs a single array length.
        final boolean redstoneListeners =
            org.bukkit.event.block.BlockRedstoneEvent.getHandlerList().getRegisteredListeners().length > 0;

        this.writingBack = true;
        try {
            int outputCount = 0;
            for (int i = 0; i < count; i++) {
                final Node node = nodes[dirty[i]];
                if (node.type == NodeType.PISTON || node.type == NodeType.GENERIC_OUTPUT) {
                    zone.outputs[outputCount++] = node.pos;
                    continue;
                }
                final BlockPos pos = BlockPos.of(node.pos);
                if (node.type == NodeType.WIRE) {
                    this.writeWire(pos, node, redstoneListeners);
                    continue;
                }
                final BlockState state = this.level.getBlockState(pos);
                final BlockState updated = applyToWorld(state, node);
                if (updated != null && updated != state) {
                    // The state property moved, the block did not, so its shape is the shape it
                    // already had; lighting still runs, which a lamp and a torch both need.
                    this.level.setBlock(pos, updated, BlockWriter.STATE_ONLY);
                }
            }
            for (int i = 0; i < outputCount; i++) {
                final BlockPos pos = BlockPos.of(zone.outputs[i]);
                this.level.neighborChanged(this.level.getBlockState(pos), pos, Blocks.REDSTONE_WIRE, null, false);
            }
        } finally {
            this.writingBack = false;
            zone.runtime.clearDirty();
        }
    }

    /**
     * Stores one dust power change, through {@link BlockWriter} rather than {@code Level#setBlock}.
     *
     * <p>{@code BlockRedstoneEvent} is fired here for the same reason vanilla and Alternate Current
     * fire it: a plugin is allowed to override the current a wire carries. A compiled zone used to
     * skip it silently, so redstone inside a compiled circuit was invisible to every plugin
     * listening. When a listener does change the value, the node is moved with it, exactly as
     * Alternate Current does — consumers updated earlier in this same burst keep the un-overridden
     * value until the next change reaches them.
     */
    private void writeWire(final BlockPos pos, final Node node, final boolean redstoneListeners) {
        final BlockState state = this.level.getBlockState(pos);
        if (!state.is(Blocks.REDSTONE_WIRE)) {
            // The world no longer holds dust here; the edit that did that releases the zone itself.
            return;
        }
        final int previous = state.getValue(RedStoneWireBlock.POWER);
        int target = node.strength;
        if (previous == target) {
            return;
        }
        if (redstoneListeners) {
            target = org.bukkit.craftbukkit.event.CraftEventFactory
                .callRedstoneChange(this.level, pos, previous, target).getNewCurrent();
            if (target == previous) {
                return;
            }
            node.setStrength(target);
        }
        BlockWriter.setWirePower(this.level, pos, state.setValue(RedStoneWireBlock.POWER, target));
    }

    /**
     * The node's runtime state expressed as a block state, or {@code null} when this node's block is
     * owned by the world rather than by the graph.
     */
    static BlockState applyToWorld(final BlockState state, final Node node) {
        return switch (node.type) {
            case WIRE -> state.is(Blocks.REDSTONE_WIRE)
                ? state.setValue(RedStoneWireBlock.POWER, node.strength)
                : null;
            case REPEATER, COMPARATOR -> state.hasProperty(BlockStateProperties.POWERED)
                ? state.setValue(BlockStateProperties.POWERED, node.powered)
                : null;
            case TORCH, LAMP -> state.hasProperty(BlockStateProperties.LIT)
                ? state.setValue(BlockStateProperties.LIT, node.powered)
                : null;
            // Inputs: the player, an entity or vanilla's own timer owns these blocks.
            case LEVER, BUTTON, PRESSURE_PLATE, CONSTANT -> null;
            // Handled by the output phase, never written as a state.
            case PISTON, GENERIC_OUTPUT -> null;
        };
    }

    // --- inputs ------------------------------------------------------------------------------

    /**
     * The strength a world-driven node now emits, or {@code -1} when this node type is not driven by
     * the world, meaning the change was not an input and the graph has diverged.
     */
    static int inputStrength(final NodeType type, final BlockState state,
                             final ServerLevel level, final BlockPos pos) {
        return switch (type) {
            case LEVER, BUTTON -> state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED) ? 15 : 0;
            // Weighted plates emit 1-15, so ask the block rather than reading a flag.
            case PRESSURE_PLATE -> state.getSignal(level, pos, Direction.UP);
            default -> -1;
        };
    }

    /**
     * A comparator inside the zone was told its container changed. Its analog output replaces the
     * back input, so it is re-read here rather than being resolved through links.
     */
    private void refreshContainer(final Zone zone, final BlockPos pos) {
        if (zone.containers.isEmpty()) {
            return;
        }
        final int index = zone.graph.nodeAt(pos.asLong());
        if (index < 0) {
            return;
        }
        final Long containerPos = zone.containers.get(index);
        if (containerPos == null) {
            return;
        }

        final BlockState comparator = this.level.getBlockState(pos);
        if (!comparator.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        final BlockPos container = BlockPos.of(containerPos);
        final BlockState containerState = this.level.getBlockState(container);
        if (!containerState.hasAnalogOutputSignal()) {
            // The container is gone; the block change itself already released the zone if it was
            // inside the box, so all that is left to do is stop trusting the override.
            return;
        }

        final Direction back = comparator.getValue(HorizontalDirectionalBlock.FACING);
        final int signal = containerState.getAnalogOutputSignal(this.level, container, back.getOpposite());
        final Node node = zone.graph.nodes()[index];
        if (node.containerOverride == signal) {
            return;
        }
        node.containerOverride = signal;
        if (zone.runtime.refresh(index)) {
            zone.changed = true;
        }
    }

    // --- lookups -----------------------------------------------------------------------------

    private Zone zoneAt(final BlockPos pos) {
        final List<Zone> inChunk = this.zonesByChunk.get(chunkKey(pos));
        if (inChunk == null) {
            return null;
        }
        for (final Zone zone : inChunk) {
            if (zone.graph.contains(pos.getX(), pos.getY(), pos.getZ())) {
                return zone;
            }
        }
        return null;
    }

    private boolean chunksStillLoaded(final Zone zone) {
        for (final long chunk : zone.chunks) {
            if (!this.level.hasChunk((int) (chunk >> 32), (int) chunk)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The cached whitelist answer, refreshed every {@link #WHITELIST_REFRESH_TICKS} so a config
     * reload takes effect without a restart.
     *
     * <p>The first call is driven by an explicit flag rather than by a sentinel tick. Seeding the
     * sentinel with {@code Long.MIN_VALUE} made {@code tick - sentinel} overflow into a negative
     * number for every real tick, so the refresh never fired, {@link #whitelisted} kept its default
     * {@code false}, and {@link #recordActivity} returned immediately on every single update — the
     * compiler could never compile anything, in any world.
     */
    private boolean isWhitelisted() {
        final long tick = this.now();
        if (!this.whitelistChecked || tick - this.whitelistCheckedTick >= WHITELIST_REFRESH_TICKS) {
            this.whitelistChecked = true;
            this.whitelistCheckedTick = tick;
            this.whitelisted = BTCCoreConfig.isRedstoneCompilerEnabledFor(this.level.getWorld().getName());
        }
        return this.whitelisted;
    }

    private long now() {
        return this.level.getServer().getTickCount();
    }

    private static long chunkKey(final BlockPos pos) {
        return ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
    }

    private static long[] chunkKeys(final CompiledGraph graph) {
        final int minChunkX = graph.minX() >> 4;
        final int maxChunkX = graph.maxX() >> 4;
        final int minChunkZ = graph.minZ() >> 4;
        final int maxChunkZ = graph.maxZ() >> 4;
        final long[] keys = new long[(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
        int next = 0;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                keys[next++] = ((long) x << 32) | (z & 0xFFFFFFFFL);
            }
        }
        return keys;
    }

    // --- state -------------------------------------------------------------------------------

    /** One compiled circuit and everything needed to keep it in step with the world. */
    private static final class Zone {

        final CompiledGraph graph;
        final GraphRuntime runtime;

        /** Comparator node index to the packed position of the container it reads. */
        final Map<Integer, Long> containers;

        /**
         * Chunks this zone overlaps, resolved once. The tick loop checks they are still loaded on
         * every tick, and recomputing them there allocated an array per zone per tick.
         */
        final long[] chunks;

        /** Scratch buffer for the write-back's output phase, so it allocates nothing per pass. */
        final long[] outputs;

        /** Some node moved since the last write-back. */
        boolean changed;

        Zone(final Compilation compilation) {
            this.graph = compilation.graph();
            this.runtime = new GraphRuntime(this.graph);
            this.chunks = chunkKeys(this.graph);
            this.outputs = new long[this.graph.size()];
            this.containers = HashMap.newHashMap(compilation.containerNodes().length);
            for (int i = 0; i < compilation.containerNodes().length; i++) {
                this.containers.put(compilation.containerNodes()[i], compilation.containerPositions()[i]);
            }
        }
    }

    /** Redstone updates counted in one chunk over one window, and where to start compiling from. */
    private static final class Activity {
        long windowStart;
        int count;
        /** Packed position of the last real component seen in this chunk during the window. */
        long origin;
        boolean hasOrigin;
    }
}
