package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapsRuleTest {

    private final CapsRule rule = new CapsRule(config("").caps());

    @ParameterizedTest
    @ValueSource(strings = {"SALUT", "GG WP", "BONNE ANNEE", "HAPPY BIRTHDAY", "GG WP EZ ALL", "ABC DEF", "SALUT 12345678 !!!!"})
    @DisplayName("a short shout passes: fewer than min-words words of three letters or more are never judged")
    void shortShoutPasses(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @Test
    @DisplayName("more than 60 percent capitals over enough words is refused; exactly 60 percent passes")
    void aboveRatioRefused() {
        assertTrue(refuses(rule, "THIS IS A VERY LOUD MESSAGE"));
        assertTrue(refuses(rule, "ABC DEF GHI JKL mno pqr"), "12 of 18 letters");
        assertFalse(refuses(rule, "ABC DEF GHI abc def"), "exactly 60 percent of 15 letters");
        assertTrue(refuses(rule, "ABC DEF GHI"), "three words of three letters: the minimum is reached");
        assertFalse(refuses(rule, "ABC DEF GH"), "two words of three letters and a short one: below the minimum");
    }

    @Test
    @DisplayName("a script without capitals is never refused")
    void scriptWithoutCapitalsPasses() {
        assertFalse(refuses(rule, "こんにちは世界こんにちは世界"));
    }
}
