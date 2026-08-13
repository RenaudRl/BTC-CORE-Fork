package dev.btc.core.redstone;

import dev.btc.core.redstone.compile.CompileResult;
import dev.btc.core.redstone.compile.GraphCompiler;
import dev.btc.core.redstone.graph.CompiledGraph;
import dev.btc.core.redstone.graph.GraphRuntime;
import dev.btc.core.redstone.graph.Node;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks that a compiled graph behaves like the circuit it was compiled from.
 *
 * <p>The graph runtime is proven by unit tests, and {@link GraphCompiler} is proven to <em>produce</em>
 * a graph from real blocks. Neither proves the graph is the <em>same circuit</em>: a misread topology
 * compiles perfectly and then runs something else. Only a differential run answers that.
 *
 * <h2>Why it runs alongside the world instead of replaying it</h2>
 *
 * <p>The obvious harness — snapshot, run compiled, restore, run vanilla, compare — cannot be made
 * honest here. Restoring block states does not restore vanilla's pending block ticks, so a clock put
 * back mid-cycle simply stops, and the second half of the comparison measures a dead circuit.
 *
 * <p>So the graph runs <em>in parallel</em> with the untouched world instead. The world keeps running
 * vanilla redstone, no zone is installed, nothing is written back. Each tick the graph is advanced by
 * one tick and every node is compared with the block it stands for. The world is the reference and it
 * is never disturbed, which also means a failed verification cannot corrupt a build.
 *
 * <h2>What a mismatch does and does not prove</h2>
 *
 * <p>World-driven inputs (levers, buttons, pressure plates) are read back into the graph every tick,
 * so a player flipping a lever is not a divergence. Everything else — wire strength, repeater and
 * comparator output, torch and lamp state — is the graph's own work, and a disagreement there is a
 * real defect in the compiler or the runtime.
 *
 * <p>One honest caveat: a free-running clock that starts one tick out of phase disagrees on almost
 * every tick while being perfectly correct. That is why the report gives the number of agreeing ticks
 * rather than a verdict, and why a circuit with settled inputs is the meaningful test.
 */
public final class RedstoneVerifier {

    /** Distinct positions named in the report before it stops listing them. */
    private static final int MAX_REPORTED_POSITIONS = 5;

    /**
     * Consecutive ticks of disagreement with a world that is standing still before the verdict stops
     * excusing it as a phase offset.
     *
     * <p>A phase offset is a <em>lag</em>: it exists only while the circuit is moving, and it closes
     * as soon as the world stops, because a graph one tick behind a still world catches up on its
     * next tick. One second of the world holding perfectly still while the graph keeps disagreeing is
     * therefore something a phase offset cannot produce, whatever the circuit did earlier in the run.
     */
    private static final int SETTLED_DIVERGENCE_TICKS = 20;

    private RedstoneVerifier() {
    }

    private static volatile ServerLevel level;
    private static CompiledGraph graph;
    private static GraphRuntime runtime;
    private static Consumer<String> reporter;

    private static int remaining;
    private static int totalTicks;
    private static int agreeingTicks;
    private static int divergentTicks;
    private static int reportedPositions;

    /**
     * Ticks on which the world's own copy of the circuit changed.
     *
     * <p>This is what separates a real topology defect from a phase offset. On a free-running clock a
     * graph started one tick out of step disagrees on most ticks while being perfectly correct, and it
     * still agrees now and then whenever the waveforms happen to coincide — so a partial agreement
     * rate proves nothing on its own. A circuit whose world copy never moves has no phase to be
     * offset by, and there a divergence is unambiguous.
     */
    private static int movingTicks;

    /** Current run of consecutive ticks where the world held still and the graph still disagreed. */
    private static int settledDivergentStreak;

    /**
     * Longest such run over the whole verification.
     *
     * <p>This is the measurement that turns the caveat above into a verdict. {@link #movingTicks}
     * only asks whether the world <em>ever</em> moved, which a single lever flip makes true for the
     * rest of the run — so a divergence that outlives the world's own motion was being excused by the
     * very event that revealed it.
     */
    private static int worstSettledDivergentStreak;
    private static long[] previousWorldState;

    /** True while this world is being verified, which suppresses compilation so nothing interferes. */
    public static boolean active(final ServerLevel candidate) {
        return level == candidate;
    }

    /**
     * Compiles the circuit at {@code origin} and starts comparing it with the world.
     *
     * @return false when a verification is already running, the circuit refuses to compile, or the
     *         world already holds compiled zones that would make the world an unfair reference
     */
    public static synchronized boolean start(final ServerLevel target, final BlockPos origin,
                                             final int ticks, final Consumer<String> sink) {
        if (level != null) {
            sink.accept("A verification is already running.");
            return false;
        }
        if (target.btcRedstoneCompiler.hasZones()) {
            sink.accept("This world already holds compiled zones; the world would not be a vanilla "
                + "reference. Wait for them to be released, or disable the compiler and retry.");
            return false;
        }

        final CompileResult result = GraphCompiler.compile(target, origin,
            dev.btc.core.config.BTCCoreConfig.redstoneCompilerMaxNodes,
            dev.btc.core.config.BTCCoreConfig.redstoneCompilerMaxExtent);
        if (!result.succeeded()) {
            sink.accept("REFUSED: " + result.refusal());
            return false;
        }

        graph = result.compilation().graph();
        runtime = new GraphRuntime(graph);
        reporter = sink;
        remaining = ticks;
        totalTicks = 0;
        agreeingTicks = 0;
        divergentTicks = 0;
        reportedPositions = 0;
        movingTicks = 0;
        settledDivergentStreak = 0;
        worstSettledDivergentStreak = 0;
        previousWorldState = new long[graph.size()];
        level = target;

        sink.accept("Verifying " + graph.size() + " node(s) against the live world for " + ticks
            + " ticks. The world is untouched; the graph runs beside it.");
        return true;
    }

