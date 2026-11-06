package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionService;
import dev.btc.core.integrity.sanction.SanctionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which sanction a player is shown when several of a type apply to them (AD-090). */
class StandingOfTypeTest {

    private final UUID self = UUID.randomUUID();
    private final UUID alias = UUID.randomUUID();

    @Test
    @DisplayName("the sanction on the player's own account wins over one on an alias, whatever the order")
    void ownAccountComesFirst() {
        Sanction onAlias = mute(1, alias);
        Sanction own = mute(2, self);

        assertEquals(Optional.of(own), SanctionService.standingOfType(List.of(onAlias, own), self, SanctionType.MUTE));
        assertEquals(Optional.of(own), SanctionService.standingOfType(List.of(own, onAlias), self, SanctionType.MUTE));
    }

    @Test
    @DisplayName("an alias sanction still applies when the player has none of their own; other types are ignored")
    void aliasAppliesWhenAlone() {
        Sanction onAlias = mute(1, alias);

        assertEquals(Optional.of(onAlias), SanctionService.standingOfType(List.of(onAlias), self, SanctionType.MUTE));
        assertTrue(SanctionService.standingOfType(List.of(onAlias), self, SanctionType.BAN).isEmpty());
    }

    private static Sanction mute(long id, UUID player) {
        return new Sanction(Optional.of(id), Optional.of(player), Optional.empty(), SanctionType.MUTE,
            SanctionScope.NETWORK, "btc", Optional.empty(), "reason", false,
            Optional.of(UUID.randomUUID()), ActorKind.HUMAN, Optional.empty(),
            1L, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
