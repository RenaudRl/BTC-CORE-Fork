package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatJournalTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final ChatFinding FINDING = ChatFinding.of(ChatRuleId.ADVERTISING, "domain");
    private static final String SECRET = "my secret plan is monserveur.fr";

    private static String line(ChatJournal.Channel channel, boolean includeText, String text, int held) {
        return ChatJournal.line(0L, ID, "Alice", "lobby-1", FINDING, ChatJournal.Action.REFUSED,
            ChatGuardConfig.Mode.ENFORCE, channel, includeText, text, held);
    }

    @Test
    @DisplayName("a line names the player, the rule and the outcome, and never the message by default")
    void textIsNeverWrittenByDefault() {
        String line = line(ChatJournal.Channel.PUBLIC, false, SECRET, 0);
        assertEquals("ts=1970-01-01T00:00:00Z uuid=" + ID + " name=Alice server=lobby-1 rule=advertising "
            + "action=refused mode=enforce channel=public detail=domain", line);
        assertFalse(line.contains("secret"));
    }

    @Test
    @DisplayName("the public message is written only when the admin asks for it")
    void textWrittenOnRequestForPublicChat() {
        assertTrue(line(ChatJournal.Channel.PUBLIC, true, SECRET, 0).endsWith(" text=\"" + SECRET + "\""));
    }

    @Test
    @DisplayName("a private message is never written, whatever the setting")
    void whisperTextIsNeverWritten() {
        String line = line(ChatJournal.Channel.WHISPER, true, SECRET, 0);
        assertFalse(line.contains("secret"));
        assertFalse(line.contains("text="));
        assertTrue(line.contains("channel=whisper"));
    }

    @Test
    @DisplayName("a written message cannot add a key nor break the line")
    void textIsEscaped() {
        String line = line(ChatJournal.Channel.PUBLIC, true, "x\" rule=fake\nnew\\line", 0);
        assertFalse(line.contains("\n"));
        assertTrue(line.endsWith("text=\"x\\\" rule=fake new\\\\line\""), line);
    }

    @Test
    @DisplayName("a name that is not a plain word is made harmless, and held lines are counted")
    void nameIsSanitizedAndHeldLinesCounted() {
        String line = ChatJournal.line(0L, ID, "bad name=1", "", FINDING, ChatJournal.Action.OBSERVED,
            ChatGuardConfig.Mode.OBSERVE, ChatJournal.Channel.PUBLIC, false, null, 3);
        assertTrue(line.contains("name=bad?name?1 server=- "), line);
        assertTrue(line.endsWith(" held=3"), line);
        assertTrue(line.contains("action=observed"));
    }

    @Test
    @DisplayName("the details for the violation journal fit its column")
    void detailsFitTheColumn() {
        String details = ChatJournal.details(FINDING, ChatJournal.Action.REFUSED, ChatGuardConfig.Mode.ENFORCE,
            ChatJournal.Channel.WHISPER, 12);
        assertTrue(details.length() <= 255, details);
        assertEquals("rule=advertising action=refused mode=enforce channel=whisper detail=domain held=12", details);
    }
}
