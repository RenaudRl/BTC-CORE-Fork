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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who is punished at logout. The quit reason is not an input: a client can provoke a kick (invalid
 * packet, flying, spam), so only a departure BTC-CORE declares itself, or a server stop, is exempt.
 */
class CombatLogManagerTest {

    private Server server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = mock(Server.class);
        when(this.server.getLogger()).thenReturn(Logger.getAnonymousLogger());
        this.player = mock(Player.class);
        when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(this.player.getName()).thenReturn("tester");
        when(this.player.getServer()).thenReturn(this.server);
        CombatLogManager.onTickStart(100L);
        CombatLogManager.tagPlayer(this.player);
    }

    @Test
    @DisplayName("a tagged player who disconnects is killed")
    void disconnectWhileTaggedIsPunished() {
        assertTrue(CombatLogManager.handleLogout(this.player));
        verify(this.player).setHealth(0);
    }

    @Test
    @DisplayName("a kick nobody declared (client-provoked: bad packet, flying, spam) still kills")
    void undeclaredKickIsPunished() {
        // The quit listener makes this same call for a KICKED quit: the reason is not an input.
        assertTrue(CombatLogManager.handleLogout(this.player));
        verify(this.player).setHealth(0);
    }

    @Test
    @DisplayName("a sanction kick declared before kick() does not kill")
    void declaredSanctionKickIsNotPunished() {
        CombatLogManager.departureDecidedByServer(this.player);
        assertFalse(CombatLogManager.handleLogout(this.player));
        verify(this.player, never()).setHealth(anyDouble());
    }

    @Test
    @DisplayName("a hit landing between the declared kick and the quit cannot re-arm the punishment")
    void hitAfterDeclaredKickDoesNotRetag() {
        CombatLogManager.departureDecidedByServer(this.player);
        CombatLogManager.tagPlayer(this.player);
        assertFalse(CombatLogManager.isInCombat(this.player));
        assertFalse(CombatLogManager.handleLogout(this.player));
        verify(this.player, never()).setHealth(anyDouble());
    }

    @Test
    @DisplayName("the exemption ends with the quit: the same account tagged next session is punished")
    void exemptionDoesNotOutliveTheSession() {
        CombatLogManager.departureDecidedByServer(this.player);
        CombatLogManager.handleLogout(this.player);
        CombatLogManager.tagPlayer(this.player);
        assertTrue(CombatLogManager.handleLogout(this.player));
        verify(this.player).setHealth(0);
    }

    @Test
    @DisplayName("a server stop does not kill")
    void serverStopIsNotPunished() {
        when(this.server.isStopping()).thenReturn(true);
        assertFalse(CombatLogManager.handleLogout(this.player));
        verify(this.player, never()).setHealth(anyDouble());
    }

    @Test
    @DisplayName("a proxy transfer asked by the server (untagged first) does not kill")
    void untaggedTransferIsNotPunished() {
        CombatLogManager.untagPlayer(this.player);
        assertFalse(CombatLogManager.handleLogout(this.player));
        verify(this.player, never()).setHealth(anyDouble());
    }
}
