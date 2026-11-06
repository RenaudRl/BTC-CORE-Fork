package dev.btc.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Who is punished at logout: a connection the player ended, never one the server ended. */
class CombatLogManagerTest {

    private Player player;

    @BeforeEach
    void setUp() {
        final Server server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getAnonymousLogger());
        this.player = mock(Player.class);
        when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.player.getName()).thenReturn("tester");
        when(this.player.getServer()).thenReturn(server);
        CombatLogManager.onTickStart(100L);
        CombatLogManager.tagPlayer(this.player);
    }

    @Test
    @DisplayName("a tagged player who closes the game is killed")
    void disconnectWhileTaggedIsPunished() {
        assertTrue(CombatLogManager.handleLogout(this.player, QuitReason.DISCONNECTED));
        verify(this.player).setHealth(0);
    }

    @Test
    @DisplayName("a tagged player whose connection times out is killed")
    void timeoutWhileTaggedIsPunished() {
        assertTrue(CombatLogManager.handleLogout(this.player, QuitReason.TIMED_OUT));
        verify(this.player).setHealth(0);
    }

    @Test
    @DisplayName("a kick (Sentinel, a sanction, a shutdown) never kills, and clears the tag")
    void kickWhileTaggedIsNotPunished() {
        assertFalse(CombatLogManager.handleLogout(this.player, QuitReason.KICKED));
        verify(this.player, never()).setHealth(anyDouble());
        assertFalse(CombatLogManager.isInCombat(this.player));
    }

    @Test
    @DisplayName("an erroneous state decided by the server never kills")
    void erroneousStateWhileTaggedIsNotPunished() {
        assertFalse(CombatLogManager.handleLogout(this.player, QuitReason.ERRONEOUS_STATE));
        verify(this.player, never()).setHealth(anyDouble());
    }

    @Test
    @DisplayName("an untagged player who closes the game is left alone")
    void disconnectWhileUntaggedIsNotPunished() {
        CombatLogManager.untagPlayer(this.player);
        assertFalse(CombatLogManager.handleLogout(this.player, QuitReason.DISCONNECTED));
        verify(this.player, never()).setHealth(anyDouble());
    }
}
