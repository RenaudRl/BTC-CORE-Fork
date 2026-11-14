package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepetitionRuleTest {

    private final RepetitionRule rule = new RepetitionRule(config("").repetition());

    @Test
    @DisplayName("eight of the same letter in a row are refused, seven are allowed")
    void eightSameLettersRefusedSevenAllowed() {
        assertFalse(refuses(rule, "nooooooon"), "seven o in a row is a drawn-out word, not spam");
        assertTrue(refuses(rule, "noooooooon"));
        assertTrue(refuses(rule, "NOOOOOOOON"), "case does not hide the run");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "kkkkk", "ahhhhh", "ha ha ha ha ha", "?????", "!!!!!", "wait!!!!!",
        "kkkkkkkkkkkk", "wwwwwwwwwwww", "hhhhhhhhhhhh", "????????????", "............", "!!!!!!!!!!!!",
    })
    @DisplayName("laughter, question marks, full stops and exclamation marks never count as a run")
    void laughterAndPunctuationNeverCount(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @Test
    @DisplayName("a row of standard emoji is not spam")
    void emojiNeverCount() {
        // Built from code points: the emoji are the point, and a literal one in a source file is easily mangled.
        assertFalse(refuses(rule, Character.toString(0x1F602).repeat(5)));
        assertFalse(refuses(rule, Character.toString(0x1F602).repeat(12)));
        assertFalse(refuses(rule, Character.toString(0x2764).repeat(12)), "a heart is an emoji too");
    }

    @Test
    @DisplayName("the ignored characters are a setting: an admin can narrow them")
    void ignoredCharactersAreConfigurable() {
        RepetitionRule strict = new RepetitionRule(
            config("rules:\n  repetition: { ignored-characters: \"\" }\n").repetition());
        assertTrue(refuses(strict, "wait!!!!!!!!"));
        assertTrue(refuses(strict, "kkkkkkkkkk"));
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
        assertFalse(refuses(rule, Character.toString(0xE000).repeat(12)), "a row of pack glyphs is not spam"); // built from the code point: a private-use character in the source would be stripped by tooling
    }

    @Test
    @DisplayName("the same word eight times in a row is refused, seven are allowed")
    void sameWordRepeated() {
        assertFalse(refuses(rule, "spam spam spam spam spam spam spam"));
        assertTrue(refuses(rule, "spam spam spam spam spam spam spam spam"));
        assertFalse(refuses(rule, "spam eggs spam eggs spam eggs"), "alternating words are not a run");
    }
}
