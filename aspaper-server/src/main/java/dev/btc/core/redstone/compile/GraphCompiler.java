package dev.btc.core.redstone.compile;

import dev.btc.core.redstone.graph.CompiledGraph;
import dev.btc.core.redstone.graph.Link;
import dev.btc.core.redstone.graph.Node;
import dev.btc.core.redstone.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a live redstone circuit into a {@link CompiledGraph}.
 *
 * <p>Two passes, in the order RedPiler established:
 * <ol>
 *   <li><em>IdentifyNodes</em> — a flood fill over redstone connectivity from an origin block,
 *       producing one node per compilable component;</li>
 *   <li><em>InputSearch</em> — for every node, a breadth-first walk <em>through the dust</em> to the
 *       components that actually drive it. The dust traversal happens once, here; at runtime it is a
 *       single subtraction of the {@link Link} weight.</li>
 * </ol>
 * followed by the {@code ClampWeights}, {@code ConstantFold}, {@code DedupLinks} and
 * {@code PruneOrphans} optimisations.
 *
 * <h2>Compilation domain</h2>
 *
 * <p>Only circuits built entirely from blocks whose behaviour the graph reproduces exactly are
 * compiled: dust, repeaters, comparators, torches, lamps, levers, buttons, pressure plates, blocks
 * of redstone, pistons (as output-only nodes), and solid blocks used to transmit power. Anything
 * else that emits or consumes redstone — observers, dispensers, hoppers, doors, rails, daylight
 * detectors, TNT — <strong>aborts</strong> the compilation, and {@link #compile} comes back with a
 * {@link CompileResult} naming the block and the position that caused it. The zone then keeps
 * running on vanilla redstone, which is always correct. Widening
 * the domain means teaching the write-back how to restore each new block's state, not loosening
 * this check.
 *
 * <p>Whether a block emits in a given direction is never hard-coded here: the block state is asked
 * directly, on an {@linkplain #energised(BlockState) energised copy} of itself, so the per-block
 * direction rules stay vanilla's.
 */
public final class GraphCompiler {

    /** Signal strength a fully powered component emits. */
    private static final int SIGNAL_MAX = 15;

    private static final Direction[] DIRECTIONS = Direction.values();

    private final ServerLevel level;
    private final int maxNodes;
    private final int maxExtent;

    private final Map<Long, Integer> indexByPos = new HashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final List<BlockPos> positions = new ArrayList<>();
    private final List<List<Link>> links = new ArrayList<>();

    /** Running bounding box of the nodes found so far, so the size check stays O(1) per node. */
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    private final List<Integer> containerNodes = new ArrayList<>();
    private final List<Long> containerPositions = new ArrayList<>();

    /** Positions already handled by the flood fill, nodes and transmitting blocks alike. */
    private final Set<Long> visited = new HashSet<>();

    /**
     * Set while the inputs of a dust node are being resolved, and it makes every other dust node
     * invisible as a source for the duration.
     *
     * <p>This mirrors {@code RedStoneWireBlock.shouldSignal}, which vanilla drops for exactly the
     * span of {@code getBlockSignal}: while a dust block works out what reaches it, no dust emits at
     * all. Dust reaches dust through the wire net alone — {@link #searchDust} already walks that —
     * never through a block.
     *
     * <p>Without this, dust strongly powers the solid block it rests on, and reading that block back
     * hands the dust a link to <em>itself</em> with weight 0. Such a node computes
     * {@code max(source - weight, own strength)}, which tracks the source on the way up and then
     * latches at its lit value forever once the source goes away.
     */
    private boolean dustSourcesMuted;

    /** Why the circuit was left to vanilla, or {@code null} while the attempt is still viable. */
    private String refusal;

    private GraphCompiler(final ServerLevel level, final int maxNodes, final int maxExtent) {
        this.level = level;
        this.maxNodes = maxNodes;
        this.maxExtent = maxExtent;
    }

    /**
     * Compiles the circuit reachable from {@code origin}.
     *
     * @param level     world holding the circuit; must be read from the region thread owning it
     * @param origin    any block of the circuit, typically the one that just changed
     * @param maxNodes  refuse to compile a circuit larger than this
     * @param maxExtent refuse to compile a circuit spanning more than this many blocks on an axis
     * @return the compiled circuit, or a named refusal when it falls outside the compilation domain
     *         or exceeds the given bounds — in which case the caller must leave it to vanilla
     *         redstone. Never {@code null}.
     */
    public static CompileResult compile(final ServerLevel level, final BlockPos origin,
                                        final int maxNodes, final int maxExtent) {
        final GraphCompiler compiler = new GraphCompiler(level, maxNodes, maxExtent);
        return compiler.run(origin);
    }

    private CompileResult run(final BlockPos origin) {
        this.identifyNodes(origin);
        if (this.refusal == null && this.nodes.isEmpty()) {
            this.refuse("no compilable redstone component around " + describe(origin));
        }
        if (this.refusal != null) {
            return CompileResult.refused(this.refusal);
        }

        this.inputSearch();
        if (this.refusal != null) {
            return CompileResult.refused(this.refusal);
        }

        this.clampWeights();
        this.constantFold();
        this.dedupLinks();
        final int[] remap = this.pruneOrphans();
        if (this.nodes.isEmpty()) {
            return CompileResult.refused("every component around " + describe(origin)
                + " was pruned: nothing there drives or is driven by anything");
        }

        return CompileResult.compiled(this.assemble(remap));
    }

    /** Records the first reason the attempt failed; later ones are consequences of that one. */
    private void refuse(final String reason) {
        if (this.refusal == null) {
            this.refusal = reason;
        }
    }

    private static String describe(final BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String describe(final BlockState state, final BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
            + " at " + describe(pos);
    }

    // --- pass 1: IdentifyNodes ---------------------------------------------------------------------

    /**
     * Flood-fills redstone connectivity from {@code origin}, creating a node per compilable
     * component. Solid blocks are walked through — they transmit power — but never chained into one
     * another, which is what keeps the fill inside the circuit instead of the whole build.
     */
    private void identifyNodes(final BlockPos origin) {
        final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(origin.immutable());
        this.visited.add(origin.asLong());

        while (!frontier.isEmpty() && this.refusal == null) {
            final BlockPos pos = frontier.poll();
            final BlockState state = this.level.getBlockState(pos);
            final NodeType type = classify(state);

            if (type == null) {
                if (isUnsupported(state)) {
                    this.refuse(describe(state, pos) + " takes part in redstone but is outside the "
                        + "compilable domain (observer, hopper, dispenser, door, rail, light sensor, TNT...)");
                    return;
                }
                if (state.isRedstoneConductor(this.level, pos)) {
                    // A solid block passes power between components; look at what touches it, but
                    // stop there — strong power never chains through two blocks.
                    for (final Direction direction : DIRECTIONS) {
                        this.enqueueUnlessConductor(frontier, pos.relative(direction));
                    }
                }
                continue;
            }

            this.addNode(pos, state, type);
            if (this.refusal != null) {
                return;
            }

            for (final Direction direction : DIRECTIONS) {
                this.enqueue(frontier, pos.relative(direction));
            }
            if (type == NodeType.WIRE) {
                this.enqueueWireStaircase(frontier, pos);
            } else if (type == NodeType.PISTON) {
                // Quasi-connectivity: a piston also reads everything touching the block above it.
                final BlockPos above = pos.above();
                for (final Direction direction : DIRECTIONS) {
                    this.enqueue(frontier, above.relative(direction));
                }
            }
        }
    }

    /** Dust also connects one block up or down a step, which the flood fill has to follow. */
    private void enqueueWireStaircase(final ArrayDeque<BlockPos> frontier, final BlockPos pos) {
        for (final Direction direction : Direction.Plane.HORIZONTAL) {
            final BlockPos neighbour = pos.relative(direction);
            this.enqueue(frontier, neighbour.above());
            this.enqueue(frontier, neighbour.below());
        }
    }

    private void enqueue(final ArrayDeque<BlockPos> frontier, final BlockPos pos) {
        if (this.visited.add(pos.asLong())) {
            frontier.add(pos.immutable());
        }
    }

    /**
     * Same as {@link #enqueue}, but refuses to step from one solid block to the next: that would
     * turn the flood fill loose on the surrounding terrain without ever finding more redstone.
     */
    private void enqueueUnlessConductor(final ArrayDeque<BlockPos> frontier, final BlockPos pos) {
        final BlockState state = this.level.getBlockState(pos);
        if (classify(state) == null && state.isRedstoneConductor(this.level, pos)) {
            return;
        }
        this.enqueue(frontier, pos);
    }

    private void addNode(final BlockPos pos, final BlockState state, final NodeType type) {
        if (this.nodes.size() >= this.maxNodes) {
            this.refuse("circuit exceeds max-nodes (" + this.maxNodes + "), still growing at " + describe(pos));
            return;
        }

        final BlockPos immutable = pos.immutable();
        final Node node = new Node(immutable.asLong(), type);
        this.applyWorldState(node, immutable, state, type);

        this.indexByPos.put(node.pos, this.nodes.size());
        this.nodes.add(node);
        this.positions.add(immutable);
        this.links.add(new ArrayList<>());

        this.minX = Math.min(this.minX, immutable.getX());
        this.minY = Math.min(this.minY, immutable.getY());
        this.minZ = Math.min(this.minZ, immutable.getZ());
        this.maxX = Math.max(this.maxX, immutable.getX());
        this.maxY = Math.max(this.maxY, immutable.getY());
        this.maxZ = Math.max(this.maxZ, immutable.getZ());

        if (this.maxX - this.minX >= this.maxExtent
            || this.maxY - this.minY >= this.maxExtent
            || this.maxZ - this.minZ >= this.maxExtent) {
            this.refuse("circuit spans more than max-extent (" + this.maxExtent
                + ") blocks on an axis, reaching " + describe(immutable));
        }
    }

    /** Seeds a fresh node with the state the block currently has, so the graph starts in sync. */
    private void applyWorldState(final Node node, final BlockPos pos, final BlockState state, final NodeType type) {
        switch (type) {
            case WIRE -> node.strength = state.getValue(RedStoneWireBlock.POWER);
            case REPEATER -> {
                node.repeaterDelay = state.getValue(RepeaterBlock.DELAY);
                node.locked = state.getValue(RepeaterBlock.LOCKED);
                node.powered = state.getValue(DiodeBlock.POWERED);
                node.strength = node.powered ? SIGNAL_MAX : 0;
                node.facingDiode = this.facesDiode(pos, state);
            }
            case COMPARATOR -> {
                node.subtractMode = state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT;
                node.strength = this.level.getBlockEntity(pos) instanceof ComparatorBlockEntity comparator
                    ? comparator.getOutputSignal()
                    : 0;
                node.powered = node.strength > 0;
                node.facingDiode = this.facesDiode(pos, state);
            }
            case TORCH -> {
                node.powered = state.getValue(BlockStateProperties.LIT);
                node.strength = node.powered ? SIGNAL_MAX : 0;
            }
            case LAMP -> node.powered = state.getValue(BlockStateProperties.LIT);
            case LEVER, BUTTON -> {
                node.powered = state.getValue(BlockStateProperties.POWERED);
                node.strength = node.powered ? SIGNAL_MAX : 0;
            }
            case PRESSURE_PLATE -> {
                node.strength = state.getSignal(this.level, pos, Direction.UP);
                node.powered = node.strength > 0;
            }
            case CONSTANT -> {
                node.strength = SIGNAL_MAX;
                node.powered = true;
            }
            case PISTON -> node.powered = state.getValue(BlockStateProperties.EXTENDED);
            case GENERIC_OUTPUT -> node.powered = state.getValue(BlockStateProperties.POWERED);
        }
    }

    /** True when the diode's output points straight into another diode, which raises its priority. */
    private boolean facesDiode(final BlockPos pos, final BlockState state) {
        final BlockPos front = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING).getOpposite());
        return DiodeBlock.isDiode(this.level.getBlockState(front));
    }

    // --- pass 2: InputSearch --------------------------------------------------------------------

    private void inputSearch() {
        for (int i = 0; i < this.nodes.size() && this.refusal == null; i++) {
            final Node node = this.nodes.get(i);
            final BlockPos pos = this.positions.get(i);
            final BlockState state = this.level.getBlockState(pos);
            final List<Link> out = this.links.get(i);

            switch (node.type) {
                case WIRE -> {
                    this.dustSourcesMuted = true;
                    this.searchDust(out, pos, false, 0);
                    this.dustSourcesMuted = false;
                }
                case REPEATER -> {
                    final Direction back = state.getValue(HorizontalDirectionalBlock.FACING);
                    this.searchDiodeBack(out, pos, back);
                    // A repeater is only locked by another diode on its side.
                    this.searchControlInput(out, pos.relative(back.getClockWise()), back.getClockWise(), true);
                    this.searchControlInput(out, pos.relative(back.getCounterClockWise()), back.getCounterClockWise(), true);
                }
                case COMPARATOR -> {
                    final Direction back = state.getValue(HorizontalDirectionalBlock.FACING);
                    if (!this.bindContainer(i, pos, back)) {
                        this.searchDiodeBack(out, pos, back);
                    }
                    this.searchControlInput(out, pos.relative(back.getClockWise()), back.getClockWise(), false);
                    this.searchControlInput(out, pos.relative(back.getCounterClockWise()), back.getCounterClockWise(), false);
                }
                case TORCH -> {
                    final Direction toSupport = state.hasProperty(HorizontalDirectionalBlock.FACING)
                        ? state.getValue(HorizontalDirectionalBlock.FACING).getOpposite()
                        : Direction.DOWN;
                    this.searchSignal(out, pos.relative(toSupport), toSupport, false, 0);
                }
                case LAMP -> {
                    for (final Direction direction : DIRECTIONS) {
                        this.searchSignal(out, pos.relative(direction), direction, false, 0);
                    }
                }
                case PISTON -> this.searchPistonInputs(out, pos, state);
                case GENERIC_OUTPUT -> {
                    for (final Direction direction : DIRECTIONS) {
                        this.searchSignal(out, pos.relative(direction), direction, false, 0);
                    }
                }
                case LEVER, BUTTON, PRESSURE_PLATE, CONSTANT -> {
                    // Driven by the player or by entities, never by the graph.
                }
            }
        }
    }

    /**
     * A piston reads every face but the one it pushes towards, plus its own block, plus everything
     * touching the block above it — vanilla quasi-connectivity, kept because circuits rely on it.
     */
    private void searchPistonInputs(final List<Link> out, final BlockPos pos, final BlockState state) {
        final Direction push = state.getValue(HorizontalDirectionalBlock.FACING);
        for (final Direction direction : DIRECTIONS) {
            if (direction != push) {
                this.searchSignal(out, pos.relative(direction), direction, false, 0);
            }
        }
        this.searchThroughConductor(out, pos, false, 0);

        final BlockPos above = pos.above();
        for (final Direction direction : DIRECTIONS) {
            if (direction != Direction.DOWN) {
                this.searchSignal(out, above.relative(direction), direction, false, 0);
            }
        }
    }

    /**
     * The back input of a repeater or comparator: whatever powers the block behind it, plus the raw
     * strength of the dust behind it, which vanilla reads whether or not that dust points at the diode.
     */
    private void searchDiodeBack(final List<Link> out, final BlockPos pos, final Direction back) {
        this.searchSignal(out, pos.relative(back), back, false, 0);
    }

    /**
     * Records a comparator that reads a container, mirroring vanilla's rule that the container's
     * analog output <em>replaces</em> the back input rather than adding to it.
     *
     * @return true when a container drives this comparator's back input
     */
    private boolean bindContainer(final int nodeIndex, final BlockPos pos, final Direction back) {
        final BlockPos backPos = pos.relative(back);
        final BlockState backState = this.level.getBlockState(backPos);
        if (backState.hasAnalogOutputSignal()) {
            this.registerContainer(nodeIndex, backPos, backState, back);
            return true;
        }
        if (!backState.isRedstoneConductor(this.level, backPos)) {
            return false;
        }

        // Vanilla also looks one block further, through a solid block, for a container or an item
        // frame. An item frame is an entity the graph cannot follow, so that case is not compiled.
        final BlockPos farPos = backPos.relative(back);
        if (!this.level.getEntitiesOfClass(ItemFrame.class, new AABB(farPos)).isEmpty()) {
            this.refuse("an item frame at " + describe(farPos) + " drives the comparator at "
                + describe(pos) + "; entities cannot be compiled");
            return true;
        }
        final BlockState farState = this.level.getBlockState(farPos);
        if (farState.hasAnalogOutputSignal()) {
            this.registerContainer(nodeIndex, farPos, farState, back);
            return true;
        }
        return false;
    }

    private void registerContainer(final int nodeIndex, final BlockPos containerPos,
                                   final BlockState containerState, final Direction back) {
        this.nodes.get(nodeIndex).containerOverride =
            containerState.getAnalogOutputSignal(this.level, containerPos, back.getOpposite());
        this.containerNodes.add(nodeIndex);
        this.containerPositions.add(containerPos.asLong());
    }

    /**
     * Everything that can power a node through the block at {@code pos}, approached from
     * {@code from} — vanilla's {@code getSignal}, resolved into links instead of a number.
     */
    private void searchSignal(final List<Link> out, final BlockPos pos, final Direction from,
                              final boolean side, final int weight) {
        final BlockState state = this.level.getBlockState(pos);
        if (state.is(Blocks.REDSTONE_WIRE)) {
            this.searchDust(out, pos, side, weight);
            return;
        }

        final int index = this.nodeIndex(pos);
        if (index >= 0) {
            if (!this.muted(index) && this.nodes.get(index).type.producesOutput()
                && emits(state, this.level, pos, from, false)) {
                out.add(new Link(side, index, weight));
            }
            return;
        }

        if (state.isRedstoneConductor(this.level, pos)) {
            this.searchThroughConductor(out, pos, side, weight);
        }
    }

    /** Whatever strongly powers the solid block at {@code pos}, which in turn powers its neighbours. */
    private void searchThroughConductor(final List<Link> out, final BlockPos pos,
                                        final boolean side, final int weight) {
        for (final Direction direction : DIRECTIONS) {
            final BlockPos sourcePos = pos.relative(direction);
            final int index = this.nodeIndex(sourcePos);
            if (index < 0 || this.muted(index) || !this.nodes.get(index).type.producesOutput()) {
                continue;
            }
            final BlockState sourceState = this.level.getBlockState(sourcePos);
            if (emits(sourceState, this.level, sourcePos, direction, true)) {
                out.add(new Link(side, index, weight));
            }
        }
    }

    /**
     * The side input of a diode: vanilla reads it directly, never through a solid block, and a
     * repeater accepts only other diodes there.
     */
    private void searchControlInput(final List<Link> out, final BlockPos pos, final Direction from,
                                    final boolean diodesOnly) {
        final BlockState state = this.level.getBlockState(pos);
        final int index = this.nodeIndex(pos);
        if (index < 0) {
            return;
        }
        final NodeType type = this.nodes.get(index).type;

        if (diodesOnly) {
            if (type == NodeType.REPEATER || type == NodeType.COMPARATOR) {
                out.add(new Link(true, index, 0));
            }
            return;
        }

        // Dust and blocks of redstone are read unconditionally; anything else must actually emit
        // towards the diode.
        if (type == NodeType.WIRE || type == NodeType.CONSTANT
            || (type.producesOutput() && emits(state, this.level, pos, from, true))) {
            out.add(new Link(true, index, 0));
        }
    }

    /**
     * Walks the dust net starting at {@code start}, adding a link for every component that feeds it.
     *
     * <p>This is the pass that pays for itself: the whole traversal happens once, here, and each
     * link carries the number of dust blocks crossed as its weight. At runtime the dust is never
     * walked again — a consumer subtracts the weight from its source and is done.
     */
    private void searchDust(final List<Link> out, final BlockPos start, final boolean side, final int baseWeight) {
        final Map<Long, Integer> best = new HashMap<>();
        final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        best.put(start.asLong(), baseWeight);
        frontier.add(start.immutable());

        while (!frontier.isEmpty()) {
            final BlockPos pos = frontier.poll();
            final int weight = best.get(pos.asLong());
            if (weight >= SIGNAL_MAX) {
                // Beyond fifteen blocks of dust nothing can arrive: stop walking.
                continue;
            }

            for (final Direction direction : DIRECTIONS) {
                final BlockPos neighbour = pos.relative(direction);
                if (this.level.getBlockState(neighbour).is(Blocks.REDSTONE_WIRE)) {
                    continue; // reached by the dust step below, with one more of attenuation
                }
                this.searchSignal(out, neighbour, direction, side, weight);
            }

            for (final BlockPos next : this.connectedDust(pos)) {
                final long key = next.asLong();
                final Integer known = best.get(key);
                if (known == null || known > weight + 1) {
                    best.put(key, weight + 1);
                    frontier.add(next);
                }
            }
        }
    }

    /**
     * The dust blocks a dust block draws from: its horizontal neighbours, one step up when the
     * neighbouring block is solid and nothing covers this one, one step down when it is not — the
     * staircase rule from {@code RedstoneWireEvaluator#getIncomingWireSignal}.
     */
    private List<BlockPos> connectedDust(final BlockPos pos) {
        final List<BlockPos> found = new ArrayList<>(6);
        final BlockPos above = pos.above();
        final boolean covered = this.level.getBlockState(above).isRedstoneConductor(this.level, above);

        for (final Direction direction : Direction.Plane.HORIZONTAL) {
            final BlockPos neighbour = pos.relative(direction);
            if (this.level.getBlockState(neighbour).is(Blocks.REDSTONE_WIRE)) {
                found.add(neighbour);
            }
            final boolean solid = this.level.getBlockState(neighbour).isRedstoneConductor(this.level, neighbour);
            if (solid && !covered) {
                final BlockPos up = neighbour.above();
                if (this.level.getBlockState(up).is(Blocks.REDSTONE_WIRE)) {
                    found.add(up);
                }
            } else if (!solid) {
                final BlockPos down = neighbour.below();
                if (this.level.getBlockState(down).is(Blocks.REDSTONE_WIRE)) {
                    found.add(down);
                }
            }
        }
        return found;
    }

    // --- optimisations --------------------------------------------------------------------------

    /** Drops links no signal could ever survive: fifteen blocks of dust attenuate everything to zero. */
    private void clampWeights() {
        for (final List<Link> list : this.links) {
            list.removeIf(link -> link.weight() >= SIGNAL_MAX);
        }
    }

    /** Drops links from a fixed source that is already too weak to reach the far end. */
    private void constantFold() {
        for (final List<Link> list : this.links) {
            list.removeIf(link -> {
                final Node source = this.nodes.get(link.source());
                return source.type == NodeType.CONSTANT && source.strength - link.weight() <= 0;
            });
        }
    }

    /** Collapses parallel links to the same source, keeping only the strongest path. */
    private void dedupLinks() {
        for (int i = 0; i < this.links.size(); i++) {
            final Map<Integer, Link> strongest = new HashMap<>();
            for (final Link link : this.links.get(i)) {
                // Back and side inputs are read separately, so they must not collapse into each other.
                final int key = link.source() * 2 + (link.side() ? 1 : 0);
                final Link known = strongest.get(key);
                if (known == null || known.weight() > link.weight()) {
                    strongest.put(key, link);
                }
            }
            this.links.set(i, new ArrayList<>(strongest.values()));
        }
    }

    /**
     * Removes nodes that neither feed anything nor can be fed — dust cut off from every source, a
     * lamp nothing reaches, a block of redstone with no consumer. Such a node can never change, so
     * dropping it costs nothing and shrinks the graph the runtime walks.
     *
     * @return old index to new index, {@code -1} for a removed node
     */
    private int[] pruneOrphans() {
        final int before = this.nodes.size();
        final boolean[] drives = new boolean[before];
        for (final List<Link> list : this.links) {
            for (final Link link : list) {
                drives[link.source()] = true;
            }
        }

        final int[] remap = new int[before];
        final List<Node> keptNodes = new ArrayList<>(before);
        final List<BlockPos> keptPositions = new ArrayList<>(before);
        final List<List<Link>> keptLinks = new ArrayList<>(before);

        for (int i = 0; i < before; i++) {
            if (!drives[i] && this.links.get(i).isEmpty()) {
                remap[i] = -1;
                continue;
            }
            remap[i] = keptNodes.size();
            keptNodes.add(this.nodes.get(i));
            keptPositions.add(this.positions.get(i));
            keptLinks.add(this.links.get(i));
        }

        if (keptNodes.size() == before) {
            return remap;
        }

        this.nodes.clear();
        this.nodes.addAll(keptNodes);
        this.positions.clear();
        this.positions.addAll(keptPositions);
        this.links.clear();
        this.links.addAll(keptLinks);
        return remap;
    }

    // --- assembly -------------------------------------------------------------------------------

    private Compilation assemble(final int[] remap) {
        final int count = this.nodes.size();
        final List<List<Integer>> consumers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            consumers.add(new ArrayList<>());
        }

        for (int i = 0; i < count; i++) {
            final List<Link> list = this.links.get(i);
            final List<Link> resolved = new ArrayList<>(list.size());
            for (final Link link : list) {
                final int source = remap[link.source()];
                if (source < 0) {
                    continue;
                }
                resolved.add(new Link(link.side(), source, link.weight()));
                consumers.get(source).add(i);
            }
            this.nodes.get(i).inputs = resolved.toArray(new Link[0]);
        }

        for (int i = 0; i < count; i++) {
            final List<Integer> list = consumers.get(i);
            final int[] outputs = new int[list.size()];
            for (int j = 0; j < outputs.length; j++) {
                outputs[j] = list.get(j);
            }
            this.nodes.get(i).outputs = outputs;
        }

        // The box is grown by one so that it also covers the solid blocks transmitting power and the
        // blocks components are attached to: editing any of those must invalidate the graph too.
        final CompiledGraph graph = new CompiledGraph(this.nodes.toArray(new Node[0]),
            this.minX - 1, this.minY - 1, this.minZ - 1,
            this.maxX + 1, this.maxY + 1, this.maxZ + 1);

        final int[] boundNodes = new int[this.containerNodes.size()];
        final long[] boundPositions = new long[this.containerNodes.size()];
        int written = 0;
        for (int i = 0; i < boundNodes.length; i++) {
            final int remapped = remap[this.containerNodes.get(i)];
            if (remapped < 0) {
                continue;
            }
            boundNodes[written] = remapped;
            boundPositions[written] = this.containerPositions.get(i);
            written++;
        }

        return new Compilation(graph,
            java.util.Arrays.copyOf(boundNodes, written),
            java.util.Arrays.copyOf(boundPositions, written));
    }

    // --- block classification -------------------------------------------------------------------

    private int nodeIndex(final BlockPos pos) {
        return this.indexByPos.getOrDefault(pos.asLong(), -1);
    }

    /** True when {@link #dustSourcesMuted} hides this node, which it does for dust and dust only. */
    private boolean muted(final int index) {
        return this.dustSourcesMuted && this.nodes.get(index).type == NodeType.WIRE;
    }

    /**
     * True when this block is a redstone component the compiler can build a node from.
     *
     * <p>Used to pick a compilation origin. A neighbour update lands on plenty of bystanders — the
     * floor under a wire, the air beside a torch — and starting a flood fill there finds nothing at
     * all, which is how a live circuit produced 26 attempts and 26 refusals.
     */
    public static boolean isComponent(final BlockState state) {
        return classify(state) != null;
    }

    /** The node type for a block, or {@code null} when the block is not a component. */
    private static NodeType classify(final BlockState state) {
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return NodeType.WIRE;
        }
        if (state.is(Blocks.REPEATER)) {
            return NodeType.REPEATER;
        }
        if (state.is(Blocks.COMPARATOR)) {
            return NodeType.COMPARATOR;
        }
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return NodeType.TORCH;
        }
        if (state.is(Blocks.REDSTONE_LAMP)) {
            return NodeType.LAMP;
        }
        if (state.is(Blocks.LEVER)) {
            return NodeType.LEVER;
        }
        if (state.getBlock() instanceof ButtonBlock) {
            return NodeType.BUTTON;
        }
        if (state.getBlock() instanceof BasePressurePlateBlock) {
            return NodeType.PRESSURE_PLATE;
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return NodeType.CONSTANT;
        }
        if (state.getBlock() instanceof PistonBaseBlock) {
            return NodeType.PISTON;
        }
        return null;
    }

    /**
     * True for a block that takes part in redstone but whose behaviour the graph does not reproduce.
     * Meeting one aborts the compilation rather than producing a circuit that would run differently
     * from vanilla.
     */
    private static boolean isUnsupported(final BlockState state) {
        if (state.isSignalSource()) {
            return true; // observer, daylight detector, target, tripwire hook, sculk sensor...
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) {
            return true; // a piston mid-cycle: the circuit is not in a stable shape to read
        }
        // Anything else that reacts to power — doors, dispensers, hoppers, rails, note blocks, TNT —
        // would need its own write-back before it can be compiled.
        return state.hasProperty(BlockStateProperties.POWERED)
            || state.hasProperty(BlockStateProperties.ENABLED)
            || state.is(Blocks.TNT);
    }

    /**
     * Whether the block at {@code pos} emits towards a neighbour lying in direction {@code from},
     * ignoring whether it happens to be switched on right now.
     *
     * <p>Asking an energised copy of the state keeps every per-block rule — a torch not powering the
     * block below it, a repeater emitting only forwards, dust powering only what it points at — in
     * vanilla's hands instead of duplicating them here.
     *
     * @param strong {@code true} to ask for direct power, which is what travels through a solid block
     */
    private static boolean emits(final BlockState state, final ServerLevel level, final BlockPos pos,
                                 final Direction from, final boolean strong) {
        final BlockState energised = energised(state);
        return strong
            ? energised.getDirectSignal(level, pos, from) > 0
            : energised.getSignal(level, pos, from) > 0;
    }

    /** A copy of the state as if it were switched on, used to read topology rather than current values. */
    private static BlockState energised(final BlockState state) {
        BlockState result = state;
        if (result.hasProperty(BlockStateProperties.POWERED)) {
            result = result.setValue(BlockStateProperties.POWERED, true);
        }
        if (result.hasProperty(BlockStateProperties.LIT)) {
            result = result.setValue(BlockStateProperties.LIT, true);
        }
        if (result.hasProperty(RedStoneWireBlock.POWER)) {
            result = result.setValue(RedStoneWireBlock.POWER, SIGNAL_MAX);
        }
        return result;
    }
}
