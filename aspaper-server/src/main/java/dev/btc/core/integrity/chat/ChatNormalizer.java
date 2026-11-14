package dev.btc.core.integrity.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Turns a message into the views the rules compare. Pure and stateless.
 *
 * <p>The raw text is never changed for the player; these views only serve to see through the usual
 * disguises (accents, full-width letters, Cyrillic look-alikes inside a Latin word, {@code p@t3},
 * {@code p u t e}). A private-use code point (a pack glyph) goes through every view untouched: it is
 * neither a letter nor a digit, so it is simply a separator for the word views.
 */
public final class ChatNormalizer {

    /** Protocol bound: Minecraft chat is 256 characters; anything longer is cut before any regex runs. */
    static final int MAX_INPUT_CHARS = 1024;

    /**
     * Bound of the normalized text: one compatibility character can give a dozen (U+FDFA gives 18) and a
     * sharp s gives two, while the rules run regexes and edit distances on the result.
     */
    static final int MAX_NORMALIZED_CHARS = MAX_INPUT_CHARS;

    /** A run of this many one-letter words ("p u t e") is read as one word. */
    static final int MIN_SPACED_RUN = 3;

    private static final Map<Integer, String> SPECIAL_LETTERS = Map.of(
        (int) 'ß', "ss", (int) 'ø', "o", (int) 'đ', "d", (int) 'ł', "l",
        (int) 'æ', "ae", (int) 'œ', "oe", (int) 'ı', "i");

    /** Cyrillic and Greek letters that look like a Latin one, lower case. */
    private static final Map<Integer, Integer> TO_LATIN = Map.ofEntries(
        Map.entry(0x0430, (int) 'a'), Map.entry(0x0435, (int) 'e'), Map.entry(0x043E, (int) 'o'),
        Map.entry(0x0440, (int) 'p'), Map.entry(0x0441, (int) 'c'), Map.entry(0x0443, (int) 'y'),
        Map.entry(0x0445, (int) 'x'), Map.entry(0x043A, (int) 'k'), Map.entry(0x043C, (int) 'm'),
        Map.entry(0x0442, (int) 't'), Map.entry(0x043D, (int) 'h'), Map.entry(0x0456, (int) 'i'),
        Map.entry(0x0458, (int) 'j'), Map.entry(0x0455, (int) 's'),
        Map.entry(0x03B1, (int) 'a'), Map.entry(0x03B5, (int) 'e'), Map.entry(0x03B9, (int) 'i'),
        Map.entry(0x03BA, (int) 'k'), Map.entry(0x03BD, (int) 'v'), Map.entry(0x03BF, (int) 'o'),
        Map.entry(0x03C1, (int) 'p'), Map.entry(0x03C4, (int) 't'), Map.entry(0x03C5, (int) 'u'),
        Map.entry(0x03C7, (int) 'x'));

    /** The Latin look-alikes of Cyrillic letters, for a Cyrillic word with a Latin intruder. */
    private static final Map<Integer, Integer> TO_CYRILLIC = Map.ofEntries(
        Map.entry((int) 'a', 0x0430), Map.entry((int) 'e', 0x0435), Map.entry((int) 'o', 0x043E),
        Map.entry((int) 'p', 0x0440), Map.entry((int) 'c', 0x0441), Map.entry((int) 'y', 0x0443),
        Map.entry((int) 'x', 0x0445), Map.entry((int) 'k', 0x043A), Map.entry((int) 'm', 0x043C),
        Map.entry((int) 't', 0x0442), Map.entry((int) 'h', 0x043D), Map.entry((int) 'i', 0x0456));

    private static final Map<Integer, Integer> LEET = Map.of(
        (int) '0', (int) 'o', (int) '1', (int) 'i', (int) '3', (int) 'e', (int) '4', (int) 'a',
        (int) '5', (int) 's', (int) '7', (int) 't', (int) '@', (int) 'a', (int) '$', (int) 's');

    private ChatNormalizer() {}

