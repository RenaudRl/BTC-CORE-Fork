package dev.btc.core.redstone.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Vanilla-semantics contract of the compiled redstone runtime.
 *
 * <p>These are the four reference circuits of the compiler's validation lot: a torch clock, a
 * repeater chain, a comparator in subtract mode, and lateral repeater locking. The graphs are built
 * by hand, exactly in the shape {@code GraphCompiler} emits, because the compiler itself needs a
 * loaded {@code ServerLevel} while the runtime does not depend on NMS at all.
 *
 * <p>Timings are asserted in whole game ticks, since that is the only thing an observer in-game can
 * actually compare between a compiled and an uncompiled circuit.
 */
class GraphRuntimeTest {

    // --- graph construction helpers ----------------------------------------------------------

    /** Builder that keeps node indices implicit, the way the compiler assigns them. */
    private static final class Circuit {

        private final List<Node> nodes = new ArrayList<>();
        private final List<List<Integer>> outputs = new ArrayList<>();

        int add(final NodeType type) {
            final int index = this.nodes.size();
            this.nodes.add(new Node(index, type));
            this.outputs.add(new ArrayList<>());
            return index;
        }

        Node node(final int index) {
            return this.nodes.get(index);
        }

        /** Wires {@code source -> target}, registering both the input link and the reverse edge. */
        void link(final int source, final int target, final boolean side, final int weight) {
            final Node node = this.nodes.get(target);
            final Link[] grown = new Link[node.inputs.length + 1];
            System.arraycopy(node.inputs, 0, grown, 0, node.inputs.length);
            grown[node.inputs.length] = new Link(side, source, weight);
            node.inputs = grown;
            this.outputs.get(source).add(target);
        }

        void link(final int source, final int target) {
            this.link(source, target, false, 0);
        }

        GraphRuntime build() {
            for (int i = 0; i < this.nodes.size(); i++) {
                final List<Integer> edges = this.outputs.get(i);
                final int[] packed = new int[edges.size()];
                for (int e = 0; e < edges.size(); e++) {
                    packed[e] = edges.get(e);
                }
                this.nodes.get(i).outputs = packed;
            }
            final Node[] array = this.nodes.toArray(new Node[0]);
            return new GraphRuntime(new CompiledGraph(array, 0, 0, 0, array.length, 1, 1));
        }
    }

    /** Advances the graph by {@code count} game ticks. */
    private static void run(final GraphRuntime runtime, final int count) {
        for (int i = 0; i < count; i++) {
            runtime.tick();
        }
    }

    // --- 1. torch clock ------------------------------------------------------------------------

    @Nested
    @DisplayName("Torch clock")
    class TorchClock {

        /**
         * Torch and repeater feeding each other. The torch inverts, so the loop can never settle:
         * one full cycle costs the repeater delay plus the torch's own tick, twice over.
         */
        private GraphRuntime clock(final int repeaterDelay) {
            final Circuit circuit = new Circuit();
            final int torch = circuit.add(NodeType.TORCH);
            final int repeater = circuit.add(NodeType.REPEATER);
            circuit.link(torch, repeater);
            circuit.link(repeater, torch);
            circuit.node(repeater).repeaterDelay = repeaterDelay;

            // A lit torch with nothing powering its attachment block is the consistent start state.
            circuit.node(torch).strength = 15;
            circuit.node(torch).powered = true;

            final GraphRuntime runtime = circuit.build();
            runtime.refresh(repeater); // the repeater still has to catch up with the torch
            return runtime;
        }

        @Test
        @DisplayName("never reaches quiescence")
        void neverSettles() {
            final GraphRuntime runtime = clock(2);
            for (int tick = 0; tick < 200; tick++) {
                runtime.tick();
                assertFalse(runtime.graph().isQuiescent(),
                        "a clock must always have a tick pending, none at tick " + tick);
            }
        }

