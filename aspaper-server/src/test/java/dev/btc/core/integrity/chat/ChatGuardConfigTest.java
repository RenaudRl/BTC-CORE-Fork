package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGuardConfigTest {

    @Test
    @DisplayName("the defaults observe, enforce nothing, and leave the word lists off")
    void defaultsAreObserveAndWordsOff() {
        ChatGuardConfig defaults = ChatGuardConfig.defaults();
        assertEquals(ChatGuardConfig.Mode.OBSERVE, defaults.mode());
        assertEquals(ChatGuardConfig.RuleMode.OFF, defaults.words().mode());
        for (ChatRuleId rule : ChatRuleId.values()) {
            assertTrue(defaults.modeOf(rule) != ChatGuardConfig.Mode.ENFORCE, rule + " must not enforce by default");
        }
        assertEquals(ChatGuardConfig.Mode.OFF, defaults.modeOf(ChatRuleId.WORDS));
        assertEquals(ChatGuardConfig.Mode.OBSERVE, defaults.modeOf(ChatRuleId.RATE));
    }

    @Test
    @DisplayName("the defaults carry the values of the design: 2500 ms, burst 3, 8 repeats, 0.85, 60 percent, 5 findings per minute")
    void defaultValues() {
        ChatGuardConfig defaults = ChatGuardConfig.defaults();
        assertEquals(2500, defaults.rate().intervalMillis());
        assertEquals(3, defaults.rate().burst());
        assertEquals(8, defaults.repetition().maxRepeats());
        assertEquals("kwh?.!", defaults.repetition().ignoredCharacters());
        assertEquals(0.85, defaults.similarity().threshold());
        assertEquals(5, defaults.similarity().count());
        assertEquals(60, defaults.similarity().windowSeconds());
        assertEquals(5, defaults.similarity().history());
        assertEquals(0.60, defaults.caps().ratio());
        assertEquals(3, defaults.caps().minWords());
        assertEquals(2, defaults.unicode().maxCombiningPerChar());
        assertFalse(defaults.unicode().refuseMixedScripts());
        assertEquals(5, defaults.staffAlert().refusals());
        assertEquals(60, defaults.staffAlert().windowSeconds());
        assertFalse(defaults.journal().includeText(), "the journal never carries the text unless asked");
        assertEquals(90, defaults.journal().retentionDays());
        assertEquals("btc.chatguard.bypass", defaults.bypassPermission());
        assertTrue(defaults.whisperCommands().containsAll(java.util.List.of("msg", "tell", "w", "whisper", "m", "pm")));
        assertTrue(defaults.replyCommands().containsAll(java.util.List.of("r", "reply")));
        assertEquals(8, defaults.words().languages().size());
    }

    @Test
    @DisplayName("the file the server ships parses to exactly the defaults")
    void shippedFileIsTheDefaults() {
        assertEquals(ChatGuardConfig.defaults(), ChatGuardConfig.fromYaml(ChatTestKit.shippedConfigText()));
    }

    @Test
    @DisplayName("an empty file, or only comments, gives the defaults")
    void emptyDocument() {
        assertEquals(ChatGuardConfig.defaults(), ChatGuardConfig.fromYaml(""));
        assertEquals(ChatGuardConfig.defaults(), ChatGuardConfig.fromYaml("# nothing here\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "rules:\n  rate: { burst: 0 }\n",
        "rules:\n  rate: { burst: 51 }\n",
        "rules:\n  rate: { interval-ms: 10 }\n",
        "rules:\n  repetition: { max-repeats: 1 }\n",
        "rules:\n  similarity: { threshold: 1.5 }\n",
        "rules:\n  similarity: { threshold: 0.2 }\n",
        "rules:\n  similarity: { count: 1 }\n",
        "rules:\n  similarity: { count: 10, history: 5 }\n",
        "rules:\n  caps: { ratio: 0 }\n",
        "rules:\n  caps: { min-words: 0 }\n",
        "rules:\n  unicode: { max-combining-per-char: -1 }\n",
        "rules:\n  unicode: { allowed-private-use: [\"nonsense\"] }\n",
        "rules:\n  unicode: { allowed-private-use: [\"F8FF-E000\"] }\n",
        "rules:\n  unicode: { allowed-private-use: [\"E000-110000\"] }\n",
        "rules:\n  advertising: { min-label-length: 0 }\n",
        "rules:\n  words: { languages: [\"../../etc\"] }\n",
        "staff-alert: { refusals: 0 }\n",
        "journal: { min-interval-seconds: -1 }\n",
        "mode: maybe\n",
        "mode: inherit\n",
        "rules:\n  rate: { mode: sometimes }\n",
        "rules: [1, 2]\n",
        "rules:\n  rate: { burst: three }\n",
        "rules:\n  rate: { burst: 2.5 }\n",
        "whisper-commands: [\"msg bob\"]\n",
        "- just\n- a list\n",
    })
    @DisplayName("a value outside its bounds, or of the wrong type, refuses the whole file")
    void outOfRangeRejected(String yaml) {
        assertThrows(IllegalArgumentException.class, () -> ChatGuardConfig.fromYaml(yaml), yaml);
    }

    @Test
    @DisplayName("the refusal names the key and the bounds")
    void refusalNamesTheKey() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> config("rules:\n  rate: { burst: 99 }\n"));
        assertTrue(refused.getMessage().contains("rules.rate.burst"), refused.getMessage());
        assertTrue(refused.getMessage().contains("99"), refused.getMessage());
    }

    @Test
    @DisplayName("a reworded text is kept for a chat key, and any other key is refused")
    void messageOverrides() {
        ChatGuardConfig reworded = config("messages:\n  btccore.chat.refused.caps: \"<red>Please do not shout.\"\n");
        assertEquals("<red>Please do not shout.", reworded.messages().get("btccore.chat.refused.caps"));
        assertThrows(IllegalArgumentException.class, () -> config("messages:\n  btccore.chat.refused.typo: \"x\"\n"));
        assertThrows(IllegalArgumentException.class, () -> config("messages:\n  btccore.sanction.banned: \"x\"\n"));
        assertThrows(IllegalArgumentException.class, () -> config("messages:\n  btccore.chat.refused.caps: 5\n"));
    }

    @Test
    @DisplayName("the settings of a rule override the global mode only for that rule")
    void ruleModeResolution() {
        ChatGuardConfig mixed = config("mode: enforce\nrules:\n  caps: { mode: observe }\n  rate: { mode: off }\n");
        assertEquals(ChatGuardConfig.Mode.ENFORCE, mixed.modeOf(ChatRuleId.REPETITION));
        assertEquals(ChatGuardConfig.Mode.OBSERVE, mixed.modeOf(ChatRuleId.CAPS));
        assertEquals(ChatGuardConfig.Mode.OFF, mixed.modeOf(ChatRuleId.RATE));
        ChatGuardConfig off = config("mode: off\nrules:\n  caps: { mode: enforce }\n");
        assertEquals(ChatGuardConfig.Mode.OFF, off.modeOf(ChatRuleId.CAPS), "a module that is off switches every rule off");
    }
}