    public static ChatText normalize(String raw) {
        String bounded = raw.length() > MAX_INPUT_CHARS ? raw.substring(0, MAX_INPUT_CHARS) : raw;

        int letters = 0;
        int upper = 0;
        int combiningRun = 0;
        int combiningMax = 0;
        for (int i = 0; i < bounded.length(); ) {
            int cp = bounded.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
                    upper++;
                }
            }
            if (isStackedMark(cp)) {
                combiningRun++;
                combiningMax = Math.max(combiningMax, combiningRun);
            } else {
                combiningRun = 0;
            }
        }

        String based = base(bounded);
        boolean overflowed = raw.length() > MAX_INPUT_CHARS || based.length() > MAX_NORMALIZED_CHARS;
        String folded = mapTokens(capped(based), ChatNormalizer::foldScripts);
        String leet = mapTokens(folded, ChatNormalizer::foldLeet);

        LinkedHashSet<List<String>> forms = new LinkedHashSet<>();
        forms.add(tokens(folded, false));
        List<String> leetTokens = tokens(leet, true);
        forms.add(leetTokens);
        forms.add(joinSpacedLetters(leetTokens));

        return new ChatText(bounded, collapseSpaces(folded), letters, upper, combiningMax, List.copyOf(forms), overflowed);
    }

    /** The words of a word-list entry, spelled as the matcher spells a message (no leet, no joining). */
    public static List<String> entryWords(String entry) {
        return tokens(mapTokens(base(entry), ChatNormalizer::foldScripts), false);
    }

    /** Every Cyrillic or Greek look-alike of a Latin letter in {@code lowerCased} turned into that letter, even in a pure-Cyrillic word. */
    static String foldLookalikes(String lowerCased) {
        StringBuilder out = new StringBuilder(lowerCased.length());
        lowerCased.codePoints().forEach(cp -> out.appendCodePoint(TO_LATIN.getOrDefault(cp, cp)));
        return out.toString();
    }

    /**
     * The Hangul fillers (U+115F, U+1160, U+3164, U+FFA0) are letters that draw nothing: removed from a view, they
     * cannot split a domain or a word. NFKC already turns U+3164 and U+FFA0 into U+1160.
     */
    static String stripFillers(String text) {
        return stripFillers(text, "");
    }

    /** Every filler of {@code text} replaced by {@code replacement}. */
    static String stripFillers(String text, String replacement) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0) {
                out.append(replacement);
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /** Variation selectors ride on emoji and are not stacked marks. */
    private static boolean isStackedMark(int cp) {
        int type = Character.getType(cp);
        boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK;
        boolean variationSelector = (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
        return mark && !variationSelector;
    }

    /** Compatibility fold, lower case, accents and format characters removed, spaces collapsed later. */
    private static String base(String text) {
        String lower = stripFillers(Normalizer.normalize(text, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT);
        StringBuilder stripped = new StringBuilder(lower.length());
        Normalizer.normalize(lower, Normalizer.Form.NFD).codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.FORMAT) {
                stripped.appendCodePoint(cp);
            }
        });
        StringBuilder out = new StringBuilder(stripped.length());
        Normalizer.normalize(stripped, Normalizer.Form.NFC).codePoints().forEach(cp -> {
            String special = SPECIAL_LETTERS.get(cp);
            if (special != null) {
                out.append(special);
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    /** Cuts to {@link #MAX_NORMALIZED_CHARS} without leaving half of a surrogate pair at the end. */
    private static String capped(String text) {
        if (text.length() <= MAX_NORMALIZED_CHARS) {
            return text;
        }
        int end = MAX_NORMALIZED_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String collapseSpaces(String text) {
        return text.replaceAll(" {2,}", " ").strip();
    }

    private static boolean isTokenChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '@' || cp == '$';
    }

    /** Applies {@code mapper} to every run of letters, digits, {@code @} and {@code $}; separators stay. */
    private static String mapTokens(String text, UnaryOperator<String> mapper) {
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isTokenChar(cp)) {
                token.appendCodePoint(cp);
            } else {
                if (!token.isEmpty()) {
                    out.append(mapper.apply(token.toString()));
                    token.setLength(0);
                }
                out.appendCodePoint(cp);
            }
        }
        if (!token.isEmpty()) {
            out.append(mapper.apply(token.toString()));
        }
        return out.toString();
    }

    /** @param symbols whether {@code @} and {@code $} belong to a word (the leet spelling) or split it */
    private static List<String> tokens(String text, boolean symbols) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean inWord = Character.isLetterOrDigit(cp) || (symbols && (cp == '@' || cp == '$'));
            if (inWord) {
                token.appendCodePoint(cp);
            } else if (!token.isEmpty()) {
                out.add(token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            out.add(token.toString());
        }
        return List.copyOf(out);
    }

    /** A word that mixes scripts has its look-alikes folded toward the script most of its letters are in. */
    private static String foldScripts(String token) {
        int latin = 0;
        int cyrillic = 0;
        int greek = 0;
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            switch (Character.UnicodeScript.of(cp)) {
                case LATIN -> latin++;
                case CYRILLIC -> cyrillic++;
                case GREEK -> greek++;
                default -> { }
            }
        }
        if (latin == 0 || cyrillic + greek == 0) {
            return token;
        }
        Map<Integer, Integer> table = cyrillic > latin ? TO_CYRILLIC : TO_LATIN;
        StringBuilder out = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> {
            boolean foreign = table == TO_LATIN
                ? Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN
                : Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
            out.appendCodePoint(foreign ? table.getOrDefault(cp, cp) : cp);
        });
        return out.toString();
    }

    /** Digits and symbols standing for letters, only inside a word that has a Latin letter. */
    private static String foldLeet(String token) {
        boolean hasLatinLetter = token.codePoints().anyMatch(cp ->
            Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
        if (!hasLatinLetter) {
            return token;
        }
        StringBuilder out = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> out.appendCodePoint(LEET.getOrDefault(cp, cp)));
        return out.toString();
    }

    /** {@code p u t e} (or {@code p.u.t.e}) read as {@code pute}: runs of one-letter words are joined. */
    private static List<String> joinSpacedLetters(List<String> words) {
        List<String> out = new ArrayList<>(words.size());
        StringBuilder run = new StringBuilder();
        int runLength = 0;
        for (String word : words) {
            if (word.codePointCount(0, word.length()) == 1) {
                run.append(word);
                runLength++;
                continue;
            }
            flush(out, run, runLength);
            runLength = 0;
            out.add(word);
        }
        flush(out, run, runLength);
        return List.copyOf(out);
    }

    private static void flush(List<String> out, StringBuilder run, int runLength) {
        if (runLength >= MIN_SPACED_RUN) {
            out.add(run.toString());
        } else {
            for (int i = 0; i < run.length(); ) {
                int cp = run.codePointAt(i);
                out.add(new String(Character.toChars(cp)));
                i += Character.charCount(cp);
            }
        }
        run.setLength(0);
    }
}
