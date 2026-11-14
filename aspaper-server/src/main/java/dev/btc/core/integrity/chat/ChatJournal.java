package dev.btc.core.integrity.chat;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The text of one journal line, {@code key=value} separated by spaces. The line carries the rule and
 * the outcome, never a word of a private message; a public message is written only when the admin set
 * {@code journal.include-text}, and then escaped so it cannot add a key or break the line.
 */
public final class ChatJournal {

    /** Where the player said it. */
    public enum Channel { PUBLIC, WHISPER }

    /** What the guard did with the message. */
    public enum Action { REFUSED, OBSERVED }

    private ChatJournal() {}

    /**
     * @param text   the message, or {@code null} when none is available
     * @param held   lines held back by the journal interval since the previous line, written when positive
     */
    public static String line(long nowMillis, UUID player, String name, String server, ChatFinding finding,
                              Action action, ChatGuardConfig.Mode mode, Channel channel, boolean includeText,
                              @Nullable String text, int held) {
        StringBuilder line = new StringBuilder("ts=").append(Instant.ofEpochMilli(nowMillis))
            .append(" uuid=").append(player)
            .append(" name=").append(token(name))
            .append(" server=").append(token(server));
        line.append(' ').append(details(finding, action, mode, channel, held));
        if (includeText && channel == Channel.PUBLIC && text != null) {
            line.append(" text=\"").append(escape(text)).append('"');
        }
        return line.toString();
    }

    /** The same facts without the identity and the time, for the violation journal row, which has its own columns. */
    public static String details(ChatFinding finding, Action action, ChatGuardConfig.Mode mode, Channel channel, int held) {
        StringBuilder details = new StringBuilder("rule=").append(finding.rule().key())
            .append(" action=").append(action.name().toLowerCase(java.util.Locale.ROOT))
            .append(" mode=").append(mode.name().toLowerCase(java.util.Locale.ROOT))
            .append(" channel=").append(channel.name().toLowerCase(java.util.Locale.ROOT))
            .append(" detail=").append(token(finding.detail()));
        if (held > 0) {
            details.append(" held=").append(held);
        }
        return details.toString();
    }

    /** A value that must not contain a space or an equal sign. */
    private static String token(String value) {
        if (value.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> out.append(Character.isLetterOrDigit(cp) || cp == '_' || cp == '-' || cp == '.' ? new String(Character.toChars(cp)) : "?"));
        return out.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '"' || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else if (Character.isISOControl(cp) || isLineOrBidiControl(cp)) {
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /** Line and paragraph separators break a line in some readers; bidi embeddings and isolates reorder it on screen. */
    private static boolean isLineOrBidiControl(int cp) {
        return cp == 0x2028 || cp == 0x2029 || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }
}
