package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckActivationPolicy;
import dev.btc.core.api.integrity.IntegrityAPI.CheckDefinition;
import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationModel;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design D14: a session's origin widens the truth model and never exempts.
 *
 * <p>Two claims are worth a test. First, nothing about the player — name, UUID shape — is ever read
 * to guess an origin: only a server-side binding counts, and the absence of one is {@code UNKNOWN}.
 * Second, an unknown origin keeps the movement family from acting while leaving every other family
 * untouched, because a Java prediction enforced on a Bedrock player flags honest people.
 */
class PlatformRegistryTest {

    /** The UUID shape Floodgate gives Bedrock players. A Java client can choose to look like this. */
    private static final UUID FLOODGATE_SHAPED = UUID.fromString("00000000-0000-0000-0009-01f4c1a2b3c4");

    private PlatformRegistry platforms;
    private CheckRegistry checks;
    private CheckId movement;
    private CheckId combat;

    @BeforeEach
    void setUp() {
        platforms = new PlatformRegistry();
        checks = new CheckRegistry();
        Plugin owner = Mockito.mock(Plugin.class);
        movement = new CheckId("test", "speed");
        combat = new CheckId("test", "reach");
        ViolationModel armed = new ViolationModel(1.0, 10.0, 20.0, false);
        checks.register(owner, new CheckDefinition(movement, CheckGroup.MOVEMENT, CheckActivationPolicy.always(), armed));
        checks.register(owner, new CheckDefinition(combat, CheckGroup.COMBAT, CheckActivationPolicy.always(), armed));
    }

    @Test
    @DisplayName("a Floodgate-shaped UUID with no binding is UNKNOWN, not BEDROCK")
    void shapeOfTheUuidProvesNothing() {
        // If this ever returns BEDROCK, someone taught the registry to guess — and a Java client
        // choosing that UUID shape just bought itself a wider movement margin.
        assertEquals(ClientPlatform.UNKNOWN, platforms.platformOf(FLOODGATE_SHAPED));
        assertEquals(ClientPlatform.UNKNOWN, platforms.platformOf(UUID.randomUUID()));
        assertEquals(ClientPlatform.UNKNOWN, platforms.platformOf(null));
    }

    @Test
    @DisplayName("only a server-side binding names a platform, and it names who vouched")
    void bindingIsTheOnlySource() {
        platforms.bind(FLOODGATE_SHAPED, ClientPlatform.BEDROCK, "btc:bridge/v2");

        assertEquals(ClientPlatform.BEDROCK, platforms.platformOf(FLOODGATE_SHAPED));
        assertEquals("btc:bridge/v2", platforms.sourceOf(FLOODGATE_SHAPED).orElseThrow());
    }

    @Test
    @DisplayName("UNKNOWN cannot be bound: it is the absence of a binding, not a value of one")
    void unknownIsNotBindable() {
        assertThrows(IllegalArgumentException.class,
            () -> platforms.bind(UUID.randomUUID(), ClientPlatform.UNKNOWN, "btc:bridge/v2"));
        assertThrows(IllegalArgumentException.class,
            () -> platforms.bind(UUID.randomUUID(), ClientPlatform.JAVA, " "));
    }

    @Test
    @DisplayName("an unknown origin holds the movement family in observation, and nothing else")
    void unknownHoldsMovementOnly() {
        // Same level, same thresholds: the origin alone decides whether movement may act.
        assertEquals(CheckRegistry.Response.NONE, checks.responseFor(movement, 25.0, ClientPlatform.UNKNOWN));
        assertEquals(CheckRegistry.Response.RESTRICT, checks.responseFor(movement, 25.0, ClientPlatform.JAVA));
        assertEquals(CheckRegistry.Response.RESTRICT, checks.responseFor(movement, 25.0, ClientPlatform.BEDROCK),
            "a bound Bedrock session is treated like a Java one past its margin — the origin never exempts");

        // Combat never reads the origin: Geyser does not alter what reach measures.
        assertEquals(CheckRegistry.Response.RESTRICT, checks.responseFor(combat, 25.0, ClientPlatform.UNKNOWN));
    }

    @Test
    @DisplayName("a missing binding is journalled once per session, again after a rebind is lost")
    void missingBindingJournalledOnce() {
        UUID player = UUID.randomUUID();

        assertTrue(platforms.shouldJournalMissing(player));
        assertFalse(platforms.shouldJournalMissing(player), "one line per session, not one per packet");

        platforms.bind(player, ClientPlatform.JAVA, "btc:bridge/v2");
        assertFalse(platforms.shouldJournalMissing(player), "a bound session has nothing missing");

        platforms.clearPlayer(player);
        assertTrue(platforms.shouldJournalMissing(player), "a new session is a new incident");
    }
}
