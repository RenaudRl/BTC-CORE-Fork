package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.btc.core.integrity.chat.ChatTestKit.ALICE;
import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.enforcing;
import static dev.btc.core.integrity.chat.ChatTestKit.guard;
import static dev.btc.core.integrity.chat.ChatTestKit.withPlayersOnline;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code points are built with {@code cp(...)}, never typed: a private-use character in a source file is
 * stripped by tooling and a combining mark is invisible in review.
 */
class UnicodeRuleTest {

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private final UnicodeRule defaults = new UnicodeRule(config("").unicode());
    private final UnicodeRule noGlyphsAllowed = new UnicodeRule(
        config("rules:\n  unicode:\n    allowed-private-use: []\n").unicode());

    @Test
    @DisplayName("more than two combining marks on one character is refused; two are allowed")
    void zalgoRefused() {
        String twoMarks = "a" + cp(0x0300, 0x0301);
        String stacked = "a" + cp(0x0300, 0x0301, 0x0302, 0x0303);
        assertFalse(refuses(defaults, "hello " + twoMarks + " world"));
        assertTrue(refuses(defaults, "hello " + stacked + " world"));
        assertTrue(refuses(defaults, "z" + cp(0x0335, 0x0321, 0x0348, 0x035A) + "algo"));
    }

    @Test
    @DisplayName("accented French passes, composed or decomposed")
    void frenchAccentsPass() {
        assertFalse(refuses(defaults, "Où êtes-vous allés ? À Noël, ça va très bien, être là, c'était l'été"));
        assertFalse(refuses(defaults, "cafe" + cp(0x0301) + " cre" + cp(0x0300) + "me"));
    }

    @Test
    @DisplayName("Russian, Greek, Polish, German, Japanese and Arabic pass")
    void otherScriptsPass() {
        assertFalse(refuses(defaults, "Привет, как дела? Всё хорошо, спасибо"));
        assertFalse(refuses(defaults, "Γειά σου κόσμε"));
        assertFalse(refuses(defaults, "Zażółć gęślą jaźń, Größe, Straße"));
        assertFalse(refuses(defaults, "こんにちは世界"));
        assertFalse(refuses(defaults, "مرحبا بالعالم"));
    }

    @Test
    @DisplayName("an emoji with its variation selector or a keycap is not stacked marks")
    void emojiSequencesPass() {
        assertFalse(refuses(defaults, cp(0x2764, 0xFE0F) + " " + cp('1', 0xFE0F, 0x20E3) + " " + cp(0x1F600)));
    }

    @Test
    @DisplayName("a private-use character is refused when no private-use range is allowed")
    void privateUseRefused() {
        assertTrue(refuses(noGlyphsAllowed, "hello " + cp(0xE000)));
        assertTrue(refuses(noGlyphsAllowed, cp(0xF0000)), "supplementary private-use plane 15");
        assertTrue(refuses(noGlyphsAllowed, cp(0x10FFFD)), "supplementary private-use plane 16");
        assertFalse(refuses(noGlyphsAllowed, "no glyph here"));
    }

    @Test
    @DisplayName("the shipped settings let every pack glyph through, in every private-use area")
    void packGlyphsPassByDefault() {
        assertFalse(refuses(defaults, "hello " + cp(0xE000) + " " + cp(0xE123) + cp(0xF8FF) + " there"));
        assertFalse(refuses(defaults, cp(0xF0000) + cp(0xFFFFD) + cp(0x100000) + cp(0x10FFFD)));
    }

    @Test
    @DisplayName("allowed-private-use narrows the glyphs to the ranges the admin lists")
    void allowedRangesAreHonoured() {
        UnicodeRule narrowed = new UnicodeRule(
            config("rules:\n  unicode:\n    allowed-private-use: [\"E000-E0FF\", \"F0000\"]\n").unicode());
        assertFalse(refuses(narrowed, cp(0xE000) + cp(0xE0FF) + cp(0xF0000)));
        assertTrue(refuses(narrowed, cp(0xE100)));
        assertTrue(refuses(narrowed, cp(0xF0001)));
    }

