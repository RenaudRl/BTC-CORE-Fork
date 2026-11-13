package dev.btc.core.integrity.sanction;

import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Map;
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

        /**
         * Unparseable. The refusal is a text key and its arguments, so that it reaches the moderator in
         * their language; what the moderator typed is inside the arguments as plain text.
         */
        record Invalid(PlayerText text, Map<String, Component> arguments) implements Parsed {

            /** The English wording, for logs and tests. */
            public String reason() {
                return PlayerTexts.plainDefault(text, arguments);
            }
        }

        /** A refusal whose text takes no argument. */
        static Invalid invalid(PlayerText text) {
            return new Invalid(text, Map.of());
        }

        /** A refusal naming what was typed. */
        static Invalid invalid(PlayerText text, String name, String typed) {
            return new Invalid(text, Map.of(name, Component.text(typed)));
        }
    }

    /**
     * Parses a moderator-typed duration.
     *
     * <p>A bare number is refused on purpose. Guessing a unit is how a ten-minute mute becomes a
     * ten-second one, or a seven-day ban a seven-second one.
     */
    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_MISSING);
        }
        String value = input.trim().toLowerCase(Locale.ROOT);

        if (value.equals("perm") || value.equals("permanent") || value.equals("forever")) {
            return new Parsed.Permanent();
        }

        char unit = value.charAt(value.length() - 1);
        // A trailing digit means the moderator typed an amount and stopped. That is the dangerous
        // input, so it gets its own message naming what is missing rather than the generic refusal.
        if (Character.isDigit(unit)) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_NO_UNIT, "input", input);
        }
        String number = value.substring(0, value.length() - 1);
        if (number.isEmpty()) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_NO_AMOUNT, "input", input);
        }

        long amount;
        try {
            amount = Long.parseLong(number);
        } catch (NumberFormatException notANumber) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_NOT_A_DURATION, "input", input);
        }
        if (amount <= 0) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_NOT_POSITIVE);
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
            return Parsed.invalid(PlayerText.DURATION_ERROR_UNKNOWN_UNIT, "unit", String.valueOf(unit));
        }

        // Overflow is not theoretical here: `9999999999999999999d` parses as a long and then wraps
        // into a past instant, which would produce a sanction that is already expired.
        if (amount > MAX_MILLIS / multiplier) {
            return Parsed.invalid(PlayerText.DURATION_ERROR_TOO_LONG);
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

    /**
     * How much time a sanction has left: a text key and the amounts it names, not a sentence. The words
     * come from {@link PlayerTexts} when the sanction is shown, so each viewer reads it in their own
     * language; a sentence built here would be English for everyone.
     */
    public record Remaining(PlayerText text, Map<String, Component> arguments) {

        private static Remaining of(PlayerText text, String name, long amount) {
            return new Remaining(text, Map.of(name, Component.text(amount)));
        }

        private static Remaining of(PlayerText text, String firstName, long first, String secondName, long second) {
            return new Remaining(text, Map.of(firstName, Component.text(first), secondName, Component.text(second)));
        }
    }

    /** What is left until {@code expiresAtMillis}, as data; see {@link Remaining}. */
    public static Remaining remaining(long nowMillis, Optional<Long> expiresAtMillis) {
        if (expiresAtMillis.isEmpty()) {
            return new Remaining(PlayerText.DURATION_PERMANENT, Map.of());
        }
        long left = expiresAtMillis.get() - nowMillis;
        if (left <= 0) {
            return new Remaining(PlayerText.DURATION_EXPIRED, Map.of());
        }
        if (left >= DAY_MILLIS) {
            return Remaining.of(PlayerText.DURATION_DAYS_HOURS,
                "days", left / DAY_MILLIS, "hours", (left % DAY_MILLIS) / HOUR_MILLIS);
        }
        if (left >= HOUR_MILLIS) {
            return Remaining.of(PlayerText.DURATION_HOURS_MINUTES,
                "hours", left / HOUR_MILLIS, "minutes", (left % HOUR_MILLIS) / MINUTE_MILLIS);
        }
        if (left >= MINUTE_MILLIS) {
            return Remaining.of(PlayerText.DURATION_MINUTES, "minutes", left / MINUTE_MILLIS);
        }
        return Remaining.of(PlayerText.DURATION_SECONDS, "seconds", Math.max(1, left / SECOND_MILLIS));
    }

    /** The remaining time in English, for the places that speak English by decision ({@code /sentinel}). */
    public static String describeRemaining(long nowMillis, Optional<Long> expiresAtMillis) {
        Remaining remaining = remaining(nowMillis, expiresAtMillis);
        return PlayerTexts.plainDefault(remaining.text(), remaining.arguments());
    }
}
