package dev.btc.core.parallel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A quarantined world unloaded without a save is a way to duplicate items: the line that says so (task 5.1). */
class UnsavedUnloadLineTest {

    @Test
    @DisplayName("an Error anywhere in the causes is the reason, and its class is named")
    void anErrorInTheCausesIsTheReason() {
        Throwable failure = new IllegalStateException("tick", new StackOverflowError());

        String reason = WorldQuarantine.unsavedUnloadReason(true, failure);

        assertTrue(reason.contains("java.lang.StackOverflowError"), reason);
    }

    @Test
    @DisplayName("without an Error, the reason is the setting that turned the save off")
    void withoutAnErrorTheReasonIsTheSetting() {
        String reason = WorldQuarantine.unsavedUnloadReason(false, new IllegalStateException("tick"));

        assertTrue(reason.contains("world.quarantine.save-before-unload is false"), reason);
    }

    @Test
    @DisplayName("a cause chain that loops ends")
    void aLoopingCauseChainEnds() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertEquals("world.quarantine.save-before-unload is false", WorldQuarantine.unsavedUnloadReason(false, a));
    }

    @Test
    @DisplayName("the line names the world, the reason and the cost, with counts and no player")
    void theLineNamesTheWorldTheReasonAndTheCost() {
        String line = WorldQuarantine.unsavedUnloadLine("btc_skyblock_42", "the reason", 3, OptionalInt.of(5), OptionalLong.of(120));

        assertEquals("World btc_skyblock_42 is unloaded WITHOUT a save (the reason): it comes back as it was last saved "
            + "120 s ago, while 3 player(s) in it keep their inventories (5 exposed with those who left it since). "
            + "Items moved between the world and them since that save are duplicated or lost.", line);
    }

    @Test
    @DisplayName("what is not tracked is left out of the line, not invented")
    void whatIsNotTrackedIsLeftOut() {
        String line = WorldQuarantine.unsavedUnloadLine("w", "r", 0, OptionalInt.empty(), OptionalLong.empty());

        assertFalse(line.contains("exposed"), line);
        assertFalse(line.contains(" s ago"), line);
        assertTrue(line.contains("last saved, while 0 player(s)"), line);
    }
}
