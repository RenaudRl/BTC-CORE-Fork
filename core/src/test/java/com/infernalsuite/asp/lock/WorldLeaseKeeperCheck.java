package com.infernalsuite.asp.lock;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.infernalsuite.asp.api.loaders.LockableSlimeLoader;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free checks for {@link WorldLeaseKeeper} (AD-096), in the fork's {@code main} +
 * assertions idiom. The SQL side of the lease (generation fencing, atomic acquisition) is proven
 * against a real PostgreSQL by {@code PostgresLoaderLockCheck}; this proves what the keeper
 * decides on top of it: grouped renewal, a lost lease reported and never taken back, a release
 * that follows the last save off the renewal thread, a re-read that waits for it, the stop order.
 *
 * <p>Deterministic: renewals are triggered by hand ({@link WorldLeaseKeeper#renew}) and the
 * renewal period of the fake is one hour, so the executor never fires one on its own.
 */
public final class WorldLeaseKeeperCheck {

    private static final String ISLAND = "btcsky_isle_overworld_0001";
    private static final String NETHER = "btcsky_isle_nether_0002";
    private static final Duration NEVER_DURING_TEST = Duration.ofHours(1);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT_SAVE_TIMEOUT = Duration.ofMillis(100);
    private static final long STILL_BLOCKED_MS = 200L;

    private WorldLeaseKeeperCheck() {
    }

    public static void main(final String[] args) throws Exception {
        leasesAreRenewedInOneGroupedCall();
        leaseHeldByAnotherServerIsRefused();
        unloadReleasesOnlyAfterTheLastSaveAndOffTheRenewalThread();
        reReadWaitsForThePendingReleaseThenFencesIt();
        saveThatNeverEndsStillReleasesAfterTheTimeout();
        leaseReadButNeverLoadedIsGivenBackAfterOneRenewal();
        leaseTakenOverIsReportedLostAndNeverTakenBack();
        leaseDroppedOnAFreeWorldIsLostNotTakenBack();
        unreachableSourceKeepsTheLease();
        renewalSurvivesAFailingLossHandler();
        shutdownStopsRenewalsThenSavesThenReleasesEverythingOnce();
        System.out.println("WorldLeaseKeeperCheck: all checks passed");
    }

    private static void leasesAreRenewedInOneGroupedCall() throws Exception {
        final Fixture f = new Fixture(true);
        final long first = f.keeper.acquire(f.loader, ISLAND);
        final long second = f.keeper.acquire(f.loader, NETHER);
        check(second > first, "every acquisition draws a new generation");

        f.keeper.renew(f.loader);
        check(f.loader.renewCalls.get() == 1, "two leases are renewed in one call, got " + f.loader.renewCalls.get());
        check(f.loader.heldLocks().keySet().equals(Set.of(ISLAND, NETHER)) && f.lost.isEmpty(), "and both stay held");
        f.close();
    }

    private static void leaseHeldByAnotherServerIsRefused() throws Exception {
        final Fixture f = new Fixture(true);
        f.loader.rows.put(ISLAND, new Row(Fake.OTHER, 1L));
        try {
            f.keeper.acquire(f.loader, ISLAND);
            throw new AssertionError("a world leased by another server must be refused");
        } catch (final WorldLockedException refused) {
            check(refused.getWorldName().equals(ISLAND), "the refusal names the world");
            check(refused.getMessage().contains(ISLAND) && refused.getMessage().contains(Fake.LOCKED_UNTIL.toString()),
                    "the message names the world and the end of the lease");
        }
        check(f.loader.heldLocks().isEmpty(), "a refused lease is not held");
        f.close();
    }

    private static void unloadReleasesOnlyAfterTheLastSaveAndOffTheRenewalThread() throws Exception {
        final Fixture f = new Fixture(false);
        final long generation = f.keeper.acquire(f.loader, ISLAND);
        final CompletableFuture<Void> lastSave = new CompletableFuture<>();
        final CompletableFuture<Void> release = f.keeper.releaseAfter(f.loader, ISLAND, generation, lastSave);

        Thread.sleep(STILL_BLOCKED_MS);
        check(!release.isDone() && f.loader.heldLocks().containsKey(ISLAND), "no release while the last save runs");

        lastSave.complete(null);
        release.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        check(f.loader.events.equals(List.of("acquired " + ISLAND + " " + generation, "released " + ISLAND + " " + generation)),
                "the lease is released after the last save, got " + f.loader.events);
        check(f.loader.releaseThreads.equals(List.of(Fixture.RELEASE_THREAD)),
                "the release runs on the release executor, never the renewal thread, got " + f.loader.releaseThreads);
        f.close();
    }

    /** M2: a re-read during the release waits for it, then its new generation fences the old one. */
    private static void reReadWaitsForThePendingReleaseThenFencesIt() throws Exception {
        final Fixture f = new Fixture(false);
        final long old = f.keeper.acquire(f.loader, ISLAND);
        final CompletableFuture<Void> lastSave = new CompletableFuture<>();
        f.keeper.releaseAfter(f.loader, ISLAND, old, lastSave);

        final CompletableFuture<Long> reRead = CompletableFuture.supplyAsync(() -> {
            try {
                return f.keeper.acquire(f.loader, ISLAND);
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        });
        Thread.sleep(STILL_BLOCKED_MS);
        check(!reRead.isDone(), "a re-read waits while the previous instance's last save runs");

        lastSave.complete(null);
        final long fresh = reRead.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        check(fresh > old, "the re-read gets a newer generation");
        check(f.loader.events.equals(List.of("acquired " + ISLAND + " " + old, "released " + ISLAND + " " + old,
                        "acquired " + ISLAND + " " + fresh)),
                "release of the old lease, then the new one, got " + f.loader.events);
        check(Long.valueOf(fresh).equals(f.loader.heldLocks().get(ISLAND)), "and the new lease is the one held");
        f.close();
    }

    private static void saveThatNeverEndsStillReleasesAfterTheTimeout() throws Exception {
        final Fixture f = new Fixture(false, SHORT_SAVE_TIMEOUT);
        final long generation = f.keeper.acquire(f.loader, ISLAND);
        f.keeper.releaseAfter(f.loader, ISLAND, generation, new CompletableFuture<>())
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        check(f.loader.heldLocks().isEmpty(), "a save that never ends does not keep the island locked forever");
        f.close();
    }

    private static void leaseReadButNeverLoadedIsGivenBackAfterOneRenewal() throws Exception {
        final Fixture f = new Fixture(false);
        f.keeper.acquire(f.loader, ISLAND);

        f.keeper.renew(f.loader);
        check(f.loader.heldLocks().containsKey(ISLAND), "a world read moments ago is given time to be loaded");

        f.keeper.renew(f.loader);
        f.drainReleases();
        check(f.loader.heldLocks().isEmpty(), "a lease nobody loaded is given back at the next renewal");
        check(f.loader.rows.get(ISLAND).owner() == null, "and released in the source");
        f.close();
    }

    private static void leaseTakenOverIsReportedLostAndNeverTakenBack() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.rows.put(ISLAND, new Row(Fake.OTHER, 99L)); // our lease expired and another server took it

        f.keeper.renew(f.loader);
        check(f.lost.equals(List.of(ISLAND)), "the lost lease is reported, got " + f.lost);
        check(f.loader.heldLocks().isEmpty(), "and is no longer held");
        check(f.loader.acquisitions.get() == 1, "it is never taken back");
        check(f.loader.rows.get(ISLAND).equals(new Row(Fake.OTHER, 99L)), "and the new holder keeps it");
        f.close();
    }

    private static void leaseDroppedOnAFreeWorldIsLostNotTakenBack() throws Exception {
        final Fixture f = new Fixture(true);
        final long generation = f.keeper.acquire(f.loader, ISLAND);
        f.loader.rows.put(ISLAND, new Row(null, generation)); // freed behind our back (another server's turn came and went)

        f.keeper.renew(f.loader);
        check(f.lost.equals(List.of(ISLAND)), "a lease dropped on a free world is lost too, got " + f.lost);
        check(f.loader.acquisitions.get() == 1 && f.loader.rows.get(ISLAND).owner() == null,
                "and never taken back in silence, even though the world is free");
        f.close();
    }

    private static void unreachableSourceKeepsTheLease() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.unreachable = true;

        f.keeper.renew(f.loader);
        check(f.loader.heldLocks().containsKey(ISLAND) && f.lost.isEmpty(),
                "an unreachable source does not drop the lease: it is retried");
        f.close();
    }

    private static void renewalSurvivesAFailingLossHandler() throws Exception {
        final Fixture f = new Fixture(true);
        f.failOnLost = true;
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.rows.put(ISLAND, new Row(Fake.OTHER, 99L));

        f.keeper.renew(f.loader); // must not throw: a periodic task that throws is never run again
        check(f.loader.heldLocks().isEmpty(), "the lease is still dropped");
        f.close();
    }

    private static void shutdownStopsRenewalsThenSavesThenReleasesEverythingOnce() throws Exception {
        final Fixture f = new Fixture(true);
        final long generation = f.keeper.acquire(f.loader, ISLAND);
        f.keeper.acquire(f.loader, NETHER);
        final CompletableFuture<Void> unloadSave = new CompletableFuture<>();
        f.keeper.releaseAfter(f.loader, ISLAND, generation, unloadSave);

        final AtomicBoolean renewerStoppedFirst = new AtomicBoolean();
        final AtomicInteger saves = new AtomicInteger();
        final Runnable saveRemaining = () -> {
            renewerStoppedFirst.set(f.renewer.isShutdown());
            saves.incrementAndGet();
            f.loader.events.add("final saves");
            unloadSave.complete(null);
        };
        f.keeper.shutdown(saveRemaining);

        check(renewerStoppedFirst.get(), "renewals stop before the final saves");
        check(f.loader.releaseAllCalls.get() == 1, "every lease is released in one call per source");
        final List<String> events = f.loader.events;
        check(events.indexOf("final saves") < events.indexOf("released " + ISLAND + " " + generation)
                        && events.indexOf("released " + ISLAND + " " + generation) < events.indexOf("released all"),
                "final saves, then the pending release, then the grouped release, got " + events);
        check(f.loader.rows.values().stream().allMatch(row -> row.owner() == null), "nothing is held in the source");

        f.keeper.shutdown(saveRemaining);
        check(saves.get() == 1 && f.loader.releaseAllCalls.get() == 1, "a second shutdown (the exit safety net) does nothing");
        try {
            f.keeper.acquire(f.loader, ISLAND);
            throw new AssertionError("no lease may be taken once stopped");
        } catch (final IOException refused) {
            check(refused.getMessage().contains(ISLAND), "the refusal names the world");
        }
        f.close();
    }

    private record Row(String owner, long generation) {
    }

    private static final class Fixture {
        static final String RELEASE_THREAD = "lease-check-release";

        final Fake loader = new Fake();
        final List<String> lost = Collections.synchronizedList(new ArrayList<>());
        final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("lease-check-renewal").factory());
        final ExecutorService releaser = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name(RELEASE_THREAD).factory());
        final WorldLeaseKeeper keeper;
        volatile boolean failOnLost;

        Fixture(final boolean worldsLoaded) {
            this(worldsLoaded, WAIT);
        }

        Fixture(final boolean worldsLoaded, final Duration lastSaveTimeout) {
            keeper = new WorldLeaseKeeper(renewer, releaser, world -> worldsLoaded, world -> {
                lost.add(world);
                if (failOnLost) throw new IllegalStateException("loss handler failed");
            }, lastSaveTimeout);
        }

        /** Waits for every release already handed to the release executor. */
        void drainReleases() throws Exception {
            releaser.submit(() -> { }).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        void close() {
            renewer.shutdownNow();
            releaser.shutdownNow();
        }
    }

    /** A shared source reduced to who holds which world, under which generation. */
    private static final class Fake implements LockableSlimeLoader {
        static final String ME = "me";
        static final String OTHER = "other";
        static final Instant LOCKED_UNTIL = Instant.parse("2026-11-07T12:00:00Z");

        final Map<String, Row> rows = new ConcurrentHashMap<>(Map.of(ISLAND, new Row(null, 0L), NETHER, new Row(null, 0L)));
        final Map<String, Long> held = new ConcurrentHashMap<>();
        final AtomicLong sequence = new AtomicLong();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> releaseThreads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger acquisitions = new AtomicInteger();
        final AtomicInteger releaseAllCalls = new AtomicInteger();
        volatile boolean unreachable;

        @Override
        public synchronized long acquireLock(final String worldName) throws UnknownWorldException, WorldLockedException {
            final Row row = rows.get(worldName);
            if (row == null) throw new UnknownWorldException(worldName);
            if (OTHER.equals(row.owner())) throw new WorldLockedException(worldName, LOCKED_UNTIL);
            final long generation = sequence.incrementAndGet();
            rows.put(worldName, new Row(ME, generation));
            held.put(worldName, generation);
            acquisitions.incrementAndGet();
            events.add("acquired " + worldName + " " + generation);
            return generation;
        }

        @Override
        public Map<String, Long> heldLocks() {
            return Collections.unmodifiableMap(held);
        }

        @Override
        public synchronized Set<String> renewLocks() throws IOException {
            if (unreachable) throw new IOException("source unreachable");
            renewCalls.incrementAndGet();
            final Set<String> lost = new HashSet<>();
            Map.copyOf(held).forEach((world, generation) -> {
                if (!new Row(ME, generation).equals(rows.get(world)) && held.remove(world, generation)) lost.add(world);
            });
            return lost;
        }

        @Override
        public synchronized void releaseLock(final String worldName, final long generation) {
            releaseThreads.add(Thread.currentThread().getName());
            if (new Row(ME, generation).equals(rows.get(worldName))) rows.put(worldName, new Row(null, generation));
            held.remove(worldName, generation);
            events.add("released " + worldName + " " + generation);
        }

        @Override
        public synchronized void releaseAllLocks() {
            releaseAllCalls.incrementAndGet();
            rows.replaceAll((world, row) -> ME.equals(row.owner()) ? new Row(null, row.generation()) : row);
            held.clear();
            events.add("released all");
        }

        @Override
        public Duration lockRenewInterval() {
            return NEVER_DURING_TEST;
        }

        @Override
        public void saveWorld(final String worldName, final byte[] serializedWorld, final long generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readWorld(final String worldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean worldExists(final String worldName) {
            return rows.containsKey(worldName);
        }

        @Override
        public List<String> listWorlds() {
            return List.copyOf(rows.keySet());
        }

        @Override
        public void saveWorld(final String worldName, final byte[] serializedWorld) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteWorld(final String worldName) {
            throw new UnsupportedOperationException();
        }
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
