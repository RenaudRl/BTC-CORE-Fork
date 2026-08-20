package dev.btc.core.api.island;

import java.util.UUID;

/**
 * The durable record of what each catch-up actually did.
 *
 * <p>Separate from {@link IslandOwnershipSource} because the two answer different questions and can
 * fail independently: ownership decides whether work may happen, the journal states what happened.
 * A backend can lose the journal and still be safe; losing ownership is what duplicates production.
 *
 * <p>Idempotency lives here, keyed on {@code (operationId, systemKey)}. One activation issues a
 * single operation id and every handler writes one row under it, so replaying an activation after a
 * crash collides with the existing rows instead of counting the work twice. Implementations must
 * leave the first row intact on collision — that row is the evidence, and overwriting it would
 * erase the thing the journal exists to prove.
 *
 * <p>Consulted off the region thread; implementations may block on I/O.
 */
public interface CatchUpJournal {

    /**
     * Records one handler's outcome for one operation.
     *
     * @param entry the outcome
     * @return {@code true} when the row was newly written, {@code false} when an entry already
     *         existed for this {@code (operationId, systemKey)} — that is a replay, not an error
     */
    boolean record(Entry entry);

    /**
     * One handler's outcome within one catch-up operation.
     *
     * @param operationId    the id shared by every handler of one activation
     * @param systemKey      the handler's stable identifier
     * @param island         the island advanced
     * @param fromEpochMillis the window start
     * @param toEpochMillis   the window end
     * @param backendId      the backend that ran the work
     * @param fencingToken   the token the work ran under
     * @param status         what the handler reported
     * @param schemaVersion  the handler's persisted-contract version
     * @param operations     how much of the budget the handler spent
     * @param resultHash     an optional digest of the produced state, or {@code null}
     */
    record Entry(UUID operationId, String systemKey, IslandKey island, long fromEpochMillis,
                 long toEpochMillis, String backendId, long fencingToken, CatchUpResult.Status status,
                 int schemaVersion, int operations, String resultHash) {
    }
}
