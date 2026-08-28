package dev.btc.core.api.island;

import java.util.Collection;
import java.util.List;
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
     * Whether this source persists an independent catch-up cursor for every owned chunk.
     *
     * <p>Chunk-scoped resumption must not fall back to the dimension cursor: committing one chunk
     * would otherwise make every other chunk look caught up. Sources that do not implement the
     * per-chunk contract are deliberately refused by the chunk-resume API.
     *
     * @return {@code true} when {@link #lastCommittedEpochMillis(IslandKey, int, int)} and
     *         {@link #commit(IslandKey, IslandLease, int, int, long)} are implemented
     */
    default boolean supportsChunkProgress() {
        return false;
    }

    /**
     * The last durably committed tick for one owned chunk.
     *
     * <p>The default is empty because a dimension cursor cannot safely answer this question.
     */
    default Optional<Long> lastCommittedEpochMillis(IslandKey island, int chunkX, int chunkZ) {
        return Optional.empty();
    }

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
     * Commits one chunk cursor while holding the island lease.
     *
     * <p>The default refuses: implementations must opt in explicitly instead of accidentally
     * advancing the dimension cursor for a single chunk.
     */
    default boolean commit(IslandKey island, IslandLease lease, int chunkX, int chunkZ,
                           long toEpochMillis) {
        return false;
    }

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

    /**
     * Returns the persisted perimeter that may be resumed for this island.
     *
     * <p>The platform uses this list only to dispatch chunk-scoped handlers on their owning region
     * threads. It is deliberately part of the ownership source: the source owns the authoritative
     * perimeter, while the platform must never guess a radius or enumerate a whole world.
     * Implementations that do not expose a perimeter cannot run chunk-scoped handlers during a
     * world activation and should leave the default empty result in place.
     *
     * @param island the island whose perimeter is requested
     * @return absolute chunk coordinates, never {@code null}
     */
    default Collection<CatchUpContext.ChunkPosition> ownedChunks(IslandKey island) {
        return List.of();
    }
}
