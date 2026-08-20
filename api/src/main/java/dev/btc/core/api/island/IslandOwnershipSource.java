package dev.btc.core.api.island;

import java.util.Optional;

/**
 * The canonical answer to "who owns this island, and may this backend advance it".
 *
 * <p>Split out of the platform on purpose: the authority is a shared store — MySQL — and the server
 * must not carry a hard dependency on it just to boot. A backend with no source bound refuses every
 * catch-up rather than inventing one, which is the safe direction: refusing costs a player some
 * offline progression, while guessing costs duplicated production across two backends.
 *
 * <p>Implementations are consulted off the region thread. They may block on I/O; the platform is
 * responsible for calling them where blocking is allowed.
 */
public interface IslandOwnershipSource {

    /**
     * Resolves the island anchored on a world name.
     *
     * @param worldName the persisted world name
     * @return the island, or empty when no canonical row exists for that name
     */
    Optional<IslandKey> resolve(String worldName);

    /**
     * Attempts to take the lease for an island, by compare-and-set against the canonical row.
     *
     * <p>Must be atomic: two backends racing here must not both succeed. The returned lease carries
     * a fencing token strictly greater than any previously issued for this island.
     *
     * @param island    the island to claim
     * @param backendId the backend asking
     * @return the lease, or empty when another backend holds a live claim
     */
    Optional<IslandLease> claim(IslandKey island, String backendId);

    /**
     * The last durably committed tick for an island, in epoch milliseconds.
     *
     * @param island the island
     * @return the timestamp, or empty when the island has never been committed
     */
    Optional<Long> lastCommittedEpochMillis(IslandKey island);

    /**
     * Commits a completed window and releases the lease.
     *
     * <p>Refused when {@code lease} presents a fencing token older than the canonical one — that is
     * the whole point of the token, and the caller must treat a refusal as "another backend owns
     * this island now", not as a transient error to retry.
     *
     * @param island        the island
     * @param lease         the lease the work ran under
     * @param toEpochMillis the new last-committed tick
     * @return {@code true} when the commit was accepted
     */
    boolean commit(IslandKey island, IslandLease lease, long toEpochMillis);

    /**
     * Releases a lease without advancing the timestamp, after a retryable failure.
     *
     * @param island the island
     * @param lease  the lease to release
     */
    void abandon(IslandKey island, IslandLease lease);

    /**
     * Whether the island's owned perimeter includes a chunk.
     *
     * @param island the island
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return {@code true} when the chunk is inside the island's unlocked perimeter
     */
    boolean ownsChunk(IslandKey island, int chunkX, int chunkZ);
}
