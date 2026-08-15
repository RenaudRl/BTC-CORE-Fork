package dev.btc.core.redstone.graph;

/**
 * One compiled redstone component.
 *
 * <p>Deliberately a mutable class of flat fields rather than a record: the runtime touches these
 * millions of times per second, and the whole gain of compiling comes from never allocating nor
 * reading the world during a tick.
 */
public final class Node {

    /** Packed block position, as produced by {@code BlockPos#asLong}. */
    public final long pos;

    public final NodeType type;

    /** Inputs, resolved at compile time. Never null; may be empty. */
    public Link[] inputs;

    /** Indices of the nodes to update when this node's output changes. Never null; may be empty. */
    public int[] outputs;

    // --- runtime state -------------------------------------------------------------------------

    /** Current emitted signal strength, 0-15. */
    public int strength;

    /** Current powered/lit flag, for the node types that expose one. */
    public boolean powered;

    // --- per-type data -------------------------------------------------------------------------

    /** Repeater delay in ticks (1-4); unused by other types. */
    public int repeaterDelay = 1;

    /** Repeater locked by a diode on its side. */
    public boolean locked;

    /** Comparator in subtract mode rather than compare mode. */
    public boolean subtractMode;

    /**
     * Signal strength contributed by a container read through a comparator, captured at compile
     * time and refreshed by the manager when the container's contents change.
     */
    public int containerOverride;

    /** True when this node points straight into another diode, which raises its tick priority. */
    public boolean facingDiode;

    public Node(final long pos, final NodeType type) {
        this.pos = pos;
        this.type = type;
        this.inputs = new Link[0];
        this.outputs = new int[0];
    }

    /** Highest signal strength arriving on the back/default inputs. */
    public int backInput(final Node[] nodes) {
        int max = 0;
        for (final Link link : this.inputs) {
            if (link.side()) {
                continue;
            }
            final int value = link.apply(nodes[link.source()].strength);
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    /** Highest signal strength arriving on the side inputs, used by comparators and repeater locking. */
    public int sideInput(final Node[] nodes) {
        int max = 0;
        for (final Link link : this.inputs) {
            if (!link.side()) {
                continue;
            }
            final int value = link.apply(nodes[link.source()].strength);
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * Applies a new output.
     *
     * @return true when the value actually moved, which is the caller's cue to record this node for
     *         write-back; {@link GraphRuntime} owns that list, so a node never carries a flag of its
     *         own that a full scan would then have to look for
     */
    public boolean setStrength(final int value) {
        if (this.strength == value) {
            return false;
        }
        this.strength = value;
        this.powered = value > 0;
        return true;
    }

    /** Applies a new powered flag. Same contract as {@link #setStrength(int)}. */
    public boolean setPowered(final boolean value) {
        if (this.powered == value) {
            return false;
        }
        this.powered = value;
        return true;
    }
}
