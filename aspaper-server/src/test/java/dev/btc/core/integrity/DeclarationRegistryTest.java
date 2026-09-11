package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.ClientTerrainHandle;
import dev.btc.core.api.integrity.IntegrityAPI.CustomMechanic;
import dev.btc.core.api.integrity.IntegrityAPI.MechanicType;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.TerrainDivergence;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link DeclarationRegistry}.
 *
 * <p>These cover the two capabilities that separate this platform from a third-party anticheat: a
 * declared teleport authorises one arrival rather than opening a window, and a declared terrain
 * divergence is what keeps phase and fall checks from being permanently wrong about phantom blocks.
 */
class DeclarationRegistryTest {

    private DeclarationRegistry registry;
    private UUID player;
    private World world;

    @BeforeEach
    void setUp() {
        registry = new DeclarationRegistry();
        player = UUID.randomUUID();
        world = Mockito.mock(World.class);
        Mockito.when(world.getName()).thenReturn("island_42");
    }

    @Test
    @DisplayName("a declared velocity is readable while it lives")
    void declaredVelocityIsReadable() {
        Vector push = new Vector(0.4, 0.8, 0.0);
        registry.declareMechanic(player,
            new CustomMechanic(MechanicType.LAUNCH, Optional.of(push)), Duration.ofSeconds(2));

        Optional<Vector> declared = registry.declaredVelocity(player);
        assertTrue(declared.isPresent());
        assertEquals(push, declared.get());
    }

    @Test
    @DisplayName("a declared velocity stops applying once it expires")
    void declaredVelocityExpires() throws InterruptedException {
        registry.declareMechanic(player,
            new CustomMechanic(MechanicType.LAUNCH, Optional.of(new Vector(1, 1, 1))),
            Duration.ofMillis(40));
        Thread.sleep(80);

        assertTrue(registry.declaredVelocity(player).isEmpty(),
            "a declaration must not widen tolerance forever");
    }

    @Test
    @DisplayName("arriving where declared consumes the declaration")
    void declaredTeleportIsConsumed() {
        Location destination = new Location(world, 100.0, 65.0, -20.0);
        registry.declareTeleport(player, destination, TeleportKind.ARENA_PHASE, Duration.ofSeconds(2));

        assertTrue(registry.consumeDeclaredTeleport(player, new Location(world, 100.0, 65.0, -20.0)));

        // One declaration authorises one relocation. If this goes red, a declared teleport becomes a
        // window during which any relocation passes — exactly the hole a blanket exemption would open.
        assertFalse(registry.consumeDeclaredTeleport(player, new Location(world, 100.0, 65.0, -20.0)),
            "a declaration authorises one arrival, not a window");
    }

    @Test
    @DisplayName("arriving elsewhere does not match a declaration")
    void differentDestinationIsRefused() {
        registry.declareTeleport(player, new Location(world, 100.0, 65.0, -20.0),
            TeleportKind.SCRIPTED, Duration.ofSeconds(2));

        assertFalse(registry.consumeDeclaredTeleport(player, new Location(world, 340.0, 65.0, -20.0)),
            "a declared destination must not authorise arrival somewhere else");
        // The declaration is still available for the arrival it actually describes.
        assertTrue(registry.consumeDeclaredTeleport(player, new Location(world, 100.0, 65.0, -20.0)));
    }

    @Test
    @DisplayName("a declaration in one world does not authorise arrival in another")
    void otherWorldIsRefused() {
        World elsewhere = Mockito.mock(World.class);
        Mockito.when(elsewhere.getName()).thenReturn("island_7");

        registry.declareTeleport(player, new Location(world, 10.0, 70.0, 10.0),
            TeleportKind.WORLD_TRANSFER, Duration.ofSeconds(2));

        assertFalse(registry.consumeDeclaredTeleport(player, new Location(elsewhere, 10.0, 70.0, 10.0)));
    }

    @Test
    @DisplayName("declared terrain covers its region and nothing else")
    void declaredTerrainIsBounded() {
        BoundingBox region = new BoundingBox(0, 60, 0, 16, 80, 16);
        try (ClientTerrainHandle handle =
                 registry.declareTerrain(player, region, TerrainDivergence.PHANTOM_SOLID, Duration.ofSeconds(5))) {

            assertTrue(handle.isActive());
            assertTrue(registry.isClientTerrainDeclared(player, 8, 70, 8),
                "a player standing on a phantom block inside the region is not phasing");
            assertFalse(registry.isClientTerrainDeclared(player, 100, 70, 100),
                "outside the region, collision checks must apply normally again");
        }

        assertFalse(registry.isClientTerrainDeclared(player, 8, 70, 8),
            "closing the handle re-arms collision checks");
    }

    @Test
    @DisplayName("a dynamic range provider that throws falls back rather than opening a hole")
    void throwingProviderFallsBack() {
        org.bukkit.NamespacedKey item = new org.bukkit.NamespacedKey("btc", "pistol");
        org.bukkit.plugin.Plugin owner = Mockito.mock(org.bukkit.plugin.Plugin.class);

        registry.registerRangedWeapon(owner, item, 12.0);
        registry.registerDynamicRangeProvider(owner, item, (p, t) -> {
            throw new IllegalStateException("extension bug");
        });

        DeclarationRegistry.OptionalDoubleRange range = registry.declaredRange(item, null, null);

        // An extension's bug must degrade to the fixed declaration, never to "no bound at all".
        assertTrue(range.present());
        assertEquals(12.0, range.value());
    }

    @Test
    @DisplayName("forgetting a player drops every declaration held for them")
    void clearingPlayerDropsDeclarations() {
        registry.declareMechanic(player,
            new CustomMechanic(MechanicType.KNOCKBACK, Optional.of(new Vector(1, 0, 0))),
            Duration.ofMinutes(1));
        registry.declareTerrain(player, new BoundingBox(0, 0, 0, 4, 4, 4),
            TerrainDivergence.PHANTOM_AIR, Duration.ofMinutes(1));

        registry.clearPlayer(player);

        assertTrue(registry.declaredVelocity(player).isEmpty());
        assertFalse(registry.isClientTerrainDeclared(player, 2, 2, 2));
    }
}
