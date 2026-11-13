package dev.btc.core.integrity.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared builders of the chat guard tests: settings from YAML snippets, the files the server ships. */
final class ChatTestKit {

    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private ChatTestKit() {}

    /** Settings from a snippet of {@code chat-guard.yml}; every key left out keeps its default. */
    static ChatGuardConfig config(String yaml) {
        return ChatGuardConfig.fromYaml(yaml);
    }

    /** Every rule enforcing, the rest default: what the rule tests judge against. */
    static ChatGuardConfig enforcing(String rules) {
        return ChatGuardConfig.fromYaml("mode: enforce\nrules:\n" + rules);
    }

    static ChatGuard guard(ChatGuardConfig config) {
        return new ChatGuard(config, shippedTlds(), WordLists.empty());
    }

    static ChatGuard guard(ChatGuardConfig config, WordLists words) {
        return new ChatGuard(config, shippedTlds(), words);
    }

    static Set<String> shippedTlds() {
        return TldList.parse(shippedLines("tlds.txt"));
    }

    static String shippedConfigText() {
        return String.join("\n", shippedLines("chat-guard.yml"));
    }

    /** A resource next to {@link ChatGuard}, as the server jar ships it. */
    static List<String> shippedLines(String name) {
        return lines(ChatGuard.class.getResourceAsStream(name), name);
    }

    static List<String> lines(InputStream stream, String name) {
        if (stream == null) {
            throw new IllegalStateException("missing test resource " + name);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Judges one message of {@code ALICE} against a single rule, so a test names the rule it proves. */
    static boolean refuses(ChatRule rule, String message) {
        return rule.check(ChatNormalizer.normalize(message), new PlayerChatHistory(), 0L).isPresent();
    }
}
