package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.VisibilityProvider;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.8: the engine never asks {@code Player#isInvisible()} for a targeting verdict; it asks the
 * features that decide what each viewer sees.
 *
 * <p>The claims that matter: one hide wins over any number of shows (features count their hides,
 * the engine must not undo one by trusting another); a provider that throws has no opinion (an
 * extension's bug is neither a hole nor a blindfold); and a provider leaves with its plugin.
 */
class VisibilityProviderTest {

    private DeclarationRegistry registry;
    private Plugin owner;
    private Player viewer;
    private UUID target;

    @BeforeEach
    void setUp() {
        registry = new DeclarationRegistry();
        owner = Mockito.mock(Plugin.class);
        viewer = Mockito.mock(Player.class);
        target = UUID.randomUUID();
    }

    private static VisibilityProvider saying(Boolean answer) {
        return (v, t) -> Optional.ofNullable(answer);
    }

    @Test
    @DisplayName("without a provider the engine has no opinion, and falls back on server state")
    void noProviderNoOpinion() {
        assertTrue(registry.canSee(viewer, target).isEmpty());
        assertFalse(registry.isPhantomEntity(viewer, 1_000_000_000));
    }

    @Test
    @DisplayName("one hide wins over any number of shows")
    void oneHideWins() {
        registry.registerVisibilityProvider(owner, saying(true));
        registry.registerVisibilityProvider(owner, saying(false));
        registry.registerVisibilityProvider(owner, saying(true));

        // If this ever says true, a cinematic hiding a player is undone by a visibility rule that
        // shows them — and a hit on someone the viewer cannot see passes as legitimate.
        assertEquals(Optional.of(false), registry.canSee(viewer, target));
    }

    @Test
    @DisplayName("a provider that throws has no opinion: neither a hole nor a blindfold")
    void throwingProviderIsIgnored() {
        registry.registerVisibilityProvider(owner, (v, t) -> { throw new IllegalStateException("bug"); });
        assertTrue(registry.canSee(viewer, target).isEmpty(), "a bug must not decide visibility");

        registry.registerVisibilityProvider(owner, saying(true));
        assertEquals(Optional.of(true), registry.canSee(viewer, target), "the sound provider still counts");
    }

    @Test
    @DisplayName("a phantom entity is one that any provider shows to this viewer")
    void phantomEntities() {
        registry.registerVisibilityProvider(owner, new VisibilityProvider() {
            @Override
            public Optional<Boolean> canSee(Player v, UUID t) {
                return Optional.empty();
            }

            @Override
            public boolean isPhantomEntity(Player v, int entityId) {
                return entityId == 1_000_000_007;
            }
        });

        assertTrue(registry.isPhantomEntity(viewer, 1_000_000_007));
        assertFalse(registry.isPhantomEntity(viewer, 42));
    }

    @Test
    @DisplayName("a provider leaves with its handle, and with its plugin")
    void providerLeaves() throws Exception {
        AutoCloseable handle = registry.registerVisibilityProvider(owner, saying(false));
        assertEquals(Optional.of(false), registry.canSee(viewer, target));

        handle.close();
        assertTrue(registry.canSee(viewer, target).isEmpty(), "a closed provider must not keep hiding");

        registry.registerVisibilityProvider(owner, saying(false));
        registry.clearOwner(owner);
        assertTrue(registry.canSee(viewer, target).isEmpty(),
            "a disabled plugin cannot close its handles; the registry does it for them");
    }
}