        @Test
        @DisplayName("period is 2 x (repeater delay + 1) ticks")
        void periodFollowsRepeaterDelay() {
            for (int delay = 1; delay <= 4; delay++) {
                final GraphRuntime runtime = clock(delay);
                final Node torch = runtime.graph().nodes()[0];

                final List<Integer> edges = new ArrayList<>();
                boolean previous = torch.powered;
                for (int tick = 1; tick <= 100 && edges.size() < 3; tick++) {
                    runtime.tick();
                    if (torch.powered != previous) {
                        edges.add(tick);
                        previous = torch.powered;
                    }
                }

                assertEquals(3, edges.size(), "delay " + delay + ": expected the torch to toggle at least 3 times");
                final int expected = 2 * (delay + 1);
                assertEquals(expected, edges.get(2) - edges.get(0),
                        "delay " + delay + ": full period should span " + expected + " ticks");
            }
        }
    }

    // --- 2. repeater chain ---------------------------------------------------------------------

    @Nested
    @DisplayName("Repeater chain")
    class RepeaterChain {

        private GraphRuntime runtime;
        private int lever;
        private Node lamp;
        private Node[] repeaters;

        /** lever -> r1 -> r2 -> r3 -> lamp, every repeater on its shortest delay. */
        private void chain() {
            final Circuit circuit = new Circuit();
            this.lever = circuit.add(NodeType.LEVER);
            final int r1 = circuit.add(NodeType.REPEATER);
            final int r2 = circuit.add(NodeType.REPEATER);
            final int r3 = circuit.add(NodeType.REPEATER);
            final int lampIndex = circuit.add(NodeType.LAMP);

            circuit.link(this.lever, r1);
            circuit.link(r1, r2);
            circuit.link(r2, r3);
            circuit.link(r3, lampIndex);

            // r1 and r2 point straight into another diode, which is what raises their tick priority.
            circuit.node(r1).facingDiode = true;
            circuit.node(r2).facingDiode = true;

            this.runtime = circuit.build();
            final Node[] nodes = this.runtime.graph().nodes();
            this.repeaters = new Node[] {nodes[r1], nodes[r2], nodes[r3]};
            this.lamp = nodes[lampIndex];
        }

        @Test
        @DisplayName("signal advances exactly one repeater per tick")
        void propagatesOneStagePerTick() {
            chain();
            this.runtime.setInput(this.lever, 15);

            for (int stage = 0; stage < this.repeaters.length; stage++) {
                assertEquals(0, this.repeaters[stage].strength,
                        "repeater " + (stage + 1) + " must not fire before its turn");
                this.runtime.tick();
                assertEquals(15, this.repeaters[stage].strength,
                        "repeater " + (stage + 1) + " should power up on tick " + (stage + 1));
            }

            assertTrue(this.lamp.powered, "the lamp lights instantly once the last repeater fires");
        }

        @Test
        @DisplayName("lamp goes dark 2 ticks after the chain drains")
        void lampExtinguishesTwoTicksLate() {
            chain();
            this.runtime.setInput(this.lever, 15);
            run(this.runtime, 3);
            assertTrue(this.lamp.powered);

            this.runtime.setInput(this.lever, 0);
            run(this.runtime, 3); // the chain itself drains in 3 ticks
            assertTrue(this.lamp.powered, "a lamp does not go dark on the same tick it loses power");

            run(this.runtime, 2);
            assertFalse(this.lamp.powered, "the lamp should be dark 2 ticks after losing power");
        }

        @Test
        @DisplayName("settles once the signal has passed")
        void reachesQuiescence() {
            chain();
            this.runtime.setInput(this.lever, 15);
            run(this.runtime, 10);
            assertTrue(this.runtime.graph().isQuiescent(), "a static chain must stop scheduling ticks");
        }
    }

    // --- 3. comparator -------------------------------------------------------------------------

    @Nested
    @DisplayName("Comparator")
    class Comparator {

        private GraphRuntime runtime;
        private int back;
        private int sideSource;
        private Node comparator;

        private void comparator(final boolean subtract) {
            final Circuit circuit = new Circuit();
            this.back = circuit.add(NodeType.CONSTANT);
            this.sideSource = circuit.add(NodeType.CONSTANT);
            final int index = circuit.add(NodeType.COMPARATOR);

            circuit.link(this.back, index, false, 0);
            circuit.link(this.sideSource, index, true, 0);
            circuit.node(index).subtractMode = subtract;

            this.runtime = circuit.build();
            this.comparator = this.runtime.graph().nodes()[index];
        }

