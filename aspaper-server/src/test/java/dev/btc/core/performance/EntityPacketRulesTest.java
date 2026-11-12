package dev.btc.core.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPacketRulesTest {

    @Test
    @DisplayName("only a relative move with no delta and no rotation is empty")
    void emptyMoveIsARelativeMoveThatChangesNothing() {
        assertTrue(EntityPacketRules.emptyMove(true, false, 0, 0, 0));

        assertFalse(EntityPacketRules.emptyMove(false, false, 0, 0, 0), "a full position sync is always sent");
        assertFalse(EntityPacketRules.emptyMove(true, true, 0, 0, 0), "a rotation is always sent");
        assertFalse(EntityPacketRules.emptyMove(true, false, 1, 0, 0));
        assertFalse(EntityPacketRules.emptyMove(true, false, 0, -1, 0));
        assertFalse(EntityPacketRules.emptyMove(true, false, 0, 0, 1));
    }

    @Test
    @DisplayName("the velocity of a mob that just walks is not sent")
    void walkingMobIsFiltered() {
        assertFalse(EntityPacketRules.keepsMotion(false, false, false, false, true));
    }

    @Test
    @DisplayName("an explicit change, a glide and a listed type are always sent")
    void explicitChangeGlideAndListedTypeAreSent() {
        assertTrue(EntityPacketRules.keepsMotion(true, false, false, false, false));
        assertTrue(EntityPacketRules.keepsMotion(false, true, false, false, false));
        assertTrue(EntityPacketRules.keepsMotion(false, false, true, false, false));
    }

    @Test
    @DisplayName("a dropped item is sent while it moves, not once it rests")
    void itemIsSentOnlyWhileItMoves() {
        assertTrue(EntityPacketRules.keepsMotion(false, false, false, true, true));
        assertFalse(EntityPacketRules.keepsMotion(false, false, false, true, false));
    }
}
