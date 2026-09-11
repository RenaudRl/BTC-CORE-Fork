package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.CheckGroup;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionHandle;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason;
import dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link ExemptionRegistry}.
 *
 * <p>Each test here corresponds to a failure that a shipped anticheat API is documented to have: the
 * shared flag that lets one plugin undo another's exemption, the exemption that outlives the extension
 * that asked for it, the check disabled server-wide for one player's benefit. Removing the guard should
 * turn the matching test red — that is the point of writing them.
 *
 * <p>{@link ExemptionRegistry} deliberately touches no world state, so it is testable without a server.
 */
class ExemptionRegistryTest {

    private static final ExemptionScope MOVEMENT_LAUNCH =
        new ExemptionScope(CheckGroup.MOVEMENT, ExemptionReason.SCRIPTED_KNOCKBACK);

    private ExemptionRegistry registry;
    private Plugin pluginA;
    private Plugin pluginB;
    private UUID player;

    @BeforeEach
    void setUp() {
        registry = new ExemptionRegistry();
        pluginA = Mockito.mock(Plugin.class);
        pluginB = Mockito.mock(Plugin.class);
        Mockito.when(pluginA.getName()).thenReturn("ExtensionA");
        Mockito.when(pluginB.getName()).thenReturn("ExtensionB");
        player = UUID.randomUUID();
    }

    @Test
    @DisplayName("an exemption is active while its holder keeps it")
    void grantsWhileHeld() {
        try (ExemptionHandle handle = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5))) {
            assertTrue(handle.isActive());
            assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH));
        }
        assertFalse(registry.isExempted(player, MOVEMENT_LAUNCH));
    }

    @Test
    @DisplayName("closing one holder does not release another's exemption")
    void referenceCounted() {
        ExemptionHandle first = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5));
        ExemptionHandle second = registry.grant(pluginB, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5));
        assertEquals(2, registry.holderCount(player, MOVEMENT_LAUNCH));

        first.close();

        // The failure this guards against: extension A finishing its mechanic and blinding extension B
        // mid-flight, which is what a shared boolean would do.
        assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH),
            "second holder still needs the exemption");
        assertEquals(1, registry.holderCount(player, MOVEMENT_LAUNCH));

        second.close();
        assertFalse(registry.isExempted(player, MOVEMENT_LAUNCH));
    }

    @Test
    @DisplayName("a leaked handle expires on its own")
    void expiresWithoutClose() throws InterruptedException {
        registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofMillis(40));
        assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH));

        Thread.sleep(80);

        // No scheduler runs expiry: the read path must be what closes a hole left by a crashed
        // extension. If this goes red, a leaked handle lasts until restart.
        assertFalse(registry.isExempted(player, MOVEMENT_LAUNCH),
            "a handle that is never closed must not outlive its TTL");
    }

    @Test
    @DisplayName("closing twice is harmless")
    void closeIsIdempotent() {
        ExemptionHandle handle = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5));
        handle.close();
        handle.close();
        assertFalse(handle.isActive());
        assertEquals(0, registry.holderCount(player, MOVEMENT_LAUNCH));
    }

    @Test
    @DisplayName("a TTL is mandatory, positive and bounded")
    void ttlIsMandatory() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.grant(pluginA, player, MOVEMENT_LAUNCH, null));
        assertThrows(IllegalArgumentException.class,
            () -> registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
            () -> registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofHours(1)),
            "an unbounded exemption is a permanent hole");
    }

    @Test
    @DisplayName("an exemption is scoped to one player")
    void scopedToPlayer() {
        UUID other = UUID.randomUUID();
        try (ExemptionHandle ignored = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5))) {
            assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH));
            assertFalse(registry.isExempted(other, MOVEMENT_LAUNCH),
                "relaxing a check for one player must never relax it for everyone");
        }
    }

    @Test
    @DisplayName("relaxing one group leaves the others armed")
    void scopedToGroup() {
        ExemptionScope combat = new ExemptionScope(CheckGroup.COMBAT, ExemptionReason.SCRIPTED_KNOCKBACK);
        try (ExemptionHandle ignored = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5))) {
            assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH));
            assertFalse(registry.isExempted(player, combat));
        }
    }

    @Test
    @DisplayName("a grouped exemption is released as one unit")
    void groupedExemption() {
        List<CheckGroup> groups = List.of(CheckGroup.MOVEMENT, CheckGroup.COMBAT, CheckGroup.POSITION);
        ExemptionHandle handle =
            registry.grantAll(pluginA, player, groups, ExemptionReason.CINEMATIC, Duration.ofSeconds(5));

        for (CheckGroup group : groups) {
            assertTrue(registry.isExempted(player, new ExemptionScope(group, ExemptionReason.CINEMATIC)),
                group + " should be relaxed for the cinematic");
        }

        handle.close();

        for (CheckGroup group : groups) {
            assertFalse(registry.isExempted(player, new ExemptionScope(group, ExemptionReason.CINEMATIC)),
                group + " should be armed again once the cinematic ends");
        }
    }

    @Test
    @DisplayName("disabling a plugin drops the exemptions it held")
    void clearingOwnerReleasesItsHolds() {
        registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofMinutes(1));
        registry.grant(pluginB, player, MOVEMENT_LAUNCH, Duration.ofMinutes(1));

        registry.clearOwner(pluginA);

        // B's hold survives; A's does not. A disabled plugin cannot close its own handles, so the
        // platform has to do it, or an ordinary reload leaves a hole open for a minute.
        assertEquals(1, registry.holderCount(player, MOVEMENT_LAUNCH));
        assertTrue(registry.isExempted(player, MOVEMENT_LAUNCH));

        registry.clearOwner(pluginB);
        assertFalse(registry.isExempted(player, MOVEMENT_LAUNCH));
    }

    @Test
    @DisplayName("the active reason is reported, not merely the fact of exemption")
    void reportsReason() {
        try (ExemptionHandle ignored = registry.grant(pluginA, player, MOVEMENT_LAUNCH, Duration.ofSeconds(5))) {
            // The engine relaxes a different part of its evaluation depending on why; a boolean would
            // force it to relax everything.
            assertEquals(ExemptionReason.SCRIPTED_KNOCKBACK,
                registry.reasonFor(player, CheckGroup.MOVEMENT).orElse(null));
        }
        assertTrue(registry.reasonFor(player, CheckGroup.MOVEMENT).isEmpty());
    }
}