        /** Drives both inputs, then lets the comparator's 1-tick delay elapse. */
        private int outputFor(final int backValue, final int sideValue) {
            this.runtime.setInput(this.back, backValue);
            this.runtime.setInput(this.sideSource, sideValue);
            run(this.runtime, 2);
            return this.comparator.strength;
        }

        @Test
        @DisplayName("subtract mode removes the side input")
        void subtractsSideInput() {
            comparator(true);
            assertEquals(15, outputFor(15, 0));
            assertEquals(9, outputFor(15, 6));
            assertEquals(1, outputFor(15, 14));
            assertEquals(0, outputFor(15, 15));
        }

        @Test
        @DisplayName("subtract mode never goes below zero")
        void subtractClampsAtZero() {
            comparator(true);
            assertEquals(0, outputFor(4, 12));
        }

        @Test
        @DisplayName("compare mode passes through unless the side is stronger")
        void comparePassesThrough() {
            comparator(false);
            assertEquals(15, outputFor(15, 6));
            assertEquals(15, outputFor(15, 15), "an equal side input does not cut the signal");
            assertEquals(0, outputFor(10, 12), "a stronger side input cuts the signal");
        }

        @Test
        @DisplayName("a container override replaces the back input")
        void containerOverrideWinsOverBackInput() {
            comparator(true);
            this.comparator.containerOverride = 12;
            assertEquals(7, outputFor(0, 5), "the override stands in for the back input");
            assertEquals(12, outputFor(9, 0), "the override wins when it is the stronger of the two");
        }
    }

    // --- 4. lateral locking --------------------------------------------------------------------

    @Nested
    @DisplayName("Repeater locking")
    class RepeaterLocking {

        private GraphRuntime runtime;
        private int lever;
        private int lockLever;
        private Node main;

        /** lever -> main repeater, plus a second repeater driving the main one's side input. */
        private void lockedPair() {
            final Circuit circuit = new Circuit();
            this.lever = circuit.add(NodeType.LEVER);
            final int mainIndex = circuit.add(NodeType.REPEATER);
            this.lockLever = circuit.add(NodeType.LEVER);
            final int locker = circuit.add(NodeType.REPEATER);

            circuit.link(this.lever, mainIndex, false, 0);
            circuit.link(this.lockLever, locker, false, 0);
            circuit.link(locker, mainIndex, true, 0);

            this.runtime = circuit.build();
            this.main = this.runtime.graph().nodes()[mainIndex];
        }

        @Test
        @DisplayName("a locked repeater ignores its back input")
        void lockedRepeaterHoldsState() {
            lockedPair();

            this.runtime.setInput(this.lockLever, 15);
            run(this.runtime, 2); // let the locking repeater power up
            assertTrue(this.main.locked, "the side input should have latched the main repeater");

            this.runtime.setInput(this.lever, 15);
            run(this.runtime, 10);
            assertEquals(0, this.main.strength, "a locked repeater must not follow its back input");
        }

        @Test
        @DisplayName("releasing the lock lets the pending input through")
        void unlockingAppliesTheHeldInput() {
            lockedPair();

            this.runtime.setInput(this.lockLever, 15);
            run(this.runtime, 2);
            this.runtime.setInput(this.lever, 15);
            run(this.runtime, 5);
            assertEquals(0, this.main.strength);

            this.runtime.setInput(this.lockLever, 0);
            run(this.runtime, 5);
            assertFalse(this.main.locked, "the lock should be gone once the side input drops");
            assertEquals(15, this.main.strength, "the held back input applies as soon as the lock lifts");
        }

        @Test
        @DisplayName("an unlocked repeater is unaffected by a dead side input")
        void unlockedRepeaterBehavesNormally() {
            lockedPair();

            this.runtime.setInput(this.lever, 15);
            run(this.runtime, 2);
            assertFalse(this.main.locked);
            assertEquals(15, this.main.strength);
        }
    }
}
