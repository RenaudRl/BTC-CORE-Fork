package dev.btc.core.world;

import dev.btc.core.world.PortalPolicy.Decision;
import dev.btc.core.world.PortalPolicy.Mode;
import dev.btc.core.world.PortalPolicy.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portal policy decides, in one pure place, what a listener would otherwise decide inline.
 *
 * <p>Three claims matter. Observing must never cancel, whatever the verdict: that is the whole
 * point of shipping it as the default. Enforcing must refuse everything it was not told to allow,
 * which means an empty allow-list refuses everything. And the game's own End platform must survive
 * both, or the first player through an End portal is stranded.
 */
class PortalPolicyTest {

    private static PortalPolicy policy(Mode mode, List<String> worlds) {
        return new PortalPolicy(mode, worlds, Set.of("END_PLATFORM"), false);
    }

    @Test
    @DisplayName("the delivered default observes, allows no world and exempts only the End platform")
    void deliveredDefault() {
        PortalPolicy defaults = PortalPolicy.DEFAULT;

        assertEquals(Mode.OBSERVE, defaults.mode());
        assertEquals(List.of(), defaults.allowedWorlds());
        assertEquals(Set.of("END_PLATFORM"), defaults.exemptReasons());
        assertFalse(defaults.denyTravelOutsideAllowedWorlds());
    }

    @Test
    @DisplayName("observe judges a creation as denied but never asks for a cancellation")
    void observeNeverCancels() {
        Decision verdict = PortalPolicy.DEFAULT.onCreate("world", "FIRE");

        assertEquals(Rule.OUTSIDE_ALLOWED_WORLDS, verdict.rule());
        assertFalse(verdict.permitted(), "the verdict must still say deny, so the constat can count it");
        assertFalse(verdict.cancel(), "observe must not cancel");
    }

    @Test
    @DisplayName("enforce with an empty allow-list refuses every creation")
    void enforceEmptyListRefusesEverything() {
        PortalPolicy enforcing = policy(Mode.ENFORCE, List.of());

        for (String reason : List.of("FIRE", "NETHER_PAIR")) {
            Decision verdict = enforcing.onCreate("world", reason);
            assertTrue(verdict.cancel(), reason + " must be cancelled");
            assertFalse(verdict.permitted());
        }
    }

    @Test
    @DisplayName("enforce permits a creation in an allowed world, exact name or pattern")
    void enforcePermitsAllowedWorlds() {
        PortalPolicy enforcing = policy(Mode.ENFORCE, List.of("td_nether_*", "test", "regex:^arch.*$"));

        Decision exact = enforcing.onCreate("test", "FIRE");
        Decision prefix = enforcing.onCreate("td_nether_1", "FIRE");
        Decision regex = enforcing.onCreate("archipel_metiers", "NETHER_PAIR");

        for (Decision verdict : List.of(exact, prefix, regex)) {
            assertEquals(Rule.ALLOWED_WORLD, verdict.rule());
            assertTrue(verdict.permitted());
            assertFalse(verdict.cancel());
        }
    }

    @Test
    @DisplayName("enforce still refuses a world that merely resembles an allowed one")
    void enforceRefusesLookalikes() {
        PortalPolicy enforcing = policy(Mode.ENFORCE, List.of("td_nether_*", "test"));

        assertTrue(enforcing.onCreate("td_end_1", "FIRE").cancel(), "different prefix");
        assertTrue(enforcing.onCreate("test2", "FIRE").cancel(), "an exact entry is not a prefix");
        assertTrue(enforcing.onCreate("world", "FIRE").cancel());
    }

    @Test
    @DisplayName("the End platform is exempt in both modes, in any world, whatever its case")
    void endPlatformIsExempt() {
        for (Mode mode : Mode.values()) {
            PortalPolicy subject = policy(mode, List.of());

            Decision verdict = subject.onCreate("world_the_end", "end_platform");

            assertEquals(Rule.EXEMPT_REASON, verdict.rule());
            assertTrue(verdict.permitted());
            assertFalse(verdict.cancel(), "the generated platform must never be cancelled in " + mode);
        }
    }

    @Test
    @DisplayName("an emptied exempt list exempts nothing, so the platform is refused when asked")
    void emptyExemptListExemptsNothing() {
        PortalPolicy strict = new PortalPolicy(Mode.ENFORCE, List.of(), Set.of(), false);

        assertTrue(strict.onCreate("world", "END_PLATFORM").cancel());
    }

    @Test
    @DisplayName("exempt reasons are matched whatever their case and surrounding spaces")
    void exemptReasonsAreNormalised() {
        PortalPolicy lenient = new PortalPolicy(Mode.ENFORCE, List.of(), Set.of(" fire "), false);

        assertFalse(lenient.onCreate("world", "FIRE").cancel());
        assertTrue(lenient.onCreate("world", "NETHER_PAIR").cancel(), "only the listed reason is exempt");
    }

