package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The immutable id set behind the published server-driven blocks (design 6.G.10, §9.4). */
class StateIdSetTest {

    @Test
    @DisplayName("the source bit set is copied: changing it afterwards changes nothing")
    void copiesTheSource() {
        final BitSet source = new BitSet();
        source.set(7);
        final StateIdSet set = StateIdSet.of(source);

        source.set(8);
        source.clear(7);

        assertTrue(set.contains(7));
        assertFalse(set.contains(8));
        assertEquals(1, set.size());
    }

    @Test
    @DisplayName("negative ids are never in the set, and are ignored when building it")
    void negativeIds() {
        final StateIdSet set = StateIdSet.of(-1, 3, -7, 3);

        assertEquals(1, set.size());
        assertTrue(set.contains(3));
        assertFalse(set.contains(-1));
    }

    @Test
    @DisplayName("an empty source gives the shared empty set")
    void emptyIsShared() {
        assertSame(StateIdSet.EMPTY, StateIdSet.of(new BitSet()));
        assertSame(StateIdSet.EMPTY, StateIdSet.of());
        assertTrue(StateIdSet.EMPTY.isEmpty());
        assertFalse(StateIdSet.EMPTY.contains(0));
    }

    @Test
    @DisplayName("an id far beyond the set's last bit is simply absent")
    void farIdIsAbsent() {
        assertFalse(StateIdSet.of(1).contains(1_000_000));
    }
}
