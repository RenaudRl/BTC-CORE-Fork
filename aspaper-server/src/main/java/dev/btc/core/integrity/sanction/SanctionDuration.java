package dev.btc.core.integrity.sanction;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses and renders the durations moderators type.
 *
 * <p>Kept separate from the commands because this is where a mistake is expensive and silent: a
 * moderator typing {@code 7} and getting seconds instead of days learns about it a week later, from
 * the player. So the unit is mandatory, and anything unparseable is refused rather than guessed.
 *
 * <p>Accepted forms: {@code 30s}, {@code 10m}, {@code 2h}, {@code 7d}, {@code 4w}, and {@code perm}
 * (also {@code permanent}, {@code forever}) for no expiry.
 */
public final class SanctionDuration {

    private static final long SECOND_MILLIS = 1000L;
    private static final long MINUTE_MILLIS = 60 * SECOND_MILLIS;
    private static final long HOUR_MILLIS = 60 * MINUTE_MILLIS;
    private static final long DAY_MILLIS = 24 * HOUR_MILLIS;
    private static final long WEEK_MILLIS = 7 * DAY_MILLIS;

    /** Ten years. Beyond this, a moderator means "permanent" and should say so. */
    private static final long MAX_MILLIS = 3650 * DAY_MILLIS;

    private SanctionDuration() {}

    /** Outcome of parsing: either a duration, an explicit permanence, or a refusal. */
    public sealed interface Parsed {

        /** A bounded duration. */
        record Temporary(long millis) implements Parsed {}

        /** Explicitly permanent — no expiry. */
        record Permanent() implements Parsed {}

        /** Unparseable, with the reason to show the moderator. */
        record Invalid(String reason) implements Parsed {}
    }

    /**
     * Parses a moderator-typed duration.
     *
     * <p>A bare number is refused on purpose. Guessing a unit is how a ten-minute mute becomes a
     * ten-second one, or a seven-day ban a seven-second one.
     */
    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            return new Parsed.Invalid("no duration given; use 10m, 2h, 7d, or perm");
        }
        String value = input.trim().toLowerCase(Locale.ROOT);

        if (value.equals("perm") || value.equals("permanent") || value.equals("forever")) {
            return new Parsed.Permanent();
        }

        char unit = value.charAt(value.length() - 1);
        // A trailing digit means the moderator typed an amount and stopped. That is the dangerous
        // input, so it gets its own message naming what is missing rather than the generic refusal.
        if (Character.isDigit(unit)) {
            return new Parsed.Invalid(
                "'" + input + "' has no unit; use s, m, h, d, w (as in 10m or 7d), or perm");
        }
        String number = value.substring(0, value.length() - 1);
        if (number.isEmpty()) {
            return new Parsed.Invalid("'" + input + "' has no amount; try 10m or 7d");
        }

        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException notANumber) {
            return new Parsed.Invalid("'" + input + "' is not a duration; use 10m, 2h, 7d, or perm");
        }
        if (amount <= 0) {
            return new Parsed.Invalid("a duration must be positive");
        }

        long multiplier = switch (unit) {
            case 's' -> SECOND_MILLIS;
            case 'm' -> MINUTE_MILLIS;
            case 'h' -> HOUR_MILLIS;
            case 'd' -> DAY_MILLIS;
            case 'w' -> WEEK_MILLIS;
            default -> -1;
        };
        if (multiplier < 0) {
            return new Parsed.Invalid("unknown unit '" + unit + "'; use s, m, h, d, w, or perm");
        }

        // Overflow is not theoretical here: `9999999999999999999d` parses as a long and then wraps
        // into a past instant, which would produce a sanction that is already expired.
        if (amount > MAX_MILLIS / multiplier) {
            return new Parsed.Invalid("that is longer than ten years; say 'perm' if you mean permanent");
        }
        return new Parsed.Temporary(amount * multiplier);
    }

    /** Expiry instant for a parsed duration, or empty when permanent. */
    public static Optional<Long> expiryFrom(long nowMillis, Parsed parsed) {
        if (parsed instanceof Parsed.Temporary temporary) {
            return Optional.of(nowMillis + temporary.millis());
        }
        return Optional.empty();
    }

    /** Renders a remaining duration for a player or a staff list. */
    public static String describeRemaining(long nowMillis, Optional<Long> expiresAtMillis) {
        if (expiresAtMillis.isEmpty()) {
            return "permanent";
        }
        long left = expiresAtMillis.get() - nowMillis;
        if (left <= 0) {
            return "expired";
        }
        if (left >= DAY_MILLIS) {
            return (left / DAY_MILLIS) + "d " + ((left % DAY_MILLIS) / HOUR_MILLIS) + "h";
        }
        if (left >= HOUR_MILLIS) {
            return (left / HOUR_MILLIS) + "h " + ((left % HOUR_MILLIS) / MINUTE_MILLIS) + "m";
        }
        if (left >= MINUTE_MILLIS) {
            return (left / MINUTE_MILLIS) + "m";
        }
        return Math.max(1, left / SECOND_MILLIS) + "s";
    }
}