    @Test
    @DisplayName("travel is never gated unless the operator asked for it")
    void travelGateIsOffByDefault() {
        PortalPolicy enforcing = policy(Mode.ENFORCE, List.of());

        Decision verdict = enforcing.onTravel("world", true);

        assertEquals(Rule.TRAVEL_GATE_OFF, verdict.rule());
        assertTrue(verdict.permitted());
        assertFalse(verdict.cancel());
    }

    @Test
    @DisplayName("travel through a Nether portal outside the allowed worlds is cancelled once gated and enforced")
    void gatedTravelIsCancelled() {
        PortalPolicy gated = new PortalPolicy(Mode.ENFORCE, List.of("btc_skyblock_*"), Set.of(), true);

        assertTrue(gated.onTravel("world", true).cancel());
        assertFalse(gated.onTravel("btc_skyblock_42", true).cancel(), "an allowed world keeps its portals");
    }

    @Test
    @DisplayName("travel gated but only observed is judged denied and never cancelled")
    void gatedTravelObservedDoesNotCancel() {
        PortalPolicy gated = new PortalPolicy(Mode.OBSERVE, List.of(), Set.of(), true);

        Decision verdict = gated.onTravel("world", true);

        assertFalse(verdict.permitted());
        assertFalse(verdict.cancel());
    }

    @Test
    @DisplayName("an End portal is never refused, gated or not, enforced or not")
    void endPortalIsNeverRefused() {
        PortalPolicy gated = new PortalPolicy(Mode.ENFORCE, List.of(), Set.of(), true);

        Decision verdict = gated.onTravel("world_the_end", false);

        assertEquals(Rule.NOT_NETHER, verdict.rule());
        assertFalse(verdict.cancel());
    }

    @Test
    @DisplayName("a mode is read case-insensitively and an unreadable one falls back instead of throwing")
    void modeParsing() {
        assertEquals(Mode.ENFORCE, Mode.parse(" Enforce ", Mode.OBSERVE));
        assertEquals(Mode.OBSERVE, Mode.parse("OBSERVE", Mode.ENFORCE));
        assertEquals(Mode.OBSERVE, Mode.parse("enforced", Mode.OBSERVE), "a typo must not enforce");
        assertEquals(Mode.OBSERVE, Mode.parse(null, Mode.OBSERVE));
        assertNull(Mode.parse("nope", null), "the caller can still detect an unreadable value");
    }

    @Test
    @DisplayName("an unknown exempt reason is reported, a known one is not")
    void unknownReasonsAreReported() {
        assertEquals(List.of("FIER"), PortalPolicy.unknownReasons(Set.of("fire", "FIER", "end_platform")));
        assertEquals(List.of(), PortalPolicy.unknownReasons(Set.of("FIRE", "NETHER_PAIR", "END_PLATFORM")));
    }

    @Test
    @DisplayName("the journal line is key=value, carries the verdict and the action, and keeps field order")
    void logLineShape() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("world", "td_nether_1");
        fields.put("reason", "FIRE");
        fields.put("x", 12);
        fields.put("y", 64);
        fields.put("z", -7);
        fields.put("player", "Steve");

        Decision verdict = PortalPolicy.DEFAULT.onCreate("td_nether_1", "FIRE");
        String observed = PortalPolicy.DEFAULT.logLine("create", verdict, fields);

        assertEquals("[Portals] event=create mode=observe verdict=deny rule=outside-allowed-worlds action=none"
                + " world=td_nether_1 reason=FIRE x=12 y=64 z=-7 player=Steve", observed);
    }

    @Test
    @DisplayName("an enforced refusal says action=cancelled")
    void logLineReportsCancellation() {
        PortalPolicy enforcing = policy(Mode.ENFORCE, List.of());
        Decision verdict = enforcing.onCreate("world", "FIRE");

        String line = enforcing.logLine("create", verdict, Map.of("world", "world"));

        assertTrue(line.contains("mode=enforce verdict=deny rule=outside-allowed-worlds action=cancelled"), line);
    }

    @Test
    @DisplayName("a player-controlled value cannot extend or forge the journal line")
    void logLineSanitisesValues() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", "Bob verdict=permit\nrule=forged");
        fields.put("skipped", null);
        fields.put("empty", "");

        String line = PortalPolicy.DEFAULT.logLine("create", new Decision(Rule.ALLOWED_WORLD, false), fields);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("skipped"), "a null field is left out");
        assertTrue(line.endsWith("entity=Bob_verdict_permit_rule_forged empty=_"), line);
        assertEquals(1, line.split(" verdict=").length - 1, "exactly one verdict key, the policy's own");
    }
}
