package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PistonShapeSlotsTest {

    @Test
    @DisplayName("the three offsets a piston head stands at are the three steps")
    void theThreeOffsetsAreSteps() {
        assertEquals(0, PistonShapeSlots.step(0.0F));
        assertEquals(1, PistonShapeSlots.step(0.5F));
        assertEquals(2, PistonShapeSlots.step(1.0F));
    }

    @Test
    @DisplayName("any other offset has no step: between two, past the last, before the first, not a number")
    void anyOtherOffsetHasNoStep() {
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(0.25F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(0.75F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(0.9999F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(1.5F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(-0.5F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(-1.0F));
        assertEquals(PistonShapeSlots.NO_STEP, PistonShapeSlots.step(Float.NaN));
    }

    @Test
    @DisplayName("six directions and three steps fill eighteen slots, each once")
    void everySlotIsUsedOnce() {
        Set<Integer> slots = new HashSet<>();
        for (int direction = 0; direction < PistonShapeSlots.DIRECTIONS; direction++) {
            for (int step = 0; step < PistonShapeSlots.STEPS; step++) {
                int slot = PistonShapeSlots.slot(direction, step);
                assertTrue(slot >= 0 && slot < PistonShapeSlots.SLOTS, "slot " + slot);
                slots.add(slot);
            }
        }
        assertEquals(18, slots.size());
    }
}
