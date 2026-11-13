package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityRuleTest {

    private final SimilarityRule rule = new SimilarityRule(config("").similarity());
    private final PlayerChatHistory history = new PlayerChatHistory();

    /** Says {@code message} at {@code at}; true when refused. A refused message is not remembered. */
    private boolean refused(String message, long at) {
        ChatText text = ChatNormalizer.normalize(message);
        if (rule.check(text, history, at).isPresent()) {
            return true;
        }
        rule.accepted(text, history, at);
        return false;
    }

    private static final List<String> NEAR_COPIES = List.of(
        "come and join my island for free diamonds",
        "come and join my island for free diamonds!",
        "come and join my island for free diamond",
        "come and join my island for free diamonds!!",
        "come and join my island for free diamonds.");

    @Test
    @DisplayName("the fifth near-identical message within the window is refused, the first four pass")
    void fifthSimilarRefused() {
        for (int i = 0; i < 4; i++) {
            assertFalse(refused(NEAR_COPIES.get(i), i * 1000L), "message " + (i + 1));
        }
        assertTrue(refused(NEAR_COPIES.get(4), 4000), "the 5th is the one refused");
    }

    @Test
    @DisplayName("messages older than the window are forgotten, the boundary included")
    void forgottenAfterWindow() {
        for (int i = 0; i < 4; i++) {
            refused(NEAR_COPIES.get(i), 0);
        }
        assertTrue(refused(NEAR_COPIES.get(4), 60_000), "60 s later the first four still count");
        assertFalse(refused(NEAR_COPIES.get(4), 61_000), "61 s later they no longer do");
    }

    @Test
    @DisplayName("different messages never add up")
    void differentMessagesPass() {
        List<String> varied = List.of("who wants to trade iron for gold", "the nether portal is at spawn",
            "anyone seen my dog around here", "selling a stack of cooked salmon",
            "how do I enable the minimap please", "the shop closes in five minutes",
            "thanks everyone for the help today", "my farm finally works again");
        for (int i = 0; i < varied.size(); i++) {
            assertFalse(refused(varied.get(i), i * 1000L), varied.get(i));
        }
    }

    @Test
    @DisplayName("short messages are never compared: 'gg' ten times is not a copy-paste flood")
    void shortMessagesAreNotCompared() {
        for (int i = 0; i < 10; i++) {
            assertFalse(refused("gg", i * 100L));
        }
    }

    @Test
    @DisplayName("the similarity of two texts is 1 minus their edit distance over the longer length")
    void similarityValue() {
        assertEquals(1.0, SimilarityRule.similarity("abcd", "abcd"));
        assertEquals(0.75, SimilarityRule.similarity("abcd", "abce"));
        assertEquals(0.0, SimilarityRule.similarity("abcd", "wxyz"));
        assertEquals(1.0, SimilarityRule.similarity("", ""));
    }

    @Test
    @DisplayName("a long text is compared by its head and by its tail, so one comparison stays bounded")
    void comparisonIsBounded() {
        String head = "a".repeat(SimilarityRule.MAX_COMPARED_CHARS);
        assertEquals(1.0, SimilarityRule.similarity(head + "x".repeat(500), head + "y".repeat(700)));
        assertEquals(0.0, SimilarityRule.similarity("b".repeat(SimilarityRule.MAX_COMPARED_CHARS + 50), head));
        assertEquals(1.0, SimilarityRule.similarity("x".repeat(400) + head, "y".repeat(700) + head), "the tails match");
    }

    @Test
    @DisplayName("a fresh opening on every message does not hide the same long ending: the fifth is refused")
    void differentPrefixSameLongEndingIsSimilar() {
        String ending = " come and join my island for free diamonds".repeat(8);
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 5; i++) {
            StringBuilder opening = new StringBuilder();
            while (opening.length() < 300) {
                opening.append((char) ('a' + random.nextInt(26)));
                if (random.nextInt(6) == 0) {
                    opening.append(' ');
                }
            }
            boolean refused = refused(opening + ending, i * 1000L);
            if (i < 4) {
                assertFalse(refused, "message " + (i + 1));
            } else {
                assertTrue(refused, "the 5th, alike by its ending, is refused");
            }
        }
    }
}
