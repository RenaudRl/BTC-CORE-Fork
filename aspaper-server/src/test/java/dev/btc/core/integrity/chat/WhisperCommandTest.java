package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WhisperCommandTest {

    private static final Set<String> TARGETED = Set.of("msg", "tell", "w");
    private static final Set<String> REPLY = Set.of("r", "reply");

    private static Optional<String> textOf(String line) {
        return WhisperCommand.textOf(line, TARGETED, REPLY);
    }

    @Test
    @DisplayName("the text of a private message is what follows the target")
    void textAfterTheTarget() {
        assertEquals(Optional.of("hello there Bob"), textOf("/msg Alice hello there Bob"));
        assertEquals(Optional.of("hi"), textOf("tell Alice   hi  "));
        assertEquals(Optional.of("psst"), textOf("/W Alice psst"));
    }

    @Test
    @DisplayName("a reply has no target: the text is everything after the command")
    void replyHasNoTarget() {
        assertEquals(Optional.of("ok see you"), textOf("/r ok see you"));
        assertEquals(Optional.of("ok"), textOf("/reply ok"));
    }

    @Test
    @DisplayName("a namespaced command is the same command")
    void namespacedCommand() {
        assertEquals(Optional.of("hi"), textOf("/minecraft:msg Alice hi"));
    }

    @Test
    @DisplayName("nothing said, or not a private-message command, gives nothing to judge")
    void nothingToJudge() {
        assertEquals(Optional.empty(), textOf("/msg Alice"));
        assertEquals(Optional.empty(), textOf("/msg Alice    "));
        assertEquals(Optional.empty(), textOf("/msg"));
        assertEquals(Optional.empty(), textOf("/r"));
        assertEquals(Optional.empty(), textOf("/home spawn"));
        assertEquals(Optional.empty(), textOf("/me waves"));
        assertEquals(Optional.empty(), textOf(""));
    }
}
