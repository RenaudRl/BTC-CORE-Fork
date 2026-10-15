package dev.btc.core.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One owner for the break-speed provider and the server-driven set (design 6.G.10, §10). */
class SingleOwnerTest {

    @Test
    @DisplayName("a free place is taken by whoever asks")
    void freePlaceIsTaken() {
        assertTrue(SingleOwner.accepts(null, false, "rpgcore"));
    }

    @Test
    @DisplayName("a second plugin is refused while the holder is enabled")
    void secondOwnerIsRefused() {
        assertFalse(SingleOwner.accepts("rpgcore", true, "intruder"));
    }

    @Test
    @DisplayName("the holder itself may bind again (a reload)")
    void holderMayRebind() {
        assertTrue(SingleOwner.accepts("rpgcore", true, "rpgcore"));
    }

    @Test
    @DisplayName("a disabled holder leaves the place to the next one")
    void disabledHolderIsReplaced() {
        assertTrue(SingleOwner.accepts("rpgcore", false, "successor"));
    }

    @Test
    @DisplayName("a null candidate is a bug of the caller")
    void nullCandidateThrows() {
        assertThrows(NullPointerException.class, () -> SingleOwner.accepts("rpgcore", true, null));
    }
}
