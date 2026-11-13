package dev.btc.core.text;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys are frozen the day a translation file is written against them: this is what keeps them honest. */
class PlayerTextCatalogTest {

    private static final Pattern KEY_SHAPE = Pattern.compile("btccore(\\.[a-z][a-z0-9_]*)+");
    private static final Pattern TAG = Pattern.compile("<(?!/)([a-z_]+)(?::[^>]*)?>");

    @Test
    @DisplayName("keys are unique, in the btccore domain, and made of lowercase words")
    void keysAreUniqueAndPrefixedByBtccore() {
        Set<String> seen = new HashSet<>();
        for (PlayerText text : PlayerText.values()) {
            assertTrue(text.key().startsWith("btccore."), text.key());
            assertTrue(KEY_SHAPE.matcher(text.key()).matches(), "malformed key: " + text.key());
            assertTrue(seen.add(text.key()), "duplicate key: " + text.key());
        }
        assertEquals(seen, PlayerText.keys(), "playerTextKeys() must publish exactly the keys of the enum");
    }

    @Test
    @DisplayName("every default template uses exactly the variables it declares")
    void everyDefaultTemplateUsesExactlyItsDeclaredArguments() {
        TagResolver styles = TagResolver.standard();
        for (PlayerText text : PlayerText.values()) {
            Set<String> used = new HashSet<>();
            Matcher tag = TAG.matcher(text.defaultTemplate());
            while (tag.find()) {
                if (!styles.has(tag.group(1))) {
                    used.add(tag.group(1));
                }
            }
            Set<String> declared = new HashSet<>(text.variables());
            assertEquals(declared, used, text.key() + " declares " + declared + " but its template uses " + used);
            assertEquals(declared.size(), text.variables().size(), text.key() + " declares a variable twice");
        }
    }

    @Test
    @DisplayName("a text shown to a player about a linked account declares no variable: no reason, no duration (AD-091)")
    void linkedAccountTextsDeclareNothing() {
        for (PlayerText text : java.util.List.of(PlayerText.SANCTION_BANNED_LINKED, PlayerText.SANCTION_MUTED_LINKED,
            PlayerText.SANCTION_KICKED_LINKED, PlayerText.SANCTION_WARNED_LINKED)) {
            assertTrue(text.variables().isEmpty(), text.key() + " would be given " + text.variables());
        }
    }
}
