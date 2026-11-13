package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Neutral stand-in words: the rule is proven on the mechanics, the shipped lists are empty. */
class WordListRuleTest {

    private static WordListRule rule(Map<String, List<String>> lines, List<String> exceptions) {
        return new WordListRule(WordLists.of(lines, exceptions));
    }

    private final WordListRule rule = rule(Map.of(
        "en", List.of("# comment", "", "ass", "cunt", "son of a"),
        "fr", List.of("pute", "merde", "salop*", "va te faire")), List.of("salopette"));

    @Test
    @DisplayName("only a whole word is refused: a word that contains an entry passes (the Scunthorpe case)")
    void wholeWordOnly() {
        assertTrue(refuses(rule, "you ass"));
        assertTrue(refuses(rule, "Ass!"));
        assertFalse(refuses(rule, "a class of assassins from Scunthorpe in the bassin"));
        assertFalse(refuses(rule, "je dois disputer ce match, ma reputation est en jeu"));
    }

    @Test
    @DisplayName("an exception wins over a prefix entry")
    void exceptionWins() {
        assertTrue(refuses(rule, "quel salopard"));
        assertFalse(refuses(rule, "elle porte une salopette"));
    }

    @Test
    @DisplayName("digits and symbols standing for letters, accents, case and look-alikes are normalized")
    void leetNormalized() {
        assertTrue(refuses(rule, "put3"));
        assertTrue(refuses(rule, "m3rd3"));
        assertTrue(refuses(rule, "PUTE"));
        assertTrue(refuses(rule, "pûte"));
        assertTrue(refuses(rule, "put" + new String(Character.toChars(0x0435))), "a Cyrillic e at the end of a Latin word");
        assertTrue(refuses(rule, new String(Character.toChars(0x0440)) + "ute"), "a Cyrillic r in front of Latin letters");
    }

    @Test
    @DisplayName("letters spaced or dotted out are read as one word")
    void spacedLettersAreJoined() {
        assertTrue(refuses(rule, "p u t e"));
        assertTrue(refuses(rule, "p.u.t.e"));
        assertTrue(refuses(rule, "p-u-t-e"));
        assertFalse(refuses(rule, "plan a b then c"), "two or three stray letters are not a word");
    }

    @Test
    @DisplayName("a trailing star makes a prefix; a prefix too short to be safe is ignored")
    void prefixEntry() {
        assertTrue(refuses(rule, "salopard"));
        assertFalse(refuses(rule, "sal"));
        WordLists lists = WordLists.of(Map.of("en", List.of("ab*", "ok")), List.of());
        assertEquals(1, lists.ignoredLines(), "the two-letter prefix is ignored and counted");
        assertEquals(1, lists.entryCount());
        assertFalse(refuses(new WordListRule(lists), "about abacus"));
    }

    @Test
    @DisplayName("an entry with several words matches those words next to each other")
    void phraseEntry() {
        assertTrue(refuses(rule, "va te faire voir"));
        assertTrue(refuses(rule, "oh va, te, faire"), "punctuation between the words does not hide a phrase");
        assertFalse(refuses(rule, "je va te dire quand faire"));
        assertTrue(refuses(rule, "you son of a gun"));
    }

    @Test
    @DisplayName("every language applies to every message, and the finding names the language not the word")
    void allLanguagesApply() {
        ChatText text = ChatNormalizer.normalize("quelle merde");
        assertEquals("fr", rule.check(text, new PlayerChatHistory(), 0L).orElseThrow().detail());
        assertTrue(refuses(rule, "what an ass, quelle merde"));
    }

    @Test
    @DisplayName("an empty list refuses nothing")
    void emptyListsRefuseNothing() {
        assertFalse(refuses(new WordListRule(WordLists.empty()), "anything at all"));
    }
}
