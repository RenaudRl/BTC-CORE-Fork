package dev.btc.core.integrity.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The files of the chat guard in the plugin folder: {@code chat-guard.yml}, the TLD file and the word
 * lists. The server jar ships a default of each, copied when missing and never over an admin's file.
 *
 * <p>Does file I/O: call it at start-up or from an asynchronous task, never on a game thread.
 */
public final class ChatGuardFiles {

    public static final String CONFIG_FILE = "chat-guard.yml";

    private static final String EXCEPTIONS_FILE = "exceptions.txt";

    /** The word lists the jar ships (empty, comments only), next to {@link #EXCEPTIONS_FILE}. */
    private static final List<String> SHIPPED_LANGUAGES = List.of("fr", "en", "de", "es", "it", "pl", "pt-BR", "ru");

    /**
     * What a load produced.
     *
     * @param warnings what the admin should know but that did not stop the load (a missing list, ignored lines)
     */
    public record Loaded(ChatGuardConfig config, Set<String> tlds, WordLists words, List<String> warnings) {
        public Loaded {
            tlds = Set.copyOf(tlds);
            warnings = List.copyOf(warnings);
        }
    }

    private ChatGuardFiles() {}

    /** Copies every shipped file that is missing from {@code folder}, at its default place. */
    public static void installDefaults(Path folder) throws IOException {
        ChatGuardConfig defaults = ChatGuardConfig.defaults();
        install(CONFIG_FILE, folder.resolve(CONFIG_FILE));
        install("tlds.txt", folder.resolve(defaults.advertising().tldFile()));
        Path words = folder.resolve(defaults.words().directory());
        for (String language : SHIPPED_LANGUAGES) {
            install("words/" + language + ".txt", words.resolve(language + ".txt"));
        }
        install("words/" + EXCEPTIONS_FILE, words.resolve(EXCEPTIONS_FILE));
    }

    /**
     * Reads the files of {@code folder}. Paths in {@code chat-guard.yml} are relative to {@code folder}.
     *
     * @throws IllegalArgumentException when {@code chat-guard.yml} is refused: the caller keeps what it had
     */
    public static Loaded load(Path folder) throws IOException {
        List<String> warnings = new ArrayList<>();
        Path configFile = folder.resolve(CONFIG_FILE);
        ChatGuardConfig config;
        if (Files.isRegularFile(configFile)) {
            config = ChatGuardConfig.fromYaml(Files.readString(configFile, StandardCharsets.UTF_8));
        } else {
            warnings.add(CONFIG_FILE + " is missing: the defaults are used (observe only)");
            config = ChatGuardConfig.defaults();
        }

        Path tldFile = folder.resolve(config.advertising().tldFile());
        Set<String> tlds;
        if (Files.isRegularFile(tldFile)) {
            tlds = TldList.parse(Files.readAllLines(tldFile, StandardCharsets.UTF_8));
        } else {
            warnings.add(config.advertising().tldFile() + " is missing: the shipped TLD list is used");
            tlds = shippedTlds();
        }

        boolean wordsOn = config.modeOf(ChatRuleId.WORDS) != ChatGuardConfig.Mode.OFF;
        Path directory = folder.resolve(config.words().directory());
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (String language : config.words().languages()) {
            Path file = directory.resolve(language + ".txt");
            if (Files.isRegularFile(file)) {
                lines.put(language, Files.readAllLines(file, StandardCharsets.UTF_8));
            } else if (wordsOn) {
                warnings.add(config.words().directory() + "/" + language + ".txt is missing: no word of that language is refused");
            }
        }
        Path exceptionsFile = directory.resolve(EXCEPTIONS_FILE);
        List<String> exceptions = Files.isRegularFile(exceptionsFile)
            ? Files.readAllLines(exceptionsFile, StandardCharsets.UTF_8) : List.of();
        WordLists words = WordLists.of(lines, exceptions);
        if (words.ignoredLines() > 0) {
            warnings.add(words.ignoredLines() + " word list line(s) ignored (a prefix shorter than "
                + WordLists.MIN_PREFIX_LENGTH + " letters, or nothing left once normalized)");
        }
        return new Loaded(config, tlds, words, warnings);
    }

    /** The settings of the jar alone, for when the folder cannot be read: observe only, no word list. */
    public static Loaded shipped() {
        return new Loaded(ChatGuardConfig.defaults(), shippedTlds(), WordLists.empty(), List.of());
    }

    private static Set<String> shippedTlds() {
        try (InputStream in = open("tlds.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return TldList.parse(reader.lines().toList());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void install(String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = open(resource)) {
            Files.copy(in, target);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = ChatGuardFiles.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("the server jar lacks the chat guard resource " + resource);
        }
        return in;
    }
}
