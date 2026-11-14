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
        "connect to 192.168.1.10 please",
        "51.75.32.10",
        "51.75.32.10:25565",
        "connect to 10.0.0.1",
        "1.2.3.4:25565",
        "join 10.0.0.1:25565",
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
        "play monserveur(dot)fr",
        "mc . monserveur . fr",
        "visit monserveur.fr today",
        "https://monserveur.fr/jeu",
        "monserveur.fr/discord",
        "monserveur.fr:25565",
        "monserveur.Fr/discord",
        "MONSERVEUR.FR",
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
        String cyrillicO = "m" + new String(Character.toChars(0x043E)) + "nserveur.fr";
        assertTrue(refuses(rule, fullWidth));
        assertTrue(refuses(rule, cyrillicO));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "e x a m p l e . c o m",
        "example .com/x",
        "example. com/x",
        "join example. com/play",
        "example. com:25565",
        "play.example. com",
        "play.example .net",
        "example.сом",
    })
    @DisplayName("a spaced-out, space-split or Cyrillic-TLD domain is seen through")
    void obfuscatedDomainRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    @Test
    @DisplayName("a Hangul filler anywhere in a domain does not hide it")
    void fillerInsideDomainDoesNotHideIt() {
        // Built from code points: a filler character in the source would be stripped by tooling.
        for (int filler : new int[] {0x3164, 0x115F, 0x1160, 0xFFA0}) {
            String pad = Character.toString(filler);
            for (String message : new String[] {"example" + pad + ".com", "example." + pad + "com", "exampl" + pad + "e.com"}) {
                assertTrue(refuses(rule, message), Integer.toHexString(filler) + " in " + message.replace(pad, "<filler>"));
            }
        }
    }

    @Test
    @DisplayName("a character glued to an allowed path that a browser could read as path keeps the entry from matching")
    void gluedCharacterAfterAllowedPath() {
        AdvertisingRule own = new AdvertisingRule(
            config("rules:\n  advertising:\n    allow-domains: [\"discord.gg/btc\"]\n").advertising(), shippedTlds());
        assertTrue(refuses(own, "join discord.gg/btc!/../autre"), "! is a path character: the browser resolves the ..");
        assertTrue(refuses(own, "join discord.gg/btc,/../autre"));
        assertFalse(refuses(own, "join discord.gg/btc!"), "a closing ! is the sentence's");
        assertFalse(refuses(own, "join discord.gg/btc!!"));
        assertFalse(refuses(own, "join discord.gg/btc?"));
        assertFalse(refuses(own, "join discord.gg/btc! thanks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://youtube.com@evil.com/x",
        "http://youtu.be@evil.com",
        "https://user:pass@monserveur.fr/jeu",
        "see https://www.youtube.com@evil.com/x",
        "https://a@b@evil.com/x",
        "https://youtube.com@x@evil.com/",
        "https://a@youtube.com@evil.com",
        "https://user:p@ss@monserveur.fr/jeu",
    })
    @DisplayName("the domain after the @ of a URL's user info is the one judged; the one before it is not allow-listed")
    void userInfoDomainIsJudged(String message) {
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

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=abc",
        "youtu.be/abc",
        "the wiki: minecraft.wiki/w/Creeper",
        "see imgur.com/abc",
        "github.com/btc/repo",
    })
    @DisplayName("the shipped allow-list lets the usual sharing sites through")
    void shippedAllowListPasses(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @Test
    @DisplayName("an allow-list entry with a path prefix lets only that part of the site through")
    void allowedPathPrefix() {
        AdvertisingRule own = new AdvertisingRule(
            config("rules:\n  advertising:\n    allow-domains: [\"discord.gg/btc\"]\n").advertising(), shippedTlds());
        assertFalse(refuses(own, "join https://discord.gg/btc"));
        assertFalse(refuses(own, "join discord.gg/btc/rules now"));
        assertFalse(refuses(own, "join discord.gg/BTC"), "case does not matter");
        assertTrue(refuses(own, "join discord.gg/btcfake"), "a longer code is another invite");
        assertFalse(refuses(own, "join discord.gg/btc, thanks"), "punctuation after the code is not part of the path");
        assertFalse(refuses(own, "join discord.gg/btc?ref=x"));
        assertFalse(refuses(own, "join discord.gg/btc."), "a closing full stop is the sentence's");
        assertTrue(refuses(own, "join discord.gg/btc/.."), "a trailing .. is not a full stop");
        assertTrue(refuses(own, "join discord.gg/btc/."));
        assertTrue(refuses(own, "join discord.gg/btc/../other"), "a browser resolves .. to another invite");
        assertTrue(refuses(own, "join discord.gg/btc%2f..%2fother"), "an encoded slash or dot hides the same trick");
        assertTrue(refuses(own, "join discord.gg/btc\\..\\other"), "a backslash is read as a slash by a browser");
        assertTrue(refuses(own, "join discord.gg/btcx"));
        assertTrue(refuses(own, "join discord.gg/other"));
        assertTrue(refuses(own, "join discord.gg"), "the bare site is not the allowed part");
        assertTrue(refuses(own, "join discord.gg.evil.com/btc"));
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
        "1.21.4.2",
        "26.3.1.0",
        "version 1.20.1.5 works",
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
        "Super. Top",
        "Cool. Game",
        "i use .net daily",
        "il y a 3 joueurs. top",
        "y a 2 mobs. fun",
        "write to x@monserveur.fr",
        "les \u00e9tudiant\u00b7es",
        "les chanteur\u00b7se\u00b7s",
        "example .com rocks",
        "nice. gg",
        "bien joué. GG",
        "merci.De rien",
        "thanks.Me too",
        "that's crazy. fr",
    })
    @DisplayName("ordinary prose that looks a little like an address is not one")
    void proseIsNotAnAddress(String message) {
        assertFalse(refuses(rule, message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "discord. gg/abcd",
        "discord . gg/abcd",
        "monserveur. fr/x",
        "\u1d05\u026a\ua731\u1d04\u1d0f\u0280\u1d05.\u0262\u0262/x",
        "d\u2800i\u2800s\u2800c\u2800o\u2800r\u2800d\u2800.\u2800g\u2800g",
        "d\ue000i\ue000s\ue000c\ue000o\ue000r\ue000d\ue000.\ue000g\ue000g",
        "discord\u00b7gg/x",
        "discord\u2027gg/x",
        "discord\u2219gg/x",
        "discord\u30fbgg/x",
        "\u0501iscord.gg/x",
        "\u04bbypixel.net",
        "\u0261ithub.io/x",
        "monserveur.Fr",
        "Monserveur.Fr",
        "discord.gg/x",
        "monserveur .com",
        "monserveur. com",
        "example. com",
        "example .com",
    })
    @DisplayName("review 14/11: a two-letter TLD after a split dot, look-alike letters and dots, a split strong TLD")
    void reviewedDisguisesRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    /** A private-use glyph and the blank braille pattern, glued inside a word. */
    private static final char PUA = 0xE000;
    private static final char BRAILLE_BLANK = 0x2800;

    @ParameterizedTest
    @ValueSource(strings = {
        "monserveur.f" + PUA + "r",
        "discord.g" + PUA + "g/x",
        "monserveur.f" + BRAILLE_BLANK + "r",
    })
    @DisplayName("a space-like glyph glued inside a domain is also read as nothing")
    void spaceLikeGlyphInsideADomainIsRefused(String message) {
        assertTrue(refuses(rule, message), message);
    }

    @Test
    @DisplayName("the strong TLDs that make a split dot an address are the configured ones")
    void strongTldsAreConfigurable() {
        AdvertisingRule own = new AdvertisingRule(
            config("rules:\n  advertising:\n    strong-tlds: [com]\n").advertising(), shippedTlds());
        assertTrue(refuses(own, "monserveur. com"));
        assertFalse(refuses(own, "monserveur. fr"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "join discord.gg/btc...",
        "join discord.gg/btc\u2026",
        "\"discord.gg/btc\"",
        "\u00ab discord.gg/btc \u00bb",
        "\u00abdiscord.gg/btc\u00bb",
        "'discord.gg/btc'",
        "join discord.gg/btc\u201d",
    })
    @DisplayName("an allowed link closed by an ellipsis or a quote passes")
    void allowedLinkWithClosingPunctuation(String message) {
        AdvertisingRule own = new AdvertisingRule(
            config("rules:\n  advertising:\n    allow-domains: [\"discord.gg/btc\"]\n").advertising(), shippedTlds());
        assertFalse(refuses(own, message), message);
        assertTrue(refuses(own, "join discord.gg/btc'/../x"), "a quote glued to more path is path");
    }

    @Test
    @DisplayName("ipv4 and domains can each be switched off")
    void partsCanBeSwitchedOff() {
        AdvertisingRule noDomains = new AdvertisingRule(
            config("rules:\n  advertising:\n    domains: false\n").advertising(), shippedTlds());
        AdvertisingRule noAddresses = new AdvertisingRule(
            config("rules:\n  advertising:\n    ipv4: false\n").advertising(), shippedTlds());
        assertFalse(refuses(noDomains, "monserveur.fr"));
        assertTrue(refuses(noDomains, "123.45.67.89"));
        assertFalse(refuses(noAddresses, "123.45.67.89"));
        assertTrue(refuses(noAddresses, "monserveur.fr"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " ", "a", "a.", "1.", "1.1.", "a . ", "1 dot ", "( ", "a-", "a-a.", "9 (.) ", "9.9.9.",
        "a+", "a b ", "a. ", "\u2800", "a\u00b7",
    })
    @DisplayName("a hostile message built to make the patterns backtrack is judged within a tight deadline")
    void hostileInputIsJudgedQuickly(String unit) {
        String message = unit.repeat(ChatNormalizer.MAX_INPUT_CHARS / unit.length());
        refuses(rule, unit.repeat(8)); // the JIT warms up outside the deadline
        assertTimeoutPreemptively(HOSTILE_DEADLINE, () -> refuses(rule, message), unit);
        assertTimeoutPreemptively(HOSTILE_DEADLINE, () -> refuses(rule, message + "!"), unit);
    }

    /** A linear judgement of 1024 characters takes well under a millisecond; a quadratic one does not. */
    private static final Duration HOSTILE_DEADLINE = Duration.ofMillis(50);
}
