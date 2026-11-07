package com.infernalsuite.asp.lock;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.infernalsuite.asp.api.loaders.LockableSlimeLoader;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free checks for {@link WorldLeaseKeeper} (AD-096), in the fork's {@code main} +
 * assertions idiom. The SQL side of the lease is proven against a real PostgreSQL by
 * {@code PostgresLoaderLockCheck}; this proves what the keeper decides on top of it: take, refuse,
 * release after the last save, give back an unused lease, notice a lost one.
 *
 * <p>Deterministic: renewals are triggered by hand ({@link WorldLeaseKeeper#renew}), the renewal
 * period of the fake is one hour so the executor never fires one on its own, and the clock is a
 * counter.
 */
public final class WorldLeaseKeeperCheck {

    private static final String ISLAND = "btcsky_isle_overworld_0001";
    private static final Duration NEVER_DURING_TEST = Duration.ofHours(1);

    private WorldLeaseKeeperCheck() {
    }

    public static void main(final String[] args) throws Exception {
        acquiredLeaseIsHeldAndRenewed();
        leaseHeldByAnotherServerIsRefused();
        unloadReleasesOnlyAfterTheLastSave();
        worldReadAgainDuringGraceKeepsItsLease();
        leaseReadButNeverLoadedIsGivenBackAfterOneInterval();
        leaseTakenOverByAnotherServerIsForgotten();
        droppedLeaseOnAFreeWorldIsTakenBack();
        unreachableSourceKeepsTheLease();
        releaseAllGivesEveryLeaseBack();
        System.out.println("WorldLeaseKeeperCheck: all checks passed");
    }

    private static void acquiredLeaseIsHeldAndRenewed() throws Exception {
        final Fixture f = new Fixture(true);
        check(f.keeper.acquire(f.loader, ISLAND), "a first acquisition reports a new lease");
        check(f.keeper.holds(ISLAND), "an acquired lease is held");
        check(!f.keeper.acquire(f.loader, ISLAND), "acquiring again reports the lease as already held");

        f.keeper.renew(ISLAND);
        check(f.loader.renewals.get() == 1, "a loaded world's lease is renewed");
        check(f.keeper.holds(ISLAND), "a renewed lease stays held");
        f.close();
    }

    private static void leaseHeldByAnotherServerIsRefused() throws Exception {
        final Fixture f = new Fixture(true);
        f.loader.owners.put(ISLAND, Fake.OTHER);
        try {
            f.keeper.acquire(f.loader, ISLAND);
            throw new AssertionError("a world leased by another server must be refused");
        } catch (final WorldLockedException refused) {
            check(refused.getWorldName().equals(ISLAND), "the refusal names the world");
            check(refused.getMessage().contains(ISLAND) && refused.getMessage().contains(Fake.LOCKED_UNTIL.toString()),
                    "the message names the world and the end of the lease");
        }
        check(!f.keeper.holds(ISLAND), "a refused lease is not held");
        f.close();
    }

    private static void unloadReleasesOnlyAfterTheLastSave() throws Exception {
        final Fixture f = new Fixture(false);
        f.keeper.acquire(f.loader, ISLAND);
        f.keeper.releaseLater(ISLAND, Duration.ZERO, () -> f.loader.events.add("save landed")).get(5, TimeUnit.SECONDS);

        check(f.loader.events.equals(List.of("save landed", "released " + ISLAND)),
                "the lease is released after the pending save, got " + f.loader.events);
        check(!f.keeper.holds(ISLAND), "an unloaded world's lease is no longer held");
        f.close();
    }

    private static void worldReadAgainDuringGraceKeepsItsLease() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.keeper.releaseLater(ISLAND, Duration.ZERO, () -> {
            try {
                f.keeper.acquire(f.loader, ISLAND);
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        }).get(5, TimeUnit.SECONDS);

        check(f.loader.events.isEmpty(), "a world read again before the release keeps its lease, got " + f.loader.events);
        check(f.keeper.holds(ISLAND), "and the keeper still holds it");
        f.close();
    }

    private static void leaseReadButNeverLoadedIsGivenBackAfterOneInterval() throws Exception {
        final Fixture f = new Fixture(false);
        f.keeper.acquire(f.loader, ISLAND);

        f.keeper.renew(ISLAND);
        check(f.keeper.holds(ISLAND), "a world read moments ago is given time to be loaded");

        f.clock.addAndGet(NEVER_DURING_TEST.toNanos());
        f.keeper.renew(ISLAND);
        check(!f.keeper.holds(ISLAND), "a lease nobody loaded is given back after one interval");
        check(f.loader.events.equals(List.of("released " + ISLAND)), "and released in the source");
        f.close();
    }

    private static void leaseTakenOverByAnotherServerIsForgotten() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.owners.put(ISLAND, Fake.OTHER); // our lease expired and another server took it

        f.keeper.renew(ISLAND);
        check(!f.keeper.holds(ISLAND), "a lease taken over is no longer held");
        check(f.loader.owners.get(ISLAND).equals(Fake.OTHER), "and the new holder keeps it");
        f.close();
    }

    private static void droppedLeaseOnAFreeWorldIsTakenBack() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.owners.remove(ISLAND); // a release that raced a new read

        f.keeper.renew(ISLAND);
        check(f.keeper.holds(ISLAND), "a dropped lease on a free world is taken back");
        check(Fake.ME.equals(f.loader.owners.get(ISLAND)), "and the source names this server again");
        f.close();
    }

    private static void unreachableSourceKeepsTheLease() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.loader.unreachable = true;

        f.keeper.renew(ISLAND);
        check(f.keeper.holds(ISLAND), "an unreachable source does not drop the lease: it is retried");
        f.close();
    }

    private static void releaseAllGivesEveryLeaseBack() throws Exception {
        final Fixture f = new Fixture(true);
        f.keeper.acquire(f.loader, ISLAND);
        f.keeper.acquire(f.loader, "btcsky_isle_nether_0002");

        f.keeper.releaseAll();
        check(!f.keeper.holds(ISLAND) && !f.keeper.holds("btcsky_isle_nether_0002"), "nothing is held after releaseAll");
        check(f.loader.owners.isEmpty(), "and every world is free in the source, got " + f.loader.owners);
        f.close();
    }

    private static final class Fixture {
        final Fake loader = new Fake();
        final AtomicLong clock = new AtomicLong();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("lease-check").factory());
        final WorldLeaseKeeper keeper;

        Fixture(final boolean worldsLoaded) {
            keeper = new WorldLeaseKeeper(executor, world -> worldsLoaded, clock::get);
        }

        void close() {
            executor.shutdownNow();
        }
    }

    /** A shared source reduced to who holds which world. */
    private static final class Fake implements LockableSlimeLoader {
        static final String ME = "me";
        static final String OTHER = "other";
        static final Instant LOCKED_UNTIL = Instant.parse("2026-11-07T12:00:00Z");

        final Map<String, String> owners = new ConcurrentHashMap<>();
        final Set<String> worlds = Set.of(ISLAND, "btcsky_isle_nether_0002");
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong renewals = new AtomicLong();
        volatile boolean unreachable;

        @Override
        public void acquireLock(final String worldName) throws UnknownWorldException, WorldLockedException {
            if (!worlds.contains(worldName)) throw new UnknownWorldException(worldName);
            if (OTHER.equals(owners.get(worldName))) throw new WorldLockedException(worldName, LOCKED_UNTIL);
            owners.put(worldName, ME);
        }

        @Override
        public boolean renewLock(final String worldName) throws IOException {
            if (unreachable) throw new IOException("source unreachable");
            renewals.incrementAndGet();
            return ME.equals(owners.get(worldName));
        }

        @Override
        public void releaseLock(final String worldName) {
            if (owners.remove(worldName, ME)) events.add("released " + worldName);
        }

        @Override
        public Duration lockRenewInterval() {
            return NEVER_DURING_TEST;
        }

        @Override
        public byte[] readWorld(final String worldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean worldExists(final String worldName) {
            return worlds.contains(worldName);
        }

        @Override
        public List<String> listWorlds() {
            return List.copyOf(worlds);
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
