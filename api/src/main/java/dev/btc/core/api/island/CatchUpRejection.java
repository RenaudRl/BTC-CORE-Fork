package dev.btc.core.api.island;

/**
 * Why the platform refused to run catch-up for an island.
 *
 * <p>Every refusal is named. A catch-up that silently does nothing is indistinguishable from one
 * that ran and produced nothing, and the two call for opposite fixes, so the platform emits a
 * bounded diagnostic carrying one of these instead of returning quietly.
 */
public enum CatchUpRejection {

    /**
     * The world has no canonical island ownership: it is not an island world, or its row is absent
     * from the canonical store.
     */
    UNKNOWN_WORLD,

    /**
     * The island exists but this backend does not own it, and no lease was granted.
     */
    ISLAND_NOT_OWNED,

    /**
     * The recorded last-tick timestamp lies in the future relative to the server clock.
     *
     * <p>Trusting it would let a clock skew — or an edited row — mint progression out of nothing, so
     * the operation is refused rather than clamped to zero: a clamp hides the anomaly.
     */
    FUTURE_TIMESTAMP,

    /**
     * The lease held by this backend lapsed before the operation could start.
     */
    LEASE_EXPIRED,

    /**
     * The canonical store has issued a newer fencing token to another backend.
     */
    STALE_FENCING_TOKEN,

    /**
     * A handler was registered for a system that declares itself non-deterministic, or whose
     * required inputs could not be reproduced.
     */
    UNSUPPORTED_SYSTEM,

    /**
     * The chunk the callback would target is not loaded, or is not part of the island's owned
     * perimeter.
     */
    CHUNK_NOT_OWNED
}
