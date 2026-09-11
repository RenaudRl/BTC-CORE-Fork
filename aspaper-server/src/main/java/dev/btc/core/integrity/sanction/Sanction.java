package dev.btc.core.integrity.sanction;

import java.util.Optional;
import java.util.UUID;

/**
 * A moderation decision, as it is stored.
 *
 * <p>Two fields carry the platform's doctrine rather than mere data.
 *
 * <p>{@link #actorKind()} distinguishes a human decision from an automatic one. It is what makes
 * "the anticheat never bans by itself" a fact that can be checked in the database instead of a promise
 * written in a document. The rule is enforced in {@link #Sanction} and again by a constraint in the
 * schema, because an invariant guarded in only one place is an invariant that eventually escapes.
 *
 * <p>{@link #silent()} decides whether the target is told. Automatic responses default to silent: a
 * visible reaction tells a cheater precisely which check caught them and what to adjust. That has a
 * real cost — a wrongly flagged player sees nothing to contest — which is why visibility becomes
 * mandatory the moment a human confirms the case.
 *
 * @param id database identity, absent until the row is written
 * @param playerUuid the target, absent for an address-only measure
 * @param ipAddress the address concerned, absent when the measure targets an account
 * @param type what was decided
 * @param scope whether it applies to one server or the whole network
 * @param networkId the network it was issued on
 * @param serverId the server, when the scope is a single server
 * @param reason why — never optional, including for automatic responses
 * @param silent whether the target is told
 * @param actorId who decided, absent for an automatic response
 * @param actorKind whether a human or the platform decided
 * @param evidence JSON detail: the check, the violation level, what was observed
 * @param createdAtMillis when it was issued
 * @param expiresAtMillis when it lapses, absent when permanent
 * @param revokedAtMillis when it was lifted, absent while it stands
 * @param revokedBy who lifted it
 * @param revokedReason why it was lifted
 */
public record Sanction(
    Optional<Long> id,
    Optional<UUID> playerUuid,
    Optional<String> ipAddress,
    SanctionType type,
    SanctionScope scope,
    String networkId,
    Optional<String> serverId,
    String reason,
    boolean silent,
    Optional<UUID> actorId,
    ActorKind actorKind,
    Optional<String> evidence,
    long createdAtMillis,
    Optional<Long> expiresAtMillis,
    Optional<Long> revokedAtMillis,
    Optional<UUID> revokedBy,
    Optional<String> revokedReason
) {

    public Sanction {
        if (type == null || scope == null || actorKind == null) {
            throw new IllegalArgumentException("a sanction needs a type, a scope and an actor kind");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a sanction requires a reason");
        }
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("a sanction requires a network id");
        }
        if (playerUuid == null || !playerUuid.isPresent() && (ipAddress == null || ipAddress.isEmpty())) {
            throw new IllegalArgumentException("a sanction must target a player or an address");
        }
        if (type == SanctionType.BAN && actorKind == ActorKind.ANTICHEAT_AUTO) {
            throw new IllegalArgumentException(
                "a ban is always a human decision; automatic detection may restrict or set back, never ban");
        }
        if (actorKind == ActorKind.HUMAN && (actorId == null || actorId.isEmpty())) {
            throw new IllegalArgumentException("a human decision must name its author");
        }
        if (scope == SanctionScope.SERVER && (serverId == null || serverId.isEmpty())) {
            throw new IllegalArgumentException("a server-scoped sanction must name its server");
        }
    }

    /** Whether this sanction is in force at the given instant. */
    public boolean activeAt(long nowMillis) {
        if (revokedAtMillis.isPresent()) {
            return false;
        }
        return expiresAtMillis.map(expiry -> nowMillis < expiry).orElse(true);
    }

    /** Whether a human decided this. */
    public boolean decidedByHuman() {
        return actorKind == ActorKind.HUMAN;
    }
}
