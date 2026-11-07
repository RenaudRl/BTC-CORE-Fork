package com.infernalsuite.asp.loaders.mysql;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Integration checks of the {@link PostgresLoader} lease against a real PostgreSQL (AD-096).
 *
 * <p>Not wired into {@code check}: it needs a database. Run it with
 * {@code gradlew :loaders:mysql-loader:postgresLockCheck} and {@code BTC_SLIME_PG_TEST_URL}
 * ({@code jdbc:postgresql://host:port/db}), {@code BTC_SLIME_PG_TEST_USER},
 * {@code BTC_SLIME_PG_TEST_PASSWORD} set. A missing variable fails the run: an integration check
 * that silently skips is green while proving nothing. It drops and recreates the {@code worlds}
 * and {@code database_version} tables: point it at a throwaway database only.
 *
 * <p>Two servers are played by two loaders with distinct owners on one database.
 */
public final class PostgresLoaderLockCheck {

    private static final String ISLAND = "btcsky_isle_overworld_0001";
    private static final Duration LEASE = Duration.ofSeconds(1);
    private static final Duration RENEW = Duration.ofMillis(300);
    private static final Duration EXPIRY_DEADLINE = Duration.ofSeconds(10);
    private static final int RACERS = 8;

    private PostgresLoaderLockCheck() {
    }

    public static void main(final String[] args) throws Exception {
        try (HikariDataSource database = dataSource()) {
            resetToLegacySchema(database);

            final PostgresLoader serverA = new PostgresLoader(database, LEASE, RENEW, UUID.randomUUID());
            serverA.update();
            final PostgresLoader serverB = new PostgresLoader(database, LEASE, RENEW, UUID.randomUUID());

            migrationKeepsTheWorldAndAddsTheHolder(serverA);
            leaseIsExclusiveAndFencesSaves(serverA, serverB);
            releasedLeaseCanBeTakenByAnotherServer(serverA, serverB);
            orphanedLeaseExpiresAndIsTakenOver(serverA, serverB);
            missingWorldCannotBeLeased(serverA);
            racingServersGetExactlyOneLease(database);

            dropTables(database);
        }
        System.out.println("PostgresLoaderLockCheck: all checks passed");
    }

    /** A database written by the previous version (v1): no lock_owner column, one world in it. */
    private static void resetToLegacySchema(final HikariDataSource database) throws Exception {
        dropTables(database);
        try (Connection con = database.getConnection(); Statement statement = con.createStatement()) {
            statement.execute("CREATE TABLE worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, world BYTEA)");
            statement.execute("CREATE TABLE database_version (id SERIAL PRIMARY KEY, version INTEGER)");
            statement.execute("INSERT INTO database_version (id, version) VALUES (1, 1)");
            statement.execute("INSERT INTO worlds (name, world) VALUES ('" + ISLAND + "', '\\x01'::bytea)");
        }
    }

    private static void migrationKeepsTheWorldAndAddsTheHolder(final PostgresLoader server) throws Exception {
        check(Arrays.equals(new byte[]{1}, server.readWorld(ISLAND)), "the v2 migration keeps the existing world");
        server.acquireLock(ISLAND);
        server.releaseLock(ISLAND);
    }

    private static void leaseIsExclusiveAndFencesSaves(final PostgresLoader a, final PostgresLoader b) throws Exception {
        a.acquireLock(ISLAND);

        final WorldLockedException refused = expectLocked(() -> {
            b.acquireLock(ISLAND);
            return null;
        });
        check(refused.getWorldName().equals(ISLAND), "the refusal names the world");
        check(refused.getLockedUntil().isAfter(Instant.now().minusSeconds(5)), "the refusal carries the end of the lease");

        expectLocked(() -> {
            b.saveWorld(ISLAND, new byte[]{9});
            return null;
        });
        a.saveWorld(ISLAND, new byte[]{2});
        check(Arrays.equals(new byte[]{2}, b.readWorld(ISLAND)), "the holder's save wins, the other server's is refused");

        check(a.renewLock(ISLAND), "the holder renews its lease");
        check(!b.renewLock(ISLAND), "another server cannot renew a lease it does not hold");
    }

    private static void releasedLeaseCanBeTakenByAnotherServer(final PostgresLoader a, final PostgresLoader b) throws Exception {
        a.releaseLock(ISLAND);
        b.acquireLock(ISLAND);
        check(!a.renewLock(ISLAND), "after a release, the former holder no longer holds the lease");
        a.releaseLock(ISLAND); // releasing a lease one does not hold changes nothing
        check(b.renewLock(ISLAND), "and the new holder keeps it");
    }

    /** B holds the world and "crashes" (stops renewing): A gets it once the lease ran out. */
    private static void orphanedLeaseExpiresAndIsTakenOver(final PostgresLoader a, final PostgresLoader b) throws Exception {
        expectLocked(() -> {
            a.acquireLock(ISLAND);
            return null;
        });

        final Instant deadline = Instant.now().plus(EXPIRY_DEADLINE);
        boolean taken = false;
        while (!taken && Instant.now().isBefore(deadline)) {
            try {
                a.acquireLock(ISLAND);
                taken = true;
            } catch (final WorldLockedException stillHeld) {
                Thread.sleep(RENEW.toMillis()); // polling a real clock, bounded by the deadline
            }
        }
        check(taken, "an orphaned lease is taken over once it expired");
        check(!b.renewLock(ISLAND), "the crashed holder cannot renew a lease taken over");
        expectLocked(() -> {
            b.saveWorld(ISLAND, new byte[]{7});
            return null;
        });
        check(Arrays.equals(new byte[]{2}, a.readWorld(ISLAND)), "and its late save does not clobber the world");
        a.releaseLock(ISLAND);
    }

    private static void missingWorldCannotBeLeased(final PostgresLoader server) throws Exception {
        try {
            server.acquireLock("does_not_exist");
            throw new AssertionError("a missing world must not be leased");
        } catch (final UnknownWorldException expected) {
            // Reported as missing, not as locked.
        }
    }

    private static void racingServersGetExactlyOneLease(final HikariDataSource database) throws Exception {
        final List<PostgresLoader> servers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            servers.add(new PostgresLoader(database, LEASE, RENEW, UUID.randomUUID()));
        }
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        final List<Future<Boolean>> outcomes = new ArrayList<>();
        for (PostgresLoader server : servers) {
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    server.acquireLock(ISLAND);
                    return true;
                } catch (final WorldLockedException lost) {
                    return false;
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get()) winners++;
        }
        pool.shutdown();
        check(winners == 1, "exactly one of " + RACERS + " racing servers gets the lease, got " + winners);
    }

    private static WorldLockedException expectLocked(final Callable<?> attempt) throws Exception {
        try {
            attempt.call();
        } catch (final WorldLockedException refused) {
            return refused;
        }
        throw new AssertionError("expected the world to be locked by another server");
    }

    private static void dropTables(final HikariDataSource database) throws Exception {
        try (Connection con = database.getConnection(); Statement statement = con.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS worlds");
            statement.execute("DROP TABLE IF EXISTS database_version");
        }
    }

    private static HikariDataSource dataSource() {
        final HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required("BTC_SLIME_PG_TEST_URL"));
        config.setUsername(required("BTC_SLIME_PG_TEST_USER"));
        config.setPassword(required("BTC_SLIME_PG_TEST_PASSWORD"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(RACERS + 2);
        return new HikariDataSource(config);
    }

    private static String required(final String variable) {
        final String value = System.getenv(variable);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Set " + variable + " to run the PostgreSQL lock checks");
        }
        return value;
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
