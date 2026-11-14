package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.btc.core.integrity.chat.ChatTestKit.ALICE;
import static dev.btc.core.integrity.chat.ChatTestKit.enforcing;
import static dev.btc.core.integrity.chat.ChatTestKit.guard;
import static dev.btc.core.integrity.chat.ChatTestKit.withPlayersOnline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateRuleTest {

    private final ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { interval-ms: 2500, burst: 3 }\n")));

    private ChatVerdict say(String message, long at) {
        return guard.judge(ALICE, message, at, false);
    }

    @Test
    @DisplayName("burst messages pass at once, the next is refused until one interval has refilled")
    void burstPassesThenRefusesUntilRefill() {
        assertFalse(say("first message", 0).cancels());
        assertFalse(say("second message", 0).cancels());
        assertFalse(say("third message", 0).cancels());

        ChatVerdict fourth = say("fourth message", 1000);
        assertTrue(fourth.cancels(), "the 4th message within the second must be refused");
        assertEquals(ChatRuleId.RATE, fourth.refusal().orElseThrow().rule());
        assertEquals(1500, fourth.refusal().orElseThrow().retryAfterMillis(), "it says how long to wait");

        assertTrue(say("fifth message", 2499).cancels(), "one millisecond early is still refused");
        assertFalse(say("sixth message", 2500).cancels(), "one full interval later it passes");
    }

    @Test
    @DisplayName("a refused message costs nothing: waiting one interval after the last refusal is enough")
    void refusedMessageIsNotCharged() {
        for (int i = 0; i < 3; i++) {
            say("message number " + i, 0);
        }
        for (long at = 100; at < 2500; at += 400) {
            assertTrue(say("hurried message " + at, at).cancels());
        }
        assertFalse(say("patient message", 2500).cancels());
    }

    @Test
    @DisplayName("the account never holds more than burst messages, however long the player was silent")
    void creditIsCappedAtBurst() {
        say("only message", 0);
        long muchLater = 10_000_000L;
        assertFalse(say("later one", muchLater).cancels());
        assertFalse(say("later two", muchLater).cancels());
        assertFalse(say("later three", muchLater).cancels());
        assertTrue(say("later four", muchLater).cancels());
    }
}
