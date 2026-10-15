package dev.btc.core.mining;

import java.util.BitSet;

/**
 * A set of registry ids (block states, items) that cannot change once built.
 *
 * <p>Read at every aim check of every player in the server-driven mode, from whatever thread ticks
 * that player, while the plugin that published it may publish a new one from any other thread. A
 * {@link BitSet} answers in constant time but is not safe to read while it is written; this class
 * copies it at construction and never writes it again, so a published instance is read without a
 * lock. Publishing a new set means building a new instance and swapping a reference.
 */
public final class StateIdSet {

    /** The empty set. */
    public static final StateIdSet EMPTY = new StateIdSet(new BitSet());

    private final BitSet ids;
    private final int size;

    private StateIdSet(final BitSet ids) {
        this.ids = ids;
        this.size = ids.cardinality();
    }

    /**
     * A set holding these ids.
     *
     * <p>The bit set is copied: the caller may keep using it.
     */
    public static StateIdSet of(final BitSet ids) {
        return ids.isEmpty() ? EMPTY : new StateIdSet((BitSet) ids.clone());
    }

    /** A set holding these ids; negative ids (an unregistered entry) are ignored. */
    public static StateIdSet of(final int... ids) {
        final BitSet bits = new BitSet();
        for (final int id : ids) {
            if (id >= 0) bits.set(id);
        }
        return bits.isEmpty() ? EMPTY : new StateIdSet(bits);
    }

    /** Whether the id is in the set; a negative id never is. */
    public boolean contains(final int id) {
        return id >= 0 && this.ids.get(id);
    }

    /** The number of ids. */
    public int size() {
        return this.size;
    }

    /** Whether the set holds nothing. */
    public boolean isEmpty() {
        return this.size == 0;
    }
}
