package dev.btc.core.integrity.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The word lists of every language, as an immutable snapshot (a reload builds a new one and swaps it).
 *
 * <p>An entry is one line: a word ({@code merde}), a prefix when it ends with {@code *}
 * ({@code salop*}), or several words ({@code son of a}). A word entry matches a whole word only, so
 * {@code ass} never matches {@code class} nor {@code bassin}. An exception is a whole word that is never
 * matched by a word or prefix entry, whatever the list says. Entries are normalized like messages, and
 * every language applies to every message: nobody types only in the language of their client.
 */
public final class WordLists {

    /** A prefix shorter than this would refuse half the dictionary; such a line is ignored and counted. */
    static final int MIN_PREFIX_LENGTH = 3;

    private record Entries(Set<String> words, List<String> prefixes, List<String> phrases) {}

    private static final WordLists EMPTY = new WordLists(Map.of(), Set.of(), 0, 0);

    private final Map<String, Entries> byLanguage;
    private final Set<String> exceptions;
    private final int entries;
    private final int ignored;

    private WordLists(Map<String, Entries> byLanguage, Set<String> exceptions, int entries, int ignored) {
        this.byLanguage = byLanguage;
        this.exceptions = exceptions;
        this.entries = entries;
        this.ignored = ignored;
    }

    public static WordLists empty() {
        return EMPTY;
    }

    /**
     * @param linesByLanguage the lines of each language file, comments ({@code #}) and blank lines allowed
     * @param exceptionLines  the lines of {@code exceptions.txt}
     */
    public static WordLists of(Map<String, List<String>> linesByLanguage, List<String> exceptionLines) {
        Map<String, Entries> languages = new LinkedHashMap<>();
        int count = 0;
        int ignored = 0;
        for (Map.Entry<String, List<String>> language : linesByLanguage.entrySet()) {
            Set<String> words = new HashSet<>();
            List<String> prefixes = new ArrayList<>();
            List<String> phrases = new ArrayList<>();
            for (String line : language.getValue()) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                boolean prefix = entry.endsWith("*");
                List<String> tokens = ChatNormalizer.entryWords(prefix ? entry.substring(0, entry.length() - 1) : entry);
                if (tokens.isEmpty() || (prefix && (tokens.size() > 1 || tokens.get(0).length() < MIN_PREFIX_LENGTH))) {
                    ignored++;
                    continue;
                }
                count++;
                if (prefix) {
                    prefixes.add(tokens.get(0));
                } else if (tokens.size() == 1) {
                    words.add(tokens.get(0));
                } else {
                    phrases.add(" " + String.join(" ", tokens) + " ");
                }
            }
            languages.put(language.getKey(), new Entries(Set.copyOf(words), List.copyOf(prefixes), List.copyOf(phrases)));
        }
        Set<String> exempt = new HashSet<>();
        for (String line : exceptionLines) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                exempt.addAll(ChatNormalizer.entryWords(entry));
            }
        }
        return new WordLists(Map.copyOf(languages), Set.copyOf(exempt), count, ignored);
    }

    /** Lines ignored while loading (a prefix too short to be safe, or nothing left after normalizing). */
    public int ignoredLines() {
        return ignored;
    }

    public int entryCount() {
        return entries;
    }

    public Set<String> languages() {
        return byLanguage.keySet();
    }

    /** @return the language of the first list that matches, in any of the message's spellings */
    public Optional<String> firstMatch(ChatText text) {
        if (entries == 0) {
            return Optional.empty();
        }
        for (List<String> form : text.wordForms()) {
            String padded = " " + String.join(" ", form) + " ";
            for (Map.Entry<String, Entries> language : byLanguage.entrySet()) {
                if (matches(language.getValue(), form, padded)) {
                    return Optional.of(language.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private boolean matches(Entries list, List<String> form, String padded) {
        for (String word : form) {
            if (exceptions.contains(word)) {
                continue;
            }
            if (list.words().contains(word)) {
                return true;
            }
            for (String prefix : list.prefixes()) {
                if (word.startsWith(prefix)) {
                    return true;
                }
            }
        }
        for (String phrase : list.phrases()) {
            if (padded.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
