package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapsRuleTest {

    private final CapsRule rule = new CapsRule(config("").caps());

    @Test
    @DisplayName("a short shout passes: fewer than min-letters letters are never judged")
    void shortShoutPasses() {
        assertFalse(refuses(rule, "SALUT"));
        assertFalse(refuses(rule, "GG WP"));
        assertFalse(refuses(rule, "SALUT 12345678 !!!!"), "digits are not letters");
    }

    @Test
    @DisplayName("more than 60 percent capitals on enough letters is refused; exactly 60 percent passes")
    void aboveRatioRefused() {
        assertTrue(refuses(rule, "ABCDEFGHIJKLMNabcdef"), "70 percent of 20 letters");
        assertFalse(refuses(rule, "ABCDEFGHIJKLabcdefgh"), "exactly 60 percent of 20 letters");
        assertTrue(refuses(rule, "ABCDEFGH"), "8 letters, all capitals: the minimum is reached");
        assertFalse(refuses(rule, "ABCDEFG"), "7 letters: one short of the minimum");
    }

    @Test
    @DisplayName("a script without capitals is never refused")
    void scriptWithoutCapitalsPasses() {
        assertFalse(refuses(rule, "こんにちは世界こんにちは世界"));
    }
}
