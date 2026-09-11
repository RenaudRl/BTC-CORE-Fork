package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionDuration.Parsed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the duration grammar moderators type under pressure.
 *
 * <p>The failures guarded here are silent ones: a bare number guessed as the wrong unit, or an
 * overflow that turns a very long ban into an already-expired one. Both produce a sanction that looks
 * correct in the command output and behaves differently in practice.
 */
class SanctionDurationTest {

    @Test
    @DisplayName("the usual units parse")
    void unitsParse() {
        assertEquals(30_000L, temporaryMillis("30s"));
        assertEquals(600_000L, temporaryMillis("10m"));
        assertEquals(7_200_000L, temporaryMillis("2h"));
        assertEquals(604_800_000L, temporaryMillis("7d"));
        assertEquals(1_209_600_000L, temporaryMillis("2w"));
    }

    @Test
    @DisplayName("case and padding do not matter")
    void toleratesFormatting() {
        assertEquals(3_600_000L, temporaryMillis("  1H  "));
    }

    @Test
    @DisplayName("permanence must be said, not implied")
    void permanence() {
        assertInstanceOf(Parsed.Permanent.class, SanctionDuration.parse("perm"));
        assertInstanceOf(Parsed.Permanent.class, SanctionDuration.parse("permanent"));
        assertInstanceOf(Parsed.Permanent.class, SanctionDuration.parse("FOREVER"));
        assertTrue(SanctionDuration.expiryFrom(0, SanctionDuration.parse("perm")).isEmpty());
    }

    @Test
    @DisplayName("a bare number is refused rather than guessed")
    void bareNumberRefused() {
        // Guessing here is how a seven-day ban silently becomes a seven-second one.
        Parsed parsed = SanctionDuration.parse("7");
        assertInstanceOf(Parsed.Invalid.class, parsed);
        assertTrue(((Parsed.Invalid) parsed).reason().contains("unit"),
            "the refusal should teach the grammar, not just say no");
    }

    @Test
    @DisplayName("nonsense is refused with a usable message")
    void nonsenseRefused() {
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse("soon"));
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse(""));
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse(null));
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse("d"));
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse("0m"));
        assertInstanceOf(Parsed.Invalid.class, SanctionDuration.parse("-5h"));
    }

    @Test
    @DisplayName("an absurd amount is refused instead of overflowing into the past")
    void overflowRefused() {
        // Without the bound, this multiplies past Long.MAX_VALUE and lands before now — a "ban"
        // that is already expired the moment it is issued, with no error anywhere.
        Parsed parsed = SanctionDuration.parse("9999999999d");
        assertInstanceOf(Parsed.Invalid.class, parsed);

        long now = 1_000_000L;
        Parsed sane = SanctionDuration.parse("365d");
        long expiry = SanctionDuration.expiryFrom(now, sane).orElseThrow();
        assertTrue(expiry > now, "a parsed duration must always land in the future");
    }

    @Test
    @DisplayName("remaining time reads the way staff expect")
    void describesRemaining() {
        long now = 0;
        assertEquals("permanent", SanctionDuration.describeRemaining(now, Optional.empty()));
        assertEquals("expired", SanctionDuration.describeRemaining(now, Optional.of(-1L)));
        assertEquals("45s", SanctionDuration.describeRemaining(now, Optional.of(45_000L)));
        assertEquals("10m", SanctionDuration.describeRemaining(now, Optional.of(600_000L)));
        assertEquals("2h 30m", SanctionDuration.describeRemaining(now, Optional.of(9_000_000L)));
        assertEquals("7d 0h", SanctionDuration.describeRemaining(now, Optional.of(604_800_000L)));
    }

    private static long temporaryMillis(String input) {
        Parsed parsed = SanctionDuration.parse(input);
        return assertInstanceOf(Parsed.Temporary.class, parsed).millis();
    }
}
