package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The labelled corpus of {@code src/test/resources/chat-corpus}: one tab-separated line per sentence,
 * {@code lang expected text}, where expected is {@code ok} or {@code refused:<rule>}, judged by the whole
 * guard with every rule enforcing. The word lists are a small neutral sample of the test resources, not
 * the shipped lists (shipped empty). Each sentence is a fresh player, so the rate and similarity rules
 * judge the sentence and not its neighbours.
 */
class ChatCorpusTest {

    private static final List<String> FILES = List.of("common", "fr", "en", "de", "es", "it", "pl", "pt-BR", "ru");
    private static final List<String> WORD_LANGUAGES = List.of("fr", "en", "de", "es", "it", "pl", "pt-BR", "ru");
    private static final Pattern CODE_POINT = Pattern.compile("\\{U\\+([0-9A-Fa-f]{4,6})}");

    private static List<String> resource(String path) {
        return ChatTestKit.lines(ChatCorpusTest.class.getResourceAsStream("/chat-corpus/" + path), path);
    }

    /** {@code {U+XXXX}} stands for one code point: invisible and private-use characters stay reviewable. */
    static String decode(String text) {
        Matcher matcher = CODE_POINT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(Character.toString(Integer.parseInt(matcher.group(1), 16))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @TestFactory
    @DisplayName("every labelled sentence of the corpus gets its verdict")
    List<DynamicTest> corpus() {
        Map<String, List<String>> words = new LinkedHashMap<>();
        WORD_LANGUAGES.forEach(language -> words.put(language, resource("words/" + language + ".txt")));
        ChatGuard guard = ChatTestKit.guard(ChatTestKit.enforcing("  words: { mode: enforce }\n"),
            WordLists.of(words, resource("words/exceptions.txt")));

        List<DynamicTest> tests = new ArrayList<>();
        for (String file : FILES) {
            List<String> lines = resource(file + ".tsv");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", 3);
                assertEquals(3, columns.length, file + ".tsv line " + (i + 1) + " needs lang, expected and text");
                String expected = columns[1];
                String text = decode(columns[2]);
                tests.add(DynamicTest.dynamicTest(file + ":" + (i + 1) + " " + expected + " " + columns[2], () -> {
                    UUID player = UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    guard.track(player);
                    ChatVerdict verdict = guard.judge(player, text, 0L, false);
                    String actual = verdict.refusal().map(finding -> "refused:" + finding.rule().key()).orElse("ok");
                    assertEquals(expected, actual, text);
                }));
            }
        }
        assertTrue(tests.size() > 100, "the corpus is loaded: " + tests.size() + " sentences");
        return tests;
    }
}
