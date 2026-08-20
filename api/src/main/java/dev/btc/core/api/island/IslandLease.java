package dev.btc.core.api.island;

import java.time.Instant;
import java.util.Objects;

/**
 * The right, held by one backend for a bounded time, to advance one island's offline progression.
 *
 * <p>A lease alone does not make an operation safe: a backend that stalls long enough for its lease
 * to lapse may still believe it holds one, and would happily commit on top of whichever backend
 * took over. The {@code fencingToken} is what closes that window. It increases with every claim, is
 * carried through to the commit, and a commit presenting a token older than the canonical one is
 * refused — even if that backend's own clock says its lease is still valid.
 *
 * <p>Expiry is therefore an optimisation, not the guarantee. The guarantee is the token.
 *
 * @param backendId    the backend holding the lease
 * @param fencingToken the monotonically increasing token issued with this claim
 * @param expiresAt    when the lease lapses, as observed by the canonical store
 */
public record IslandLease(String backendId, long fencingToken, Instant expiresAt) {

    public IslandLease {
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must not be negative");
        }
    }

    /**
     * Whether this lease has lapsed relative to {@code now}.
     *
     * <p>Read this as a hint to stop working, never as proof that committing is safe. Only the
     * canonical store can say whether {@link #fencingToken()} is still current.
     *
     * @param now the reference instant, normally the server's own clock
     * @return {@code true} when the lease is no longer valid
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Whether this lease was issued after {@code other}.
     *
     * @param other the lease to compare against, may be {@code null} for "no previous holder"
     * @return {@code true} when this lease's token supersedes the other's
     */
    public boolean supersedes(IslandLease other) {
        return other == null || fencingToken > other.fencingToken;
    }
}
