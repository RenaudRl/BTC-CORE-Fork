package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the doctrine that the sanction model exists to enforce.
 *
 * <p>The central one is {@link #automaticBanIsRefused()}: "the anticheat never bans by itself" has to
 * be a fact the code refuses to violate, not a sentence in a document. If that test ever goes green
 * after the guard is removed, the doctrine has quietly become a preference.
 */
class SanctionTest {

    private static final String NETWORK = "btc";

    private Sanction sanction(SanctionType type, ActorKind actorKind, Optional<UUID> actorId,
                              Optional<Long> expiresAt) {
        return new Sanction(
            Optional.empty(),
            Optional.of(UUID.randomUUID()),
            Optional.empty(),
            type,
            SanctionScope.NETWORK,
            NETWORK,
            Optional.empty(),
            "test reason",
            false,
            actorId,
            actorKind,
            Optional.empty(),
            System.currentTimeMillis(),
            expiresAt,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    }

    @Test
    @DisplayName("an automatic ban is refused outright")
    void automaticBanIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> sanction(SanctionType.BAN, ActorKind.ANTICHEAT_AUTO, Optional.empty(), Optional.empty()));
        assertTrue(refused.getMessage().contains("human"),
            "the refusal should say why, so the caller learns the rule rather than the symptom");
    }

    @Test
    @DisplayName("a human ban is accepted and names its author")
    void humanBanIsAccepted() {
        UUID moderator = UUID.randomUUID();
        Sanction ban = sanction(SanctionType.BAN, ActorKind.HUMAN, Optional.of(moderator), Optional.empty());
        assertTrue(ban.decidedByHuman());
        assertEquals(Optional.of(moderator), ban.actorId());
    }

    @Test
    @DisplayName("a human decision without an author is refused")
    void humanDecisionMustBeNamed() {
        assertThrows(IllegalArgumentException.class,
            () -> sanction(SanctionType.WARN, ActorKind.HUMAN, Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("the platform may restrict on its own")
    void automaticRestrictIsAllowed() {
        Sanction restriction = sanction(SanctionType.RESTRICT, ActorKind.ANTICHEAT_AUTO,
            Optional.empty(), Optional.of(System.currentTimeMillis() + 60_000));
        assertFalse(restriction.decidedByHuman());
        assertTrue(SanctionType.RESTRICT.issuableAutomatically());
        assertFalse(SanctionType.BAN.issuableAutomatically());
        assertFalse(SanctionType.KICK.issuableAutomatically(),
            "a kick ends a session; that stays a human call");
    }

    @Test
    @DisplayName("every sanction carries a reason")
    void reasonIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new Sanction(
            Optional.empty(), Optional.of(UUID.randomUUID()), Optional.empty(),
            SanctionType.MUTE, SanctionScope.NETWORK, NETWORK, Optional.empty(),
            "   ", false, Optional.empty(), ActorKind.ANTICHEAT_AUTO, Optional.empty(),
            System.currentTimeMillis(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty()));
    }

    @Test
    @DisplayName("a server-scoped sanction must name its server")
    void serverScopeNeedsServer() {
        assertThrows(IllegalArgumentException.class, () -> new Sanction(
            Optional.empty(), Optional.of(UUID.randomUUID()), Optional.empty(),
            SanctionType.MUTE, SanctionScope.SERVER, NETWORK, Optional.empty(),
            "reason", false, Optional.empty(), ActorKind.ANTICHEAT_AUTO, Optional.empty(),
            System.currentTimeMillis(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty()));
    }

    @Test
    @DisplayName("a sanction with no target at all is refused")
    void targetIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new Sanction(
            Optional.empty(), Optional.empty(), Optional.empty(),
            SanctionType.MUTE, SanctionScope.NETWORK, NETWORK, Optional.empty(),
            "reason", false, Optional.empty(), ActorKind.ANTICHEAT_AUTO, Optional.empty(),
            System.currentTimeMillis(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty()));
    }

    @Test
    @DisplayName("expiry and revocation both end a sanction")
    void activityWindow() {
        long now = System.currentTimeMillis();
        Sanction expired = sanction(SanctionType.MUTE, ActorKind.ANTICHEAT_AUTO,
            Optional.empty(), Optional.of(now - 1));
        assertFalse(expired.activeAt(now));

        Sanction standing = sanction(SanctionType.MUTE, ActorKind.ANTICHEAT_AUTO,
            Optional.empty(), Optional.of(now + 60_000));
        assertTrue(standing.activeAt(now));

        Sanction permanent = sanction(SanctionType.MUTE, ActorKind.ANTICHEAT_AUTO,
            Optional.empty(), Optional.empty());
        assertTrue(permanent.activeAt(now));
    }
}