    /**
     * Advances the comparison by one tick, at the head of the world tick.
     *
     * <p>Order matters: the graph and the world are compared <em>before</em> either moves, so both
     * sides are being read at the same point of the same tick — the same reasoning that puts
     * {@link RedstoneProfiler#endOfTick} here.
     */
    public static void step(final ServerLevel ticking) {
        if (level != ticking) {
            return;
        }

        feedInputs();
        compare();
        runtime.tick();

        totalTicks++;
        if (--remaining <= 0) {
            finish();
        }
    }

    /** Levers, buttons and pressure plates belong to the world; a player using one is not a defect. */
    private static void feedInputs() {
        final Node[] nodes = graph.nodes();
        for (int i = 0; i < nodes.length; i++) {
            final Node node = nodes[i];
            final BlockPos pos = BlockPos.of(node.pos);
            final int strength = RedstoneCompilerManager.inputStrength(
                node.type, level.getBlockState(pos), level, pos);
            if (strength >= 0) {
                runtime.setInput(i, strength);
            }
        }
    }

    /** Compares every node the graph owns with the block it stands for. */
    private static void compare() {
        boolean agreed = true;
        boolean moved = false;
        final Node[] nodes = graph.nodes();
        for (int i = 0; i < nodes.length; i++) {
            final Node node = nodes[i];
            final BlockPos pos = BlockPos.of(node.pos);
            final BlockState actual = level.getBlockState(pos);

            // Block states are interned singletons, so identity is a stable fingerprint and costs no
            // API surface that could drift upstream.
            final long fingerprint = System.identityHashCode(actual);
            if (previousWorldState[i] != fingerprint) {
                if (previousWorldState[i] != 0L) {
                    moved = true;
                }
                previousWorldState[i] = fingerprint;
            }

            final BlockState expected = RedstoneCompilerManager.applyToWorld(actual, node);
            if (expected == null || expected == actual) {
                // Either the world owns this block, or the two already agree.
                continue;
            }
            agreed = false;
            if (reportedPositions < MAX_REPORTED_POSITIONS) {
                reportedPositions++;
                reporter.accept(String.format("  tick %d: %s at %d,%d,%d — world %s, graph %s",
                    totalTicks, node.type, pos.getX(), pos.getY(), pos.getZ(),
                    describe(actual), describe(expected)));
            }
        }
        if (agreed) {
            agreeingTicks++;
        } else {
            divergentTicks++;
        }
        if (moved) {
            movingTicks++;
        }

        if (!moved && !agreed) {
            settledDivergentStreak++;
            worstSettledDivergentStreak = Math.max(worstSettledDivergentStreak, settledDivergentStreak);
        } else {
            settledDivergentStreak = 0;
        }
    }

    /** The properties the graph actually drives, so the report shows the disagreement, not the block. */
    private static String describe(final BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.RedStoneWireBlock.POWER)) {
            return "power=" + state.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) {
            return "powered=" + state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
            return "lit=" + state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
        }
        return state.toString();
    }

    private static void finish() {
        final Consumer<String> sink = reporter;
        final int agreed = agreeingTicks;
        final int diverged = divergentTicks;
        final int total = totalTicks;
        final int moving = movingTicks;
        final int settledDivergence = worstSettledDivergentStreak;
        final int nodes = graph.size();
        release();

        if (sink == null) {
            return;
        }
        sink.accept(String.format("Verification over %d tick(s) on %d node(s): %d agreed, %d diverged; "
            + "the world's own circuit changed on %d tick(s).", total, nodes, agreed, diverged, moving));

        if (diverged == 0) {
            sink.accept(moving == 0
                ? "PASS, but the circuit never moved: this proves the graph reads a settled circuit "
                    + "correctly, not that it runs one correctly. Re-run while it is switching."
                : "PASS: the compiled graph matched a moving world on every tick.");
            return;
        }

        // A divergence that outlives the world's own motion is not a phase offset: a graph running one
        // tick behind a world that has stopped catches up on its next tick. This is checked before the
        // moving-circuit excuse below, which would otherwise swallow exactly the case it must catch —
        // flipping a lever sets `moving` for the rest of the run.
        if (settledDivergence >= SETTLED_DIVERGENCE_TICKS) {
            sink.accept(String.format("FAIL: the graph kept disagreeing for %d consecutive tick(s) "
                + "while the world's circuit stood completely still. A phase offset closes as soon as "
                + "the world stops, so it cannot explain this: the compiled topology is wrong at the "
                + "positions listed above.", settledDivergence));
            return;
        }

        // A circuit whose world copy keeps changing has a phase to be offset by, and a graph one tick
        // out of step disagrees almost everywhere while being perfectly correct. Partial agreement
        // does not rule that out, so this case gets no verdict at all.
        if (moving > 0) {
            sink.accept("INCONCLUSIVE: the world's circuit was moving, so a one-tick phase offset "
                + "produces exactly this pattern and cannot be told apart from a misread topology "
                + "here. Settle the inputs, wait for the circuit to go quiet, and re-run: on a still "
                + "circuit any divergence is unambiguous.");
            return;
        }
        sink.accept("FAIL: the graph disagreed with a circuit that never moved, so no phase offset can "
            + "explain it. The compiled topology is wrong at the positions listed above.");
    }

    /** Stops a running verification. Used when the world unloads. */
    public static synchronized void abort() {
        release();
    }

    private static void release() {
        level = null;
        graph = null;
        runtime = null;
        reporter = null;
    }
}