    @Test
    @DisplayName("the guard never alters a message with glyphs: normalized keeps them, the whole guard lets them through")
    void glyphsAreNeverAlteredNorBlockedByTheShippedGuard() {
        String message = "Hello " + cp(0xE000) + cp(0xE000) + cp(0xE000) + cp(0xE000) + cp(0xE000) + cp(0xE000)
            + " " + cp(0xF0001) + " World";
        ChatText text = ChatNormalizer.normalize(message);
        assertEquals(message, text.raw());
        assertTrue(text.normalized().contains(cp(0xE000)) && text.normalized().contains(cp(0xF0001)));

        ChatGuard shippedEnforcing = withPlayersOnline(guard(ChatTestKit.config("mode: enforce\n")));
        ChatVerdict verdict = shippedEnforcing.judge(ALICE, message, 0L, false);
        assertFalse(verdict.cancels(), "no rule may refuse a message for its pack glyphs");
        assertTrue(verdict.observed().isEmpty());
    }

    @Test
    @DisplayName("mixed scripts are refused only on request, and only inside a word")
    void mixedScripts() {
        String latinWithCyrillicLookalike = "h" + cp(0x0435) + "llo";
        UnicodeRule strict = new UnicodeRule(config("rules:\n  unicode:\n    refuse-mixed-scripts: true\n").unicode());
        assertFalse(refuses(defaults, latinWithCyrillicLookalike), "off by default");
        assertTrue(refuses(strict, latinWithCyrillicLookalike));
        assertFalse(refuses(strict, "hello привет мир"), "separate words in two scripts are fine");
        assertFalse(refuses(strict, "こんにちは世界カタカナ"), "Japanese mixes scripts inside a word legitimately");
    }

    @Test
    @DisplayName("a full-length ordinary message (accents, emoji, pack glyphs, sharp s) never reaches the bound")
    void ordinaryFullLengthMessagePasses() {
        String sample = "Où êtes-vous ? À Noël, Größe straße " + cp(0x1F600) + cp(0x2764, 0xFE0F) + " "
            + cp(0xE000) + cp(0xF0001) + " Привет ";
        StringBuilder message = new StringBuilder();
        for (int i = 0; message.length() < 256; i = (i + Character.charCount(sample.codePointAt(i))) % sample.length()) {
            int codePoint = sample.codePointAt(i);
            if (message.length() + Character.charCount(codePoint) > 256) {
                break;
            }
            message.appendCodePoint(codePoint);
        }
        assertTrue(message.length() >= 255, "chat length " + message.length());
        assertFalse(ChatNormalizer.normalize(message.toString()).overflowed());
        assertFalse(refuses(defaults, message.toString()));
        assertFalse(ChatNormalizer.normalize("ß".repeat(256)).overflowed(), "a doubled letter on the whole line");
    }

    @Test
    @DisplayName("an expanding prefix that pushes an advert past the bound is judged, never let through unread")
    void expandingPrefixHidingAnAdvertIsJudged() {
        // 57 x 18 = 1026 normalized characters: the advert is beyond the bound.
        String message = cp(0xFDFA).repeat(57) + " join play.myserver.net";
        ChatText text = ChatNormalizer.normalize(message);
        assertTrue(text.overflowed());
        assertFalse(text.normalized().contains("myserver"), "the advert is in the end no rule reads");
        assertTrue(refuses(defaults, message));

        assertTrue(withPlayersOnline(guard(enforcing(""))).judge(ALICE, message, 0L, false).cancels());
        // This prefix also repeats a character: switched off so the overflow alone is proven.
        String noRepetition = "  repetition: { mode: off }\n";
        ChatVerdict enforced = withPlayersOnline(guard(enforcing(noRepetition))).judge(ALICE, message, 0L, false);
        assertEquals(ChatFinding.of(ChatRuleId.UNICODE, "overflow"), enforced.refusal().orElseThrow());

        ChatVerdict observed = withPlayersOnline(guard(config("rules:\n" + noRepetition))).judge(ALICE, message, 0L, false);
        assertFalse(observed.cancels(), "observe never refuses");
        assertTrue(observed.observed().contains(ChatFinding.of(ChatRuleId.UNICODE, "overflow")), "but it is journalled");
    }

    @Test
    @DisplayName("every refusal of the rule is enforced through the whole guard")
    void guardRefusesZalgo() {
        ChatGuard guard = withPlayersOnline(guard(enforcing("")));
        ChatVerdict verdict = guard.judge(ALICE, "a" + cp(0x0300, 0x0301, 0x0302, 0x0303, 0x0304) + " hi", 0L, false);
        assertEquals(ChatRuleId.UNICODE, verdict.refusal().orElseThrow().rule());
    }
}
