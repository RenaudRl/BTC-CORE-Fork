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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Owns the leases this server holds on worlds of {@link LockableSlimeLoader} sources (AD-096).
 *
 * <p>One instance per server, owned by {@code AdvancedSlimePaper}: it takes a lease when a world
 * is read for writing, renews it on its own executor while the world stays loaded, and gives it
 * back after the world is unloaded or when the JVM stops. All I/O happens either on the thread
 * that asked for the lease (an async reader) or on the executor — never on a server thread.
 *
 * <p>A world read but never loaded, or unloaded without notice, is released at its first renewal
 * once a full renewal interval has passed: a lease nobody uses must not keep other servers out
 * until the next restart.
 */
public final class WorldLeaseKeeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLeaseKeeper.class);

    private final ScheduledExecutorService executor;
    private final Predicate<String> isLoaded;
    private final LongSupplier nanoTime;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    private record Lease(LockableSlimeLoader loader, long acquiredAtNanos, ScheduledFuture<?> renewal) {
    }

    /**
     * @param executor runs renewals and deferred releases; it may block on the source
     * @param isLoaded whether a world is currently loaded on this server
     * @param nanoTime monotonic clock, injected so tests control it
     */
    public WorldLeaseKeeper(ScheduledExecutorService executor, Predicate<String> isLoaded, LongSupplier nanoTime) {
        this.executor = executor;
        this.isLoaded = isLoaded;
        this.nanoTime = nanoTime;
    }

    /**
     * Takes this server's lease on {@code worldName}, then keeps it renewed. Blocking I/O.
     *
     * @return {@code true} when the lease was not already held by this keeper — the caller then
     *         owns releasing it if what it was taking the lease for fails
     */
    public boolean acquire(LockableSlimeLoader loader, String worldName)
            throws UnknownWorldException, WorldLockedException, IOException {
        loader.acquireLock(worldName);

        final long every = loader.lockRenewInterval().toMillis();
        final ScheduledFuture<?> renewal =
                executor.scheduleWithFixedDelay(() -> renew(worldName), every, every, TimeUnit.MILLISECONDS);
        final Lease previous = leases.put(worldName, new Lease(loader, nanoTime.getAsLong(), renewal));
        if (previous == null) return true;
        previous.renewal().cancel(false);
        return false;
    }

    /** Whether this server currently holds the lease on {@code worldName}. */
    public boolean holds(String worldName) {
        return leases.containsKey(worldName);
    }

    /**
     * Stops renewing {@code worldName} and releases it after {@code grace}, once
     * {@code awaitPendingSave} returns — the last save must land while the lease still protects
     * it. A world read again before then keeps its new lease. Never blocks the caller.
     *
     * @return completes once the release was attempted (or skipped)
     */
    public Future<?> releaseLater(String worldName, Duration grace, Runnable awaitPendingSave) {
        final Lease lease = forget(worldName);
        if (lease == null) return CompletableFuture.completedFuture(null);

        return executor.schedule(() -> {
            awaitPendingSave.run();
            if (leases.containsKey(worldName)) return;
            release(lease.loader(), worldName);
        }, grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Releases {@code worldName} now, on the calling thread. Blocking I/O. */
    public void releaseNow(String worldName) {
        final Lease lease = forget(worldName);
        if (lease != null) release(lease.loader(), worldName);
    }

    /**
     * Releases every lease, on the calling thread. Meant for JVM shutdown, after the final saves:
     * a lease left behind would keep the world out of reach of every other server until it expires.
     */
    public void releaseAll() {
        for (String worldName : List.copyOf(leases.keySet())) {
            releaseNow(worldName);
        }
    }

    void renew(String worldName) {
        final Lease lease = leases.get(worldName);
        if (lease == null) return;

        final long heldFor = nanoTime.getAsLong() - lease.acquiredAtNanos();
        if (!isLoaded.test(worldName) && heldFor >= lease.loader().lockRenewInterval().toNanos()) {
            releaseNow(worldName);
            return;
        }

        try {
            if (lease.loader().renewLock(worldName)) return;
            // The row no longer names this server — a release that raced a new read, typically.
            // Taking it back succeeds only if nobody else holds it, which is exactly the case
            // where keeping the world is safe.
            lease.loader().acquireLock(worldName);
        } catch (WorldLockedException | UnknownWorldException lost) {
            LOGGER.error("Lease on world {} was lost ({}). Saves of this world will be refused.",
                    worldName, lost.getMessage());
            forget(worldName);
        } catch (IOException unreachable) {
            LOGGER.warn("Could not renew the lease on world {}; it will be retried, and expires if the "
                    + "source stays unreachable.", worldName, unreachable);
        }
    }

    private Lease forget(String worldName) {
        final Lease lease = leases.remove(worldName);
        if (lease != null) lease.renewal().cancel(false);
        return lease;
    }

    private static void release(LockableSlimeLoader loader, String worldName) {
        try {
            loader.releaseLock(worldName);
        } catch (IOException unreachable) {
            LOGGER.warn("Could not release the lease on world {}; it will expire on its own.", worldName, unreachable);
        }
    }
}
