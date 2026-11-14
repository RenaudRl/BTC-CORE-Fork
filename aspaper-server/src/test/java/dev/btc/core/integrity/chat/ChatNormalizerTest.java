package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatNormalizerTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    @DisplayName("accents, case and full-width letters are folded; digits and punctuation are kept")
    void foldsAccentsCaseAndWidth() {
        assertEquals("ete a noel, 100%", ChatNormalizer.normalize("Été à Noël,   100%").normalized());
        assertEquals("mc.fr", ChatNormalizer.normalize("ｍｃ．ｆｒ").normalized());
        assertEquals("strasse", ChatNormalizer.normalize("Straße").normalized());
    }

    @Test
    @DisplayName("stacked combining marks are counted on the raw text and gone from the normalized one")
    void zalgoCountedThenStripped() {
        ChatText text = ChatNormalizer.normalize("z" + cp(0x0335, 0x0321, 0x0348, 0x035A, 0x0301) + "ok");
        assertEquals(5, text.combiningMax());
        assertEquals("zok", text.normalized());
    }

    @Test
    @DisplayName("the Hangul fillers draw nothing: they are gone from the normalized text and from the words")
    void fillersAreRemoved() {
        for (int filler : new int[] {0x3164, 0x115F, 0x1160, 0xFFA0}) {
            ChatText text = ChatNormalizer.normalize("exa" + cp(filler) + "mple");
            assertEquals("example", text.normalized(), Integer.toHexString(filler));
            assertEquals(List.of("example"), text.wordForms().get(0), Integer.toHexString(filler));
        }
    }

    @Test
    @DisplayName("a Russian word stays Russian; a Latin word with a Cyrillic look-alike becomes Latin")
    void mixedScriptWordsAreFoldedTowardTheirMajority() {
        assertEquals("привет мир", ChatNormalizer.normalize("Привет МИР").normalized());
        assertEquals("hello", ChatNormalizer.normalize("h" + cp(0x0435) + "llo").normalized());
        assertEquals("дом", ChatNormalizer.normalize("д" + cp('o') + "м").normalized(), "a Latin o inside a Russian word");
    }

    @Test
    @DisplayName("pack glyphs and emoji stay in the normalized text")
    void glyphsSurvive() {
        String glyphs = cp(0xE000, 0xF0001, 0x1F600);
        assertTrue(ChatNormalizer.normalize("a " + glyphs + " b").normalized().contains(glyphs));
    }

    @Test
    @DisplayName("word forms: plain, leet-folded, and with spaced letters joined")
    void wordForms() {
        List<List<String>> forms = ChatNormalizer.normalize("p u t e h3llo").wordForms();
        assertTrue(forms.contains(List.of("p", "u", "t", "e", "h3llo")));
        assertTrue(forms.contains(List.of("p", "u", "t", "e", "hello")));
        assertTrue(forms.contains(List.of("pute", "hello")));
    }

    @Test
    @DisplayName("a digit-only word and a symbol-only word are not leet-folded")
    void leetNeedsALetter() {
        assertTrue(ChatNormalizer.normalize("100000 @ $").wordForms().stream().anyMatch(form -> form.contains("100000")));
        assertTrue(ChatNormalizer.normalize("1337").wordForms().stream().allMatch(form -> form.equals(List.of("1337"))));
    }

    @Test
    @DisplayName("capital letters are counted for caps, letters of any script included")
    void lettersAndCapitals() {
        ChatText text = ChatNormalizer.normalize("Привет ABC def 123");
        assertEquals(12, text.letters());
        assertEquals(4, text.upperCase());
    }

    @Test
    @DisplayName("a message longer than the bound is cut before any work is done")
    void longInputIsBounded() {
        ChatText text = ChatNormalizer.normalize("a".repeat(5000));
        assertEquals(ChatNormalizer.MAX_INPUT_CHARS, text.raw().length());
    }

    @Test
    @DisplayName("a compatibility form that expands (U+FDFA gives 18 characters) cannot inflate the normalized text")
    void expandingCharactersAreBounded() {
        String chatLength = Character.toString(0xFDFA).repeat(256);
        ChatText text = ChatNormalizer.normalize(chatLength);
        assertTrue(text.normalized().length() <= ChatNormalizer.MAX_NORMALIZED_CHARS,
            "normalized length " + text.normalized().length());
        int words = text.wordForms().stream().mapToInt(form -> form.stream().mapToInt(String::length).sum()).max().orElse(0);
        assertTrue(words <= ChatNormalizer.MAX_NORMALIZED_CHARS, "word form length " + words);
        assertTrue(text.normalized().length() > ChatNormalizer.MAX_NORMALIZED_CHARS / 2, "the cut keeps a real prefix");
    }

    @Test
    @DisplayName("a letter folded into two (sharp s) cannot inflate the normalized text either")
    void foldedLettersAreBounded() {
        ChatText text = ChatNormalizer.normalize("ß".repeat(ChatNormalizer.MAX_INPUT_CHARS));
        assertEquals(ChatNormalizer.MAX_NORMALIZED_CHARS, text.normalized().length());
    }

    @Test
    @DisplayName("the cut never splits a surrogate pair")
    void boundKeepsSurrogatePairsWhole() {
        // 1 + 56 * 18 = 1009 characters, then pairs: the 1024th character is the first half of a pair.
        String message = "a" + Character.toString(0xFDFA).repeat(56) + Character.toString(0x1F600).repeat(50);
        String normalized = ChatNormalizer.normalize(message).normalized();
        assertTrue(normalized.length() <= ChatNormalizer.MAX_NORMALIZED_CHARS);
        assertTrue(normalized.codePoints().noneMatch(cp -> Character.getType(cp) == Character.SURROGATE), "no lone surrogate left");
        assertTrue(normalized.contains(Character.toString(0x1F600)), "the pairs before the cut are kept");
    }
}
