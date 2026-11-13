package dev.btc.core.integrity.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Extracts the text of a private message from a command line, so the guard judges what is said, not the target. */
public final class WhisperCommand {

    private WhisperCommand() {}

    /**
     * @param line     the command as typed, with or without the leading slash
     * @param targeted commands that take a player first ({@code /msg Bob hi}), lower case
     * @param reply    commands that take the text directly ({@code /r hi}), lower case
     * @return the text said, or empty when the line is not one of these commands or says nothing
     */
    public static Optional<String> textOf(String line, Set<String> targeted, Set<String> reply) {
        String stripped = line.strip();
        if (stripped.startsWith("/")) {
            stripped = stripped.substring(1).stripLeading();
        }
        int space = indexOfWhitespace(stripped);
        String name = (space < 0 ? stripped : stripped.substring(0, space)).toLowerCase(Locale.ROOT);
        // "minecraft:msg" is the same command as "msg".
        name = name.substring(name.lastIndexOf(':') + 1);
        if (space < 0) {
            return Optional.empty();
        }
        String rest = stripped.substring(space).strip();
        if (reply.contains(name)) {
            return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
        }
        if (!targeted.contains(name)) {
            return Optional.empty();
        }
        int afterTarget = indexOfWhitespace(rest);
        if (afterTarget < 0) {
            return Optional.empty();
        }
        String message = rest.substring(afterTarget).strip();
        return message.isEmpty() ? Optional.empty() : Optional.of(message);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
