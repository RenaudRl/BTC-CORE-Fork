package dev.btc.core.integrity.chat;

import dev.btc.core.text.PlayerText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.btc.core.integrity.chat.ChatTestKit.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessagesTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a refusal says why: every rule has its own text, and none shows a raw tag")
    void everyRefusalSaysWhy() {
        for (ChatRuleId rule : ChatRuleId.values()) {
            String text = plain(ChatMessages.refusal(ALICE, new ChatFinding(rule, 1500, "x"), Map.of()));
            assertFalse(text.isBlank(), rule + " shows nothing");
            assertFalse(text.contains("<"), rule + " leaves a tag in: " + text);
        }
    }

    @Test
    @DisplayName("the rate refusal tells how many whole seconds to wait, at least one")
    void rateRefusalCountsSeconds() {
        assertEquals("You are sending messages too fast. Wait 2s.",
            plain(ChatMessages.refusal(ALICE, new ChatFinding(ChatRuleId.RATE, 1500, "rate"), Map.of())));
        assertEquals("You are sending messages too fast. Wait 1s.",
            plain(ChatMessages.refusal(ALICE, new ChatFinding(ChatRuleId.RATE, 1, "rate"), Map.of())));
        assertEquals(1, ChatMessages.secondsToWait(0));
        assertEquals(3, ChatMessages.secondsToWait(2001));
    }

    @Test
    @DisplayName("the admin's wording from chat-guard.yml replaces the default")
    void adminWordingWins() {
        Map<String, String> reworded = Map.of(PlayerText.CHAT_REFUSED_CAPS.key(), "<gold>Calm down");
        ChatFinding finding = ChatFinding.of(ChatRuleId.CAPS, "capitals");
        assertEquals("Calm down", plain(ChatMessages.refusal(ALICE, finding, reworded)));
        assertEquals("Too many capital letters in your message.", plain(ChatMessages.refusal(ALICE, finding, Map.of())));
    }

    @Test
    @DisplayName("the staff alert names the player as text: a name holding markup stays text")
    void staffAlertIsPlainText() {
        String alert = plain(ChatMessages.staffAlert(ALICE, "<red>Mallory", ChatRuleId.RATE, 5, Map.of()));
        assertEquals("[ChatGuard] <red>Mallory tripped the chat filter 5 times (last rule: rate).", alert);
    }

    @Test
    @DisplayName("every refusal text has a key of its own, in the chat domain")
    void textsAreInTheChatDomain() {
        for (ChatRuleId rule : ChatRuleId.values()) {
            assertTrue(ChatMessages.textOf(rule).key().startsWith("btccore.chat.refused."), rule.toString());
        }
        assertTrue(PlayerText.CHAT_STAFF_ALERT.key().startsWith("btccore.chat."));
    }
}
