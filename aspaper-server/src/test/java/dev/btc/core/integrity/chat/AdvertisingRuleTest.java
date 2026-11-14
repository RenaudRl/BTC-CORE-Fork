package dev.btc.core.integrity.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.btc.core.integrity.chat.ChatTestKit.config;
import static dev.btc.core.integrity.chat.ChatTestKit.refuses;
import static dev.btc.core.integrity.chat.ChatTestKit.shippedTlds;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvertisingRuleTest {

    private final AdvertisingRule rule = new AdvertisingRule(config("").advertising(), shippedTlds());

    @ParameterizedTest
    @ValueSource(strings = {
        "join 123.45.67.89:25565 now",
        "ip is 123.45.67.89",
        "connect to 10.0.0.1 please",
        "123 (.) 45 [.] 67 {.} 89",
        "192 dot 168 dot 1 dot 20",
    })
    @DisplayName("an IPv4 address, with or without a port, is refused")
    void ipv4WithPortRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mc point monserveur point fr",
        "mc dot monserveur dot net",
        "mc (.) monserveur [.] fr",
        "play monserveur(dot)net",
        "mc . monserveur . fr",
        "visit monserveur.net today",
        "https://monserveur.fr/jeu",
        "monserveur.fr/discord",
        "monserveur.fr:25565",
        "play.monserveur.fr",
        "discord.gg/abcdef",
        "mc.hypixel.net:25565",
        "MC.MONSERVEUR.FR",
        "example.com.thanks",
        "play.monserveur.fr.ok",
        "monserveur.net.Merci",
    })
    @DisplayName("a domain with a known TLD is refused, plain or with a disguised dot")
    void disguisedDotRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    @Test
    @DisplayName("full-width letters and a Cyrillic look-alike inside a domain are seen through")
    void lookAlikesAreSeenThrough() {
        String fullWidth = "ｍｃ．ｍｏｎｓｅｒｖｅｕｒ．ｆｒ";
        String cyrillicO = "m" + new String(Character.toChars(0x043E)) + "nserveur.net";
        assertTrue(refuses(rule, fullWidth));
        assertTrue(refuses(rule, cyrillicO));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "e x a m p l e . c o m",
        "example .com",
        "example. com",
        "join example. com/play",
        "example.сом",
    })
    @DisplayName("a spaced-out, space-split or Cyrillic-TLD domain is seen through")
    void obfuscatedDomainRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    @Test
    @DisplayName("an invisible letter (Hangul filler) after the TLD does not hide the domain")
    void invisibleLetterDoesNotHideDomain() {
        // Built from code points: a filler character in the source would be stripped by tooling.
        for (int filler : new int[] {0x3164, 0x115F, 0x1160, 0xFFA0}) {
            String pad = Character.toString(filler);
            assertTrue(refuses(rule, "example.com" + pad), Integer.toHexString(filler));
            assertTrue(refuses(rule, "example.com" + pad + "hello"), Integer.toHexString(filler));
            assertTrue(refuses(rule, "play.example.net" + pad + "/join"), Integer.toHexString(filler));
        }
    }

    @Test
    @DisplayName("the allow-listed domain and its subdomains pass, a look-alike of it does not")
    void allowlistedDomainPasses() {
        assertFalse(refuses(rule, "see borntocraft.net for the rules"));
        assertFalse(refuses(rule, "the wiki is on wiki.borntocraft.net/start"));
        assertTrue(refuses(rule, "evilborntocraft.net is a scam"), "a longer name that ends the same is not a subdomain");
        assertTrue(refuses(rule, "borntocraft.net.evil.com"));
    }

    @Test
    @DisplayName("an allow-listed IP address passes")
    void allowlistedAddressPasses() {
        AdvertisingRule own = new AdvertisingRule(
            config("rules:\n  advertising:\n    allow-domains: [\"203.0.113.7\"]\n").advertising(), shippedTlds());
        assertFalse(refuses(own, "our server is 203.0.113.7:25565"));
        assertTrue(refuses(own, "theirs is 203.0.113.8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.21.4",
        "26.3",
        "version 1.21.4 is out",
        "x=12.5 y=64.25 z=-100.75",
        "it takes 3.5 seconds",
        "the price is 12.99 euros",
        "v1.21.4.jar",
        "256.1.1.1",
    })
    @DisplayName("a version number, a coordinate or a price is not an address")
    void versionNumberIsNotAnAddress(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a ce point net, c'est clair",
        "at this point com is fine",
        "Salut.Comment ca va",
        "yes.me",
        "ok.in",
        "write to user@example.com",
        "the end. Net result: fine",
        "Hello.It's fine",
        "Hola.Es que no puedo",
        "Salut.Ca va",
        "thanks.It was great",
        "visit monserveur.fr today",
    })
    @DisplayName("ordinary prose that looks a little like an address is not one")
    void proseIsNotAnAddress(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @Test
    @DisplayName("ipv4 and domains can each be switched off")
    void partsCanBeSwitchedOff() {
        AdvertisingRule noDomains = new AdvertisingRule(
            config("rules:\n  advertising:\n    domains: false\n").advertising(), shippedTlds());
        AdvertisingRule noAddresses = new AdvertisingRule(
            config("rules:\n  advertising:\n    ipv4: false\n").advertising(), shippedTlds());
        assertFalse(refuses(noDomains, "monserveur.net"));
        assertTrue(refuses(noDomains, "123.45.67.89"));
        assertFalse(refuses(noAddresses, "123.45.67.89"));
        assertTrue(refuses(noAddresses, "monserveur.net"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " ", "a", "a.", "1.", "1.1.", "a . ", "1 dot ", "( ", "a-", "a-a.", "9 (.) ", "9.9.9.",
    })
    @DisplayName("a hostile message built to make the patterns backtrack is judged within a generous deadline")
    void hostileInputIsJudgedQuickly(String unit) {
        String message = unit.repeat(ChatNormalizer.MAX_INPUT_CHARS / unit.length());
        assertTimeoutPreemptively(HOSTILE_DEADLINE, () -> refuses(rule, message), unit);
        assertTimeoutPreemptively(HOSTILE_DEADLINE, () -> refuses(rule, message + "!"), unit);
    }

    /** Far above the real cost (milliseconds): only a catastrophic backtracking could reach it. */
    private static final Duration HOSTILE_DEADLINE = Duration.ofSeconds(10);
}
