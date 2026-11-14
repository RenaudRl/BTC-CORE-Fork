package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.btc.core.integrity.chat.ChatTestKit.ALICE;
import static dev.btc.core.integrity.chat.ChatTestKit.BOB;
import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.enforcing;
import static dev.btc.core.integrity.chat.ChatTestKit.guard;
import static dev.btc.core.integrity.chat.ChatTestKit.withPlayersOnline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGuardTest {

    private static final String SHOUT = "THIS IS A VERY LOUD MESSAGE";

    @Test
    @DisplayName("a player with the bypass permission is not judged, and nothing is remembered about them")
    void bypassSkipsEverything() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("")));
        for (int i = 0; i < 20; i++) {
            ChatVerdict verdict = guard.judge(ALICE, SHOUT, 0L, true);
            assertEquals(ChatVerdict.CLEAN, verdict);
        }
        assertEquals(ChatVerdict.CLEAN, guard.judge(ALICE, "a calm first message", 0L, false),
            "twenty unfiltered messages used no rate credit");
        assertTrue(guard.judge(ALICE, SHOUT, 0L, false).cancels(), "the same message from a player who is judged");
    }

    @Test
    @DisplayName("in observe mode a finding is reported and the message is never cancelled")
    void observeNeverCancels() {
        ChatGuard guard = withPlayersOnline(guard(config("mode: observe\n")));
        for (int i = 0; i < 6; i++) {
            ChatVerdict verdict = guard.judge(ALICE, SHOUT, 0L, false);
            assertFalse(verdict.cancels(), "message " + i);
            assertTrue(verdict.observed().stream().anyMatch(finding -> finding.rule() == ChatRuleId.CAPS));
        }
        assertTrue(guard.judge(ALICE, SHOUT, 0L, false).observed().stream()
            .anyMatch(finding -> finding.rule() == ChatRuleId.RATE), "past the burst the rate rule observes too");
    }

    @Test
    @DisplayName("when a message breaks several rules, the cheapest rule is the one that refuses")
    void cheapRuleWinsFirst() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { burst: 1 }\n")));
        assertEquals(ChatRuleId.CAPS, guard.judge(ALICE, SHOUT, 0L, false).refusal().orElseThrow().rule(),
            "alone, the shout is refused for its capitals");
        guard.judge(ALICE, "a calm first message", 0L, false);
        ChatVerdict both = guard.judge(ALICE, SHOUT, 10L, false);
        assertEquals(ChatRuleId.RATE, both.refusal().orElseThrow().rule(), "rate is cheaper than caps");
        assertTrue(both.observed().isEmpty());
    }

    @Test
    @DisplayName("each player has their own account: one player's flood does not slow another")
    void historyIsPerPlayer() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { burst: 1 }\n")));
        assertFalse(guard.judge(ALICE, "alice speaks", 0L, false).cancels());
        assertTrue(guard.judge(ALICE, "alice again", 1L, false).cancels());
        assertFalse(guard.judge(BOB, "bob speaks", 2L, false).cancels());
        assertEquals(2, guard.tracked());
    }

    @Test
    @DisplayName("a rule's own mode beats the global one, and a rule that is off is silent")
    void ruleModeOverridesGlobal() {
        ChatGuard guard = withPlayersOnline(guard(config("mode: observe\nrules:\n  caps: { mode: enforce }\n  rate: { mode: off }\n")));
        for (int i = 0; i < 6; i++) {
            assertFalse(guard.judge(ALICE, "calm message number " + i, i, false).cancels(), "rate is off");
        }
        ChatVerdict shout = guard.judge(ALICE, SHOUT, 10L, false);
        assertEquals(ChatRuleId.CAPS, shout.refusal().orElseThrow().rule());
    }

    @Test
    @DisplayName("with the module off nothing is judged, whatever the rules say")
    void globalOffJudgesNothing() {
        ChatGuard guard = withPlayersOnline(guard(config("mode: off\nrules:\n  caps: { mode: enforce }\n")));
        assertEquals(ChatVerdict.CLEAN, guard.judge(ALICE, SHOUT, 0L, false));
    }

    @Test
    @DisplayName("a refused message is not charged: it does not use up rate credit nor feed the similarity memory")
    void refusedMessageLeavesNoTrace() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { burst: 2 }\n")));
        for (int i = 0; i < 10; i++) {
            assertTrue(guard.judge(ALICE, SHOUT, 0L, false).cancels());
        }
        assertFalse(guard.judge(ALICE, "first calm message", 0L, false).cancels());
        assertFalse(guard.judge(ALICE, "second calm message", 0L, false).cancels());
    }

    @Test
    @DisplayName("staff are alerted once when a player trips the filter five times in a minute, then after the cooldown")
    void staffAlertAtFifthFinding() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("")));
        for (int i = 1; i <= 4; i++) {
            assertTrue(guard.judge(ALICE, SHOUT, i * 1000L, false).staffAlertCount().isEmpty(), "finding " + i);
        }
        ChatVerdict fifth = guard.judge(ALICE, SHOUT, 5000L, false);
        assertEquals(5, fifth.staffAlertCount().orElseThrow());
        assertTrue(guard.judge(ALICE, SHOUT, 6000L, false).staffAlertCount().isEmpty(), "inside the cooldown");
        assertTrue(guard.judge(ALICE, SHOUT, 66_000L, false).staffAlertCount().isEmpty(),
            "a minute later the earlier findings left the window");
        for (int i = 1; i <= 3; i++) {
            assertTrue(guard.judge(ALICE, SHOUT, 66_000L + i * 1000L, false).staffAlertCount().isEmpty());
        }
        assertEquals(5, guard.judge(ALICE, SHOUT, 70_000L, false).staffAlertCount().orElseThrow(),
            "alerted again once the cooldown has passed and five findings are inside the window");
    }

    @Test
    @DisplayName("the journal writes one line per interval and counts the ones it held back")
    void journalIsThrottledPerPlayer() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("journal:\n  min-interval-seconds: 5\n")));
        assertEquals(0, guard.judge(ALICE, SHOUT, 0L, false).journalHeld(), "the first one is written");
        assertFalse(guard.judge(ALICE, SHOUT, 1000L, false).journals());
        assertFalse(guard.judge(ALICE, SHOUT, 2000L, false).journals());
        ChatVerdict later = guard.judge(ALICE, SHOUT, 5000L, false);
        assertTrue(later.journals());
        assertEquals(2, later.journalHeld(), "it says how many were held back");
        assertEquals(0, guard.judge(BOB, SHOUT, 1000L, false).journalHeld(), "another player has their own interval");
    }

    @Test
    @DisplayName("a clean message is neither journalled nor counted toward an alert")
    void cleanMessageLeavesNoLine() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("")));
        ChatVerdict verdict = guard.judge(ALICE, "a perfectly normal sentence", 0L, false);
        assertEquals(ChatVerdict.CLEAN, verdict);
        assertFalse(verdict.journals());
    }

    @Test
    @DisplayName("forget drops a player's history; reload keeps it and applies the new rules at once")
    void forgetAndReload() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { burst: 1 }\n")));
        guard.judge(ALICE, "first message", 0L, false);
        assertTrue(guard.judge(ALICE, "second message", 1L, false).cancels());
        guard.forget(ALICE);
        assertEquals(1, guard.tracked(), "only Bob is left");
        assertEquals(ChatVerdict.CLEAN, guard.judge(ALICE, SHOUT, 2L, false),
            "a late message of a player who left is not judged");
        assertEquals(1, guard.tracked(), "and does not bring their history back");
        guard.track(ALICE);
        assertFalse(guard.judge(ALICE, "third message", 2L, false).cancels(), "a returning player starts fresh");

        assertTrue(guard.judge(ALICE, "fourth message", 3L, false).cancels());
        guard.reload(enforcing("  rate: { mode: off }\n"), ChatTestKit.shippedTlds(), WordLists.empty());
        assertFalse(guard.judge(ALICE, "fifth message", 4L, false).cancels());
        assertEquals(2, guard.tracked());
    }

    @Test
    @DisplayName("explain tells what each rule thinks, without charging the message")
    void explainDoesNotRemember() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("  rate: { burst: 1 }\n")));
        for (int i = 0; i < 5; i++) {
            guard.explain(ALICE, "hello there", 0L);
        }
        assertFalse(guard.judge(ALICE, "hello there", 0L, false).cancels(), "five explanations used no credit");

        List<ChatGuard.Opinion> opinions = guard.explain(ALICE, SHOUT, 1L);
        assertEquals(ChatRuleId.values().length, opinions.size());
        Map<ChatRuleId, ChatGuard.Opinion> byRule = new java.util.EnumMap<>(ChatRuleId.class);
        opinions.forEach(opinion -> byRule.put(opinion.rule(), opinion));
        assertTrue(byRule.get(ChatRuleId.CAPS).finding().isPresent());
        assertTrue(byRule.get(ChatRuleId.RATE).finding().isPresent(), "the account is empty after one message");
        assertEquals(ChatGuardConfig.Mode.OFF, byRule.get(ChatRuleId.WORDS).mode());
    }
}
