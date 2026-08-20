package dev.btc.core.api.island;

/**
 * Applies one system's offline progression for a bounded window of time.
 *
 * <p>Registered against the platform, which decides when a handler runs, on which thread, and how
 * much of the operation budget it may spend. The handler owns only the gameplay rule.
 *
 * <p>Three obligations, none of which the platform can enforce for you:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The same {@link CatchUpContext#operationId()} may be presented twice
 *       after a crash. Applying twice must not produce twice.</li>
 *   <li><b>Bounded.</b> Never spend more than
 *       {@link CatchUpContext#remainingOperationBudget()}, and never account for more than
 *       {@link CatchUpContext#boundedElapsedMillis()}.</li>
 *   <li><b>Honest.</b> A system whose result depends on transitions rather than on elapsed time —
 *       redstone, a mob death with a real killer — must return
 *       {@link CatchUpResult#rejected} with {@link CatchUpRejection#UNSUPPORTED_SYSTEM} instead of
 *       approximating.</li>
 * </ul>
 *
 * <p>Called on the context's owning thread: the global context for a world activation, the chunk's
 * region thread for a chunk resume. It must not block on I/O, must not touch another region, and
 * must not schedule work that assumes the main thread.
 */
@FunctionalInterface
public interface CatchUpHandler {

    /**
     * Applies this system's progression for the given window.
     *
     * @param context the validated window; the world is loaded and the island is owned and leased
     * @return what was done — never {@code null}
     */
    CatchUpResult apply(CatchUpContext context);

    /**
     * Whether this handler wants chunk-scoped callbacks in addition to the world activation.
     *
     * <p>Default is world-scoped only, which is the cheaper contract: one callback per island
     * instead of one per resumed chunk. Override when the system's state genuinely lives per chunk.
     *
     * @return {@code true} to also receive {@link ChunkResumeEvent}-driven callbacks
     */
    default boolean wantsChunkScope() {
        return false;
    }
}
