package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepetitionRuleTest {

    private final RepetitionRule rule = new RepetitionRule(config("").repetition());

    @Test
    @DisplayName("five of the same letter in a row are refused, four are allowed")
    void fiveSameLettersRefusedFourAllowed() {
        assertFalse(refuses(rule, "noooon"), "four o in a row is a drawn-out word, not spam");
        assertTrue(refuses(rule, "nooooon"));
        assertTrue(refuses(rule, "NOOOOON"), "case does not hide the run");
        assertTrue(refuses(rule, "wait!!!!!"), "punctuation counts as a character too");
    }

    @Test
    @DisplayName("digits never count: a number is not a repeated character")
    void digitsNeverCount() {
        assertFalse(refuses(rule, "100000"));
        assertFalse(refuses(rule, "I have 1000000000 coins"));
    }

    @Test
    @DisplayName("spaces and pack glyphs break a run and never form one")
    void spacesAndGlyphsNeverCount() {
        assertFalse(refuses(rule, "aaaa aaaa aaaa"), "a space ends a run of letters");
        assertFalse(refuses(rule, Character.toString(0xE000).repeat(7)), "a row of pack glyphs is not spam"); // built from the code point: a private-use character in the source would be stripped by tooling
    }

    @Test
    @DisplayName("the same word five times in a row is refused, four are allowed")
    void sameWordRepeated() {
        assertFalse(refuses(rule, "spam spam spam spam"));
        assertTrue(refuses(rule, "spam spam spam spam spam"));
        assertFalse(refuses(rule, "spam eggs spam eggs spam eggs"), "alternating words are not a run");
    }
}
