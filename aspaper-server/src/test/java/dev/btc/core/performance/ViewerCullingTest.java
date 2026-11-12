package dev.btc.core.performance;

import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one player does not see, and for how long a scan is believed. */
class ViewerCullingTest {

    private static final int MOB = 41;
    private static final int OTHER = 42;
    private static final int TAKEN_AT = 1_000;
    private static final int MAX_AGE = 6;

    @Test
    @DisplayName("before any scan nothing is hidden")
    void nothingIsHiddenBeforeAScan() {
        assertFalse(new ViewerCulling().hides(MOB, 0, MAX_AGE));
    }

    @Test
    @DisplayName("the entities of the last scan are hidden until it is too old, the others are not")
    void aScanIsBelievedUntilItIsTooOld() {
        ViewerCulling view = new ViewerCulling();
        view.publish(IntSet.of(MOB), TAKEN_AT);

        assertTrue(view.hides(MOB, TAKEN_AT, MAX_AGE));
        assertTrue(view.hides(MOB, TAKEN_AT + MAX_AGE, MAX_AGE));
        assertFalse(view.hides(OTHER, TAKEN_AT, MAX_AGE));
        assertFalse(view.hides(MOB, TAKEN_AT + MAX_AGE + 1, MAX_AGE), "workers that run late: the entity is shown again");
    }

    @Test
    @DisplayName("a scan is dated by the tick it was taken at: published late, it is already old")
    void aScanPublishedLateIsOld() {
        ViewerCulling view = new ViewerCulling();
        assertTrue(view.claim());

        view.publish(IntSet.of(MOB), TAKEN_AT); // the rays ended long after the boxes were copied

        assertFalse(view.hides(MOB, TAKEN_AT + MAX_AGE + 1, MAX_AGE));
        assertTrue(view.claim(), "and the next scan may start");
    }

    @Test
    @DisplayName("one scan at a time, and a newer scan replaces the last one")
    void oneScanAtATime() {
        ViewerCulling view = new ViewerCulling();
        assertTrue(view.claim());
        assertFalse(view.claim());
        view.publish(IntSet.of(MOB), TAKEN_AT);

        view.publish(IntSet.of(OTHER), TAKEN_AT + MAX_AGE);

        assertFalse(view.hides(MOB, TAKEN_AT + MAX_AGE, MAX_AGE));
        assertTrue(view.hides(OTHER, TAKEN_AT + 2 * MAX_AGE, MAX_AGE));
    }
}
