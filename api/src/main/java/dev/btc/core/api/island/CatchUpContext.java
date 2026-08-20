package dev.btc.core.api.island;

import org.bukkit.World;

import java.util.Optional;
import java.util.UUID;

/**
 * One bounded window of offline time, handed to a {@link CatchUpHandler} on the thread that owns it.
 *
 * <p>The platform decides <em>when</em> and <em>on which thread</em>; the handler owns the gameplay
 * rule. By the time a context exists, the world is loaded, the island is owned, the lease is held
 * and the window has been validated — a handler never has to re-check any of that, and must not
 * widen the window it was given.
 *
 * <p>A context lives for the duration of one operation and must not be retained. In particular
 * {@link #world()} is only valid inside the callback: the same island reloaded later is a different
 * {@code World} instance with a different UUID.
 */
public interface CatchUpContext {

    /**
     * The island this operation belongs to.
     *
     * @return the island key, never {@code null}
     */
    IslandKey island();

    /**
     * The lease under which this operation runs.
     *
     * <p>Carry {@link IslandLease#fencingToken()} into any write that leaves this process. A write
     * that does not present it cannot be fenced, and is therefore not exactly-once whatever else it
     * does.
     *
     * @return the lease, never {@code null}
     */
    IslandLease lease();

    /**
     * The idempotency key for this operation.
     *
     * <p>Stable across retries of the same window: a handler that has already recorded this id as
     * applied must report {@link CatchUpResult#noWork()} rather than produce a second time.
     *
     * @return the operation id, never {@code null}
     */
    UUID operationId();

    /**
     * Start of the window, in epoch milliseconds — the island's last durably committed tick.
     *
     * @return the inclusive start of the window
     */
    long fromEpochMillis();

    /**
     * End of the window, in epoch milliseconds, as observed by the server clock at claim time.
     *
     * @return the exclusive end of the window
     */
    long toEpochMillis();

    /**
     * The elapsed time this operation may account for, already clamped by the platform's maximum.
     *
     * <p>This is not necessarily {@code to - from}: a player absent for a month is handed the
     * configured ceiling, not a month. Handlers must use this value and not recompute it.
     *
     * @return the bounded elapsed time in milliseconds, never negative
     */
    long boundedElapsedMillis();

    /**
     * Whether the window was clamped, meaning real absence exceeded the ceiling.
     *
     * @return {@code true} when {@link #boundedElapsedMillis()} is shorter than the real absence
     */
    boolean clamped();

    /**
     * The loaded world this island lives in.
     *
     * <p>Valid only for the duration of the callback.
     *
     * @return the world, never {@code null}
     */
    World world();

    /**
     * The chunk this callback is scoped to, when it came from a chunk resume rather than a world
     * activation.
     *
     * <p>When present, the handler runs on that chunk's region thread and must not touch another
     * chunk. When absent, the callback is world-scoped and runs on the global context.
     *
     * @return the chunk coordinates, or empty for a world-scoped activation
     */
    Optional<ChunkPosition> chunk();

    /**
     * How many bounded operations the handler may still apply within this context.
     *
     * <p>Shared across handlers for one island so that a single greedy system cannot spend the whole
     * budget. A handler that reaches zero stops and reports what it did; the remainder is picked up
     * by the next activation.
     *
     * @return the remaining operation budget, never negative
     */
    int remainingOperationBudget();

    /**
     * A chunk's position inside a world.
     *
     * @param x the chunk X coordinate
     * @param z the chunk Z coordinate
     */
    record ChunkPosition(int x, int z) {
    }
}
