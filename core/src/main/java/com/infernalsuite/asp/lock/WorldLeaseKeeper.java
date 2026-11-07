package com.infernalsuite.asp.lock;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.infernalsuite.asp.api.loaders.LockableSlimeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps renewed, and gives back, the leases this server holds on worlds of
 * {@link LockableSlimeLoader} sources (AD-096).
 *
 * <p>One instance per server, owned by {@code AdvancedSlimePaper}. The loaders hold the leases
 * (world to generation); the keeper decides when to take, renew and release them:
 * <ul>
 *   <li>every source it tracks is renewed in one grouped statement per renewal period, on the
 *       renewal executor; a lease the source no longer grants is lost for good and reported to
 *       {@code onLost}, never taken back;</li>
 *   <li>a world unloaded is released once its last save has completed, on the release executor —
 *       the renewal thread never waits for a save;</li>
 *   <li>a lease held but not used by a loaded world for a full renewal period (read but never
 *       loaded) is given back;</li>
 *   <li>at a stop, renewals stop first, the remaining saves land, then every lease of each source
 *       is released in one statement.</li>
 * </ul>
 * The generation conditions every write in the source, so a release that races a new
 * acquisition, or a save that lands late, can never touch a newer lease.
 */
public final class WorldLeaseKeeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLeaseKeeper.class);
    /** How long a stop waits for a renewal already running to finish. */
    private static final Duration RENEWAL_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final ScheduledExecutorService renewer;
    private final Executor releaser;
    private final Predicate<String> isLoaded;
    private final Consumer<String> onLost;
    private final Duration lastSaveTimeout;
    private final Map<LockableSlimeLoader, ScheduledFuture<?>> tracked = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingReleases = new ConcurrentHashMap<>();
    /** Worlds held but found unused at the previous renewal. */
    private final Set<String> unused = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * @param renewer         runs the grouped renewals; may block on the source, nothing else
     * @param releaser        runs releases; may block on the source
     * @param isLoaded        whether a world is currently loaded on this server
     * @param onLost          told, on the renewal thread, of each world whose lease was lost
     * @param lastSaveTimeout the longest a release waits for the last save of an unloaded world
     */
    public WorldLeaseKeeper(ScheduledExecutorService renewer, Executor releaser, Predicate<String> isLoaded,
                            Consumer<String> onLost, Duration lastSaveTimeout) {
        this.renewer = renewer;
        this.releaser = releaser;
        this.isLoaded = isLoaded;
        this.onLost = onLost;
        this.lastSaveTimeout = lastSaveTimeout;
    }

    /**
     * Takes this server's lease on {@code worldName}, after any release of it still pending, then
     * keeps its source renewed. Blocking I/O.
     *
     * @return the generation of the new lease
     */
    public long acquire(LockableSlimeLoader loader, String worldName)
            throws UnknownWorldException, WorldLockedException, IOException {
        if (stopped.get()) {
            throw new IOException("The server is stopping: world " + worldName + " is not leased");
        }
        // The previous instance's last save must land under its own lease before a new one fences it.
        final CompletableFuture<Void> pending = pendingReleases.get(worldName);
        if (pending != null) pending.join();

        final long generation = loader.acquireLock(worldName);
        unused.remove(worldName);
        track(loader);
        return generation;
    }

    /** Keeps every lease of {@code loader} renewed from now on. No I/O. */
    public void track(LockableSlimeLoader loader) {
        if (stopped.get()) return;
        tracked.computeIfAbsent(loader, source -> {
            final long every = source.lockRenewInterval().toMillis();
            return renewer.scheduleWithFixedDelay(() -> renew(source), every, every, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * Releases lease {@code generation} on {@code worldName} once {@code lastSave} has completed,
     * or after the last-save timeout — a save landing later is then refused, never written over a
     * newer holder. Never blocks the caller.
     */
    public CompletableFuture<Void> releaseAfter(LockableSlimeLoader loader, String worldName, long generation,
                                                CompletableFuture<?> lastSave) {
        final CompletableFuture<Void> release = lastSave.copy()
                .orTimeout(lastSaveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((ignored, failure) -> {
                    if (unwrap(failure) instanceof TimeoutException) {
                        LOGGER.warn("The last save of world {} did not finish within {}; its lease is released anyway, "
                                + "and that save will be refused if it lands later.", worldName, lastSaveTimeout);
                    }
                    return (Void) null; // a failed save was already logged by its task
                })
                .thenRunAsync(() -> releaseQuietly(loader, worldName, generation), releaser);
        pendingReleases.put(worldName, release);
        release.whenComplete((ignored, failure) -> pendingReleases.remove(worldName, release));
        return release;
    }

    /** Releases lease {@code generation} on {@code worldName} now, on the calling thread. Blocking I/O. */
    public void releaseNow(LockableSlimeLoader loader, String worldName, long generation) {
        releaseQuietly(loader, worldName, generation);
    }

    /** Whether {@link #shutdown} has run: no lease is taken or renewed any more. */
    public boolean isStopped() {
        return stopped.get();
    }

    /**
     * Ends the keeper at a stop, once: stops the renewals first, runs {@code saveRemaining} (which
     * must leave every final save landed), waits for the pending releases, then releases every
     * lease of each tracked source in one statement. Blocking I/O.
     */
    public void shutdown(Runnable saveRemaining) {
        if (!stopped.compareAndSet(false, true)) return;

        renewer.shutdown();
        try {
            if (!renewer.awaitTermination(RENEWAL_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warn("A world lease renewal was still running when the server stopped");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        try {
            saveRemaining.run();
        } catch (RuntimeException failure) {
            LOGGER.error("The final saves of the leased worlds did not all complete", failure);
        }
        for (CompletableFuture<Void> pending : List.copyOf(pendingReleases.values())) {
            pending.join(); // bounded by the last-save timeout, and a release never throws
        }
        for (LockableSlimeLoader loader : tracked.keySet()) {
            try {
                loader.releaseAllLocks();
            } catch (IOException unreachable) {
                LOGGER.warn("Could not release the world leases at shutdown; they will expire on their own.", unreachable);
            }
        }
    }

    /** One renewal period of {@code loader}. Package-private so the checks can drive it by hand. */
    void renew(LockableSlimeLoader loader) {
        try {
            final Set<String> lost;
            try {
                lost = loader.renewLocks();
            } catch (IOException unreachable) {
                LOGGER.warn("Could not renew the world leases; retried at the next renewal. A world another server "
                        + "takes once its lease ran out is reported lost then, and its saves are refused meanwhile.",
                        unreachable);
                return;
            }
            for (String worldName : lost) {
                unused.remove(worldName);
                onLost.accept(worldName);
            }
            releaseUnused(loader);
        } catch (RuntimeException failure) {
            // A periodic task that throws is never run again: the leases would silently run out.
            LOGGER.error("World lease renewal failed; retried at the next renewal", failure);
        }
    }

    /** Gives back the leases no loaded world used for a full renewal period. */
    private void releaseUnused(LockableSlimeLoader loader) {
        loader.heldLocks().forEach((worldName, generation) -> {
            if (isLoaded.test(worldName) || pendingReleases.containsKey(worldName)) {
                unused.remove(worldName);
                return;
            }
            if (unused.add(worldName)) return; // first seen unused: the reader may be about to load it
            unused.remove(worldName);
            releaser.execute(() -> releaseQuietly(loader, worldName, generation));
        });
    }

    private static void releaseQuietly(LockableSlimeLoader loader, String worldName, long generation) {
        try {
            loader.releaseLock(worldName, generation);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not release the lease on world {}; it will expire on its own.", worldName, failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure;
    }
}
