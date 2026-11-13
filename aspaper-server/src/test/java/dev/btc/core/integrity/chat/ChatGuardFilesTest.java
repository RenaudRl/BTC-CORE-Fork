package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGuardFilesTest {

    @TempDir
    Path folder;

    private void write(String relative, String text) throws IOException {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the shipped files are copied where they are missing, and an admin's file is never overwritten")
    void installDefaultsNeverOverwrites() throws IOException {
        write("chat-guard.yml", "mode: enforce\n");
        ChatGuardFiles.installDefaults(folder);

        assertEquals("mode: enforce\n", Files.readString(folder.resolve("chat-guard.yml")));
        assertTrue(Files.isRegularFile(folder.resolve("chat-guard/tlds.txt")));
        assertTrue(Files.isRegularFile(folder.resolve("chat-guard/words/pt-BR.txt")));
        assertTrue(Files.isRegularFile(folder.resolve("chat-guard/words/exceptions.txt")));
    }

    @Test
    @DisplayName("a fresh install loads as the shipped settings: observe, the shipped TLDs, empty lists, no warning")
    void freshInstallLoadsTheShippedSettings() throws IOException {
        ChatGuardFiles.installDefaults(folder);
        ChatGuardFiles.Loaded loaded = ChatGuardFiles.load(folder);

        assertEquals(ChatGuardConfig.Mode.OBSERVE, loaded.config().mode());
        assertEquals(ChatTestKit.shippedTlds(), loaded.tlds());
        assertEquals(0, loaded.words().entryCount());
        assertEquals(8, loaded.words().languages().size());
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
    }

    @Test
    @DisplayName("the configured lists are read from their configured place; a missing one and an unsafe prefix are warned")
    void configuredListsAreReadAndProblemsWarned() throws IOException {
        write("chat-guard.yml", "rules:\n  advertising: { tld-file: mine/tlds.txt }\n"
            + "  words: { mode: observe, directory: mine/words, languages: [fr, en] }\n");
        write("mine/tlds.txt", "# only one\nexample\n");
        write("mine/words/fr.txt", "crétin\nab*\n");
        write("mine/words/exceptions.txt", "cretine\n");

        ChatGuardFiles.Loaded loaded = ChatGuardFiles.load(folder);

        assertEquals(java.util.Set.of("example"), loaded.tlds());
        assertEquals(1, loaded.words().entryCount(), "ab* is too short a prefix to keep");
        assertEquals(2, loaded.warnings().size(), loaded.warnings().toString());
        assertTrue(loaded.warnings().get(0).contains("mine/words/en.txt"), loaded.warnings().get(0));
        assertTrue(loaded.warnings().get(1).contains("1 word list line"), loaded.warnings().get(1));
    }

    @Test
    @DisplayName("a missing TLD file falls back to the shipped list, so the advertising rule never goes blind")
    void missingTldFileFallsBackToTheShippedList() throws IOException {
        write("chat-guard.yml", "mode: observe\n");
        ChatGuardFiles.Loaded loaded = ChatGuardFiles.load(folder);

        assertEquals(ChatTestKit.shippedTlds(), loaded.tlds());
        assertTrue(loaded.warnings().stream().anyMatch(warning -> warning.contains("chat-guard/tlds.txt")));
    }

    @Test
    @DisplayName("a refused chat-guard.yml throws, so a reload keeps the settings it had")
    void refusedConfigThrows() throws IOException {
        write("chat-guard.yml", "mode: loud\n");
        assertThrows(IllegalArgumentException.class, () -> ChatGuardFiles.load(folder));
    }

    @Test
    @DisplayName("the jar's own settings stand alone: observe, the shipped TLDs, nothing refused by words")
    void shippedNeedsNoFolder() {
        ChatGuardFiles.Loaded shipped = ChatGuardFiles.shipped();
        assertEquals(ChatGuardConfig.Mode.OBSERVE, shipped.config().mode());
        assertEquals(ChatGuardConfig.Mode.OFF, shipped.config().modeOf(ChatRuleId.WORDS));
        assertEquals(ChatTestKit.shippedTlds(), shipped.tlds());
    }
}
