package dev.btc.core.world;

import dev.btc.core.util.WorldPatternMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a portal may exist in a world. Pure: no server, no Bukkit type, no I/O.
 *
 * <p>The listener that feeds it lives in the bundled plugin; keeping the decision here is what
 * makes it testable without a server, and it is an immutable snapshot so a Folia region thread
 * can read it while nothing is written to it. A reload replaces the whole policy, never a field.
 *
 * <p>Two questions are asked of it:
 * <ul>
 *   <li>{@link #onCreate} - may a portal be created here, for this reason ({@code PortalCreateEvent});</li>
 *   <li>{@link #onTravel} - may an entity use an already existing Nether portal here
 *       ({@code EntityPortalReadyEvent}), which only matters once creation is refused somewhere
 *       a portal was already standing.</li>
 * </ul>
 *
 * <p>In {@link Mode#OBSERVE} the verdict is computed and reported but never acts: {@link
 * Decision#cancel()} stays {@code false}. That mode exists to measure before refusing anything.
 */
public final class PortalPolicy {

    /** What the policy does with a refusal. */
    public enum Mode {
        /** Judge and report, cancel nothing. */
        OBSERVE,
        /** Judge, report and cancel what the policy refuses. */
        ENFORCE;

        /**
         * Reads a configured value, case-insensitively.
         *
         * <p>An unreadable value falls back to the caller's default rather than throwing: this runs
         * at server start, and a typo in {@code mode} must not take the server down. The fallback
         * the caller passes is {@link #OBSERVE}, the direction in which a mistake blocks nothing.
         */
        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) return fallback;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "observe" -> OBSERVE;
                case "enforce" -> ENFORCE;
                default -> fallback;
            };
        }
    }

    /** Why a verdict came out the way it did; {@link #key()} is what the journal prints. */
    public enum Rule {
        /** The reason is listed in {@code exempt-reasons}: never refused, whatever the world. */
        EXEMPT_REASON("exempt-reason", true),
        /** The world matches an {@code allowed-worlds} pattern. */
        ALLOWED_WORLD("allowed-world", true),
        /** Neither of the above: the portal has no business existing here. */
        OUTSIDE_ALLOWED_WORLDS("outside-allowed-worlds", false),
        /** Travel through an existing portal is not gated ({@code travel.deny-outside-allowed-worlds} off). */
        TRAVEL_GATE_OFF("travel-gate-off", true),
        /** Not a Nether portal: the policy never stands between an entity and the End. */
        NOT_NETHER("not-nether", true);

        private final String key;
        private final boolean permits;

        Rule(String key, boolean permits) {
            this.key = key;
            this.permits = permits;
        }

        public String key() {
            return key;
        }
    }

    /**
     * The outcome of one question.
     *
     * @param rule   which rule decided
     * @param cancel whether the caller must cancel the event; always {@code false} in observe mode
     */
    public record Decision(Rule rule, boolean cancel) {

        /** Whether the policy, enforced or not, accepts the portal / the travel. */
        public boolean permitted() {
            return rule.permits;
        }
    }

    /** {@code CreateReason} names the server knows today, to warn about a typo in {@code exempt-reasons}. */
    public static final Set<String> KNOWN_REASONS = Set.of("FIRE", "NETHER_PAIR", "END_PLATFORM");

    /**
     * The delivered default: observe, nothing allowed, the End platform exempt, travel ungated.
     * Observing means no portal is ever refused until an operator says {@code enforce}.
     */
    public static final PortalPolicy DEFAULT =
            new PortalPolicy(Mode.OBSERVE, List.of(), Set.of("END_PLATFORM"), false);

    private final Mode mode;
    private final List<String> allowedWorlds;
    private final Set<String> exemptReasons;
    private final boolean denyTravelOutsideAllowedWorlds;

    /**
     * @param mode                           observe or enforce
     * @param allowedWorlds                  world patterns where creation is permitted
     *                                       ({@link WorldPatternMatcher} syntax); empty means none
     * @param exemptReasons                  {@code CreateReason} names never refused; case-insensitive
     * @param denyTravelOutsideAllowedWorlds whether a Nether portal standing outside the allowed
     *                                       worlds stops working
     */
    public PortalPolicy(
            Mode mode,
            List<String> allowedWorlds,
            Set<String> exemptReasons,
            boolean denyTravelOutsideAllowedWorlds) {
        this.mode = mode;
        this.allowedWorlds = List.copyOf(allowedWorlds);
        this.exemptReasons = upperCased(exemptReasons);
        this.denyTravelOutsideAllowedWorlds = denyTravelOutsideAllowedWorlds;
    }

    public Mode mode() {
        return mode;
    }

    public List<String> allowedWorlds() {
        return allowedWorlds;
    }

    public Set<String> exemptReasons() {
        return exemptReasons;
    }

    public boolean denyTravelOutsideAllowedWorlds() {
        return denyTravelOutsideAllowedWorlds;
    }

    /**
     * May a portal be created in {@code world} for {@code reason}?
     *
     * <p>The reason is checked first: the End platform is generated by the game when an entity
     * enters an End portal, and refusing it would strand whoever just went through.
     */
    public Decision onCreate(String world, String reason) {
        if (reason != null && exemptReasons.contains(reason.toUpperCase(Locale.ROOT))) {
            return new Decision(Rule.EXEMPT_REASON, false);
        }
        if (WorldPatternMatcher.matchesAny(world, allowedWorlds)) {
            return new Decision(Rule.ALLOWED_WORLD, false);
        }
        return new Decision(Rule.OUTSIDE_ALLOWED_WORLDS, mode == Mode.ENFORCE);
    }

    /**
     * May an entity use an existing portal that stands in {@code world}?
     *
     * @param nether whether it is a Nether portal; every other kind is always let through
     */
    public Decision onTravel(String world, boolean nether) {
        if (!nether) {
            return new Decision(Rule.NOT_NETHER, false);
        }
        if (WorldPatternMatcher.matchesAny(world, allowedWorlds)) {
            return new Decision(Rule.ALLOWED_WORLD, false);
        }
        if (!denyTravelOutsideAllowedWorlds) {
            return new Decision(Rule.TRAVEL_GATE_OFF, false);
        }
        return new Decision(Rule.OUTSIDE_ALLOWED_WORLDS, mode == Mode.ENFORCE);
    }

    /**
     * One {@code key=value} journal line.
     *
     * <p>Values are reduced to {@code [A-Za-z0-9_.:*-]} and every other character becomes
     * {@code _}: a custom entity name is player-controlled text, and a line that a name can
     * extend with spaces, quotes or a newline is a line that can be forged.
     *
     * @param event    what happened, for instance {@code create} or {@code travel}
     * @param decision the verdict
     * @param fields   what the listener knows, in the order it wants printed; {@code null} values are skipped
     */
    public String logLine(String event, Decision decision, Map<String, ?> fields) {
        StringBuilder line = new StringBuilder("[Portals] event=").append(sanitize(event))
                .append(" mode=").append(mode.name().toLowerCase(Locale.ROOT))
                .append(" verdict=").append(decision.permitted() ? "permit" : "deny")
                .append(" rule=").append(decision.rule().key())
                .append(" action=").append(decision.cancel() ? "cancelled" : "none");
        fields.forEach((key, value) -> {
            if (value != null) {
                line.append(' ').append(sanitize(key)).append('=').append(sanitize(String.valueOf(value)));
            }
        });
        return line.toString();
    }

    /** The configured reasons this build does not know, so a typo is reported instead of ignored. */
    public static List<String> unknownReasons(Set<String> configured) {
        List<String> unknown = new ArrayList<>();
        for (String reason : new TreeSet<>(upperCased(configured))) {
            if (!KNOWN_REASONS.contains(reason)) unknown.add(reason);
        }
        return unknown;
    }

    private static String sanitize(String raw) {
        if (raw.isEmpty()) return "_";
        StringBuilder clean = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ':' || c == '*' || c == '-';
            clean.append(safe ? c : '_');
        }
        return clean.toString();
    }

    private static Set<String> upperCased(Set<String> raw) {
        Set<String> upper = new TreeSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) upper.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(upper);
    }
}
