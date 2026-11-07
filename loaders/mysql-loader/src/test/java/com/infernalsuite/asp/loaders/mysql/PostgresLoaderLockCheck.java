package com.infernalsuite.asp.loaders.mysql;

import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.exceptions.WorldAlreadyExistsException;
import com.infernalsuite.asp.api.exceptions.WorldLockedException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
 * and {@code database_version} tables and the generation sequence: point it at a throwaway
 * database only.
 *
 * <p>Servers are played by loaders with distinct owners on one database. A lease "expires" by
 * setting its end in the past, which is exactly what the passing of time does.
 */
public final class PostgresLoaderLockCheck {

    private static final String ISLAND = "btcsky_isle_overworld_0001";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration RENEW = Duration.ofSeconds(10);
    private static final int RACERS = 8;

    private PostgresLoaderLockCheck() {
    }

    private record Server(PostgresLoader loader, String owner) {
    }

    public static void main(final String[] args) throws Exception {
        try (HikariDataSource database = dataSource()) {
            migratesFromV1(database);
            migratesFromV2(database);

            acquisitionDrawsANewGenerationAndExcludesOthers(database);
            staleSaveAfterAnotherServerReleasedIsRefused(database);
            staleSaveAfterAnotherServerCrashedIsRefused(database);
            failedRenewalNeverTakesTheLeaseBack(database);
            deleteIsRefusedWhileAnotherServerHoldsTheWorld(database);
            newWorldIsCreatedAlreadyLeased(database);
            releaseAllFreesOnlyThisServersLeases(database);
            missingWorldCannotBeLeased(database);
            racingServersGetExactlyOneLease(database);
            racingCreatorsGetExactlyOneWorld(database);
            renewalLongerThanHalfTheLeaseIsRefused(database);
            lateInsertAfterDeleteIsRefused(database);
            staleCopyAfterTakeoverIsRefused(database);

            racingFirstStartsCreateTheSchemaOnce(database);
            dropSchema(database);
        }
        System.out.println("PostgresLoaderLockCheck: all checks passed");
    }

    /** A database written by the upstream version (v1): no lease columns, one world in it. */
    private static void migratesFromV1(final HikariDataSource database) throws Exception {
        dropSchema(database);
        sql(database, "CREATE TABLE worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, world BYTEA)");
        sql(database, "CREATE TABLE database_version (id SERIAL PRIMARY KEY, version INTEGER)");
        sql(database, "INSERT INTO database_version (id, version) VALUES (1, 1)");
        sql(database, "INSERT INTO worlds (name, world) VALUES ('" + ISLAND + "', '\\x01'::bytea)");

        checkMigrated(database, "v1");
    }

    /** A bench database written by the first lease build (v2): holder column, no generation yet. */
    private static void migratesFromV2(final HikariDataSource database) throws Exception {
        dropSchema(database);
        sql(database, "CREATE TABLE worlds (id SERIAL PRIMARY KEY, name VARCHAR(255) UNIQUE, locked BIGINT DEFAULT 0, "
                + "lock_owner VARCHAR(36), world BYTEA)");
        sql(database, "CREATE TABLE database_version (id SERIAL PRIMARY KEY, version INTEGER)");
        sql(database, "INSERT INTO database_version (id, version) VALUES (1, 2)");
        sql(database, "INSERT INTO worlds (name, world) VALUES ('" + ISLAND + "', '\\x01'::bytea)");

        checkMigrated(database, "v2");
    }

    private static void checkMigrated(final HikariDataSource database, final String from) throws Exception {
        final Server server = server(database);
        server.loader().update();
        check(Arrays.equals(new byte[]{1}, server.loader().readWorld(ISLAND)), "the migration from " + from + " keeps the world");
        check(scalar(database, "SELECT version FROM database_version WHERE id = 1").equals("3"), "the schema is at v3 after " + from);
        check(scalar(database, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'worlds' "
                + "AND column_name IN ('lock_owner', 'lock_generation')").equals("2"), "both lease columns exist after " + from);
        final long generation = server.loader().acquireLock(ISLAND);
        check(generation > 0, "the generation sequence works after " + from);
        server.loader().releaseLock(ISLAND, generation);
    }

    private static void acquisitionDrawsANewGenerationAndExcludesOthers(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        final long first = a.loader().acquireLock(ISLAND);
        final long again = a.loader().acquireLock(ISLAND);
        check(again > first, "re-acquiring draws a newer generation, fencing the older one");
        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{9}, first));

        final WorldLockedException refused = expectLocked(() -> b.loader().acquireLock(ISLAND));
        check(refused.getWorldName().equals(ISLAND), "the refusal names the world");
        check(refused.getLockedUntil() != null && refused.getLockedUntil().isAfter(Instant.now().minusSeconds(5)),
                "the refusal carries the end of the lease");

        final long before = Long.parseLong(scalar(database, "SELECT locked FROM worlds WHERE name = '" + ISLAND + "'"));
        Thread.sleep(5); // the database clock must move for the extension to show
        check(a.loader().renewLocks().isEmpty(), "the holder renews its lease");
        final long after = Long.parseLong(scalar(database, "SELECT locked FROM worlds WHERE name = '" + ISLAND + "'"));
        check(after > before, "and the renewal extends it");
        a.loader().saveWorld(ISLAND, new byte[]{2}, again);
        check(Arrays.equals(new byte[]{2}, b.loader().readWorld(ISLAND)), "the holder's save lands");
        a.loader().releaseLock(ISLAND, again);
    }

    /** C1: A loses its lease, B takes, writes and releases; A's next autosave must not clobber B. */
    private static void staleSaveAfterAnotherServerReleasedIsRefused(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        final long staleGeneration = a.loader().acquireLock(ISLAND);
        expire(database, ISLAND);

        final long generation = b.loader().acquireLock(ISLAND);
        b.loader().saveWorld(ISLAND, new byte[]{5}, generation);
        b.loader().releaseLock(ISLAND, generation);

        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{6}, staleGeneration));
        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{6}));
        check(Arrays.equals(new byte[]{5}, a.loader().readWorld(ISLAND)), "B's data survives A's stale saves on a free world");
    }

    private static void staleSaveAfterAnotherServerCrashedIsRefused(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        final long staleGeneration = a.loader().acquireLock(ISLAND);
        expire(database, ISLAND);

        final long generation = b.loader().acquireLock(ISLAND);
        b.loader().saveWorld(ISLAND, new byte[]{7}, generation);
        expire(database, ISLAND); // B crashed: its lease runs out, nobody released it

        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{8}, staleGeneration));
        check(Arrays.equals(new byte[]{7}, a.loader().readWorld(ISLAND)), "B's data survives A's stale save after B crashed");
        free(database);
    }

    private static void failedRenewalNeverTakesTheLeaseBack(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        a.loader().acquireLock(ISLAND);
        expire(database, ISLAND);
        b.loader().acquireLock(ISLAND);
        expire(database, ISLAND); // and B crashed too: the world is up for grabs

        check(a.loader().renewLocks().equals(Set.of(ISLAND)), "a lease taken over is reported lost");
        check(a.loader().heldLocks().isEmpty(), "and is no longer held");
        check(b.owner().equals(owner(database, ISLAND)), "the renewal does not take the world back, even expired");
        free(database);

        final long generation = a.loader().acquireLock(ISLAND);
        sql(database, "UPDATE worlds SET lock_owner = NULL, locked = 0 WHERE name = '" + ISLAND + "'"); // freed behind A's back
        check(a.loader().renewLocks().equals(Set.of(ISLAND)), "a lease dropped on a free world is lost too");
        check(owner(database, ISLAND) == null, "and never taken back in silence");
        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{9}, generation));
    }

    private static void deleteIsRefusedWhileAnotherServerHoldsTheWorld(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        final long generation = b.loader().acquireLock(ISLAND);
        expectLocked(() -> {
            a.loader().deleteWorld(ISLAND);
            return null;
        });
        check(a.loader().worldExists(ISLAND), "a world another server holds is not deleted");
        b.loader().releaseLock(ISLAND, generation);

        a.loader().createWorld("btcsky_scratch_held", new byte[]{3});
        expectLocked(() -> {
            b.loader().deleteWorld("btcsky_scratch_held");
            return null;
        });
        a.loader().deleteWorld("btcsky_scratch_held");
        check(!a.loader().worldExists("btcsky_scratch_held"), "the holder deletes its world");

        a.loader().createWorld("btcsky_scratch_free", new byte[]{4});
        a.loader().releaseLock("btcsky_scratch_free", a.loader().heldLocks().get("btcsky_scratch_free"));
        b.loader().deleteWorld("btcsky_scratch_free");
        check(!b.loader().worldExists("btcsky_scratch_free"), "a free world can be deleted by any server");

        try {
            a.loader().deleteWorld("does_not_exist");
            throw new AssertionError("deleting a missing world must report it missing");
        } catch (final UnknownWorldException expected) {
            // Reported as missing, not as locked.
        }
    }

    private static void newWorldIsCreatedAlreadyLeased(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        a.loader().createWorld("btcsky_new_isle", new byte[]{8});
        check(a.loader().heldLocks().containsKey("btcsky_new_isle"), "creating a world leases it to its creator");
        check(a.owner().equals(owner(database, "btcsky_new_isle")), "in the same statement");

        expectLocked(() -> b.loader().saveWorld("btcsky_new_isle", new byte[]{9}));
        expectLocked(() -> b.loader().acquireLock("btcsky_new_isle"));
        expectExists(() -> b.loader().createWorld("btcsky_new_isle", new byte[]{9}));
        expectExists(() -> a.loader().createWorld("btcsky_new_isle", new byte[]{9}));
        check(Arrays.equals(new byte[]{8}, b.loader().readWorld("btcsky_new_isle")),
                "an existing world is never overwritten by a server without its lease");
        a.loader().deleteWorld("btcsky_new_isle");
    }

    private static void releaseAllFreesOnlyThisServersLeases(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final Server b = server(database);
        a.loader().acquireLock(ISLAND);
        a.loader().createWorld("btcsky_isle_a2", new byte[]{1});
        b.loader().createWorld("btcsky_isle_b1", new byte[]{1});

        a.loader().releaseAllLocks();
        check(a.loader().heldLocks().isEmpty(), "nothing is held after the grouped release");
        check(owner(database, ISLAND) == null && owner(database, "btcsky_isle_a2") == null,
                "every lease of the stopping server is released in one statement");
        check(b.owner().equals(owner(database, "btcsky_isle_b1")), "another server's lease is untouched");

        a.loader().deleteWorld("btcsky_isle_a2");
        b.loader().deleteWorld("btcsky_isle_b1");
    }

    private static void missingWorldCannotBeLeased(final HikariDataSource database) throws Exception {
        try {
            server(database).loader().acquireLock("does_not_exist");
            throw new AssertionError("a missing world must not be leased");
        } catch (final UnknownWorldException expected) {
            // Reported as missing, not as locked.
        }
    }

    private static void racingServersGetExactlyOneLease(final HikariDataSource database) throws Exception {
        free(database);
        final int winners = race(database, server -> server.loader().acquireLock(ISLAND));
        check(winners == 1, "exactly one of " + RACERS + " racing servers gets the lease, got " + winners);
        free(database);
    }

    private static void racingCreatorsGetExactlyOneWorld(final HikariDataSource database) throws Exception {
        final int winners = race(database, server -> {
            server.loader().createWorld("btcsky_raced_isle", new byte[]{1});
            return null;
        });
        check(winners == 1, "exactly one of " + RACERS + " servers creating the same world succeeds, got " + winners);
        sql(database, "DELETE FROM worlds WHERE name = 'btcsky_raced_isle'");
    }

    private static void renewalLongerThanHalfTheLeaseIsRefused(final HikariDataSource database) throws Exception {
        try {
            new PostgresLoader(database, Duration.ofSeconds(10), Duration.ofSeconds(6), UUID.randomUUID());
            throw new AssertionError("a renewal longer than half the lease must be refused");
        } catch (final IllegalArgumentException expected) {
            // One missed renewal would lose the lease.
        }
    }

    /** M-B: an island deleted must never come back through a late write of the server that held it. */
    private static void lateInsertAfterDeleteIsRefused(final HikariDataSource database) throws Exception {
        final Server a = server(database);
        final String island = "btcsky_deleted_isle";
        final long generation = a.loader().createWorld(island, new byte[]{1});
        a.loader().deleteWorld(island);

        expectLocked(() -> a.loader().saveWorld(island, new byte[]{2}));
        expectLocked(() -> a.loader().saveWorld(island, new byte[]{2}, generation));
        check(!a.loader().worldExists(island), "a late write never brings a deleted island back");
    }

    /** M-B: A lost the island to B, which wrote it; whatever copy A still has, it never lands. */
    private static void staleCopyAfterTakeoverIsRefused(final HikariDataSource database) throws Exception {
        free(database);
        final Server a = server(database);
        final Server b = server(database);
        final long staleGeneration = a.loader().acquireLock(ISLAND);
        expire(database, ISLAND);
        final long generation = b.loader().acquireLock(ISLAND);
        b.loader().saveWorld(ISLAND, new byte[]{11}, generation);

        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{12}));
        expectLocked(() -> a.loader().saveWorld(ISLAND, new byte[]{12}, staleGeneration));
        expectExists(() -> a.loader().createWorld(ISLAND, new byte[]{12}));
        expectLocked(() -> b.loader().saveWorld(ISLAND, new byte[]{13})); // even the holder: a copy carries no generation
        check(Arrays.equals(new byte[]{11}, a.loader().readWorld(ISLAND)), "B's data survives every stale copy of A");
        b.loader().releaseLock(ISLAND, generation);
    }

    /** LOW: servers starting together on an empty database all open it (no race on CREATE ... IF NOT EXISTS). */
    private static void racingFirstStartsCreateTheSchemaOnce(final HikariDataSource database) throws Exception {
        dropSchema(database);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        final List<Future<PostgresLoader>> opened = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            opened.add(pool.submit(() -> {
                start.await();
                return new PostgresLoader(database, LEASE, RENEW, UUID.randomUUID());
            }));
        }
        start.countDown();
        for (Future<PostgresLoader> loader : opened) {
            loader.get(); // a failed CREATE would surface here
        }
        pool.shutdown();
        check(scalar(database, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'worlds'").equals("1"),
                "the schema exists once");
    }

    private static void expectExists(final ThrowingRunnable attempt) throws Exception {
        try {
            attempt.run();
        } catch (final WorldAlreadyExistsException refused) {
            return;
        }
        throw new AssertionError("expected the creation to be refused: the world exists");
    }

    @FunctionalInterface
    private interface Attempt {
        Object run(Server server) throws Exception;
    }

    /** Runs {@code attempt} on {@value #RACERS} servers released at once; counts those not refused. */
    private static int race(final HikariDataSource database, final Attempt attempt) throws Exception {
        final List<Server> servers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            servers.add(server(database));
        }
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        final List<Future<Boolean>> outcomes = new ArrayList<>();
        for (Server server : servers) {
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    attempt.run(server);
                    return true;
                } catch (final WorldLockedException | WorldAlreadyExistsException lost) {
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
        return winners;
    }

    private static Server server(final HikariDataSource database) throws Exception {
        final UUID owner = UUID.randomUUID();
        return new Server(new PostgresLoader(database, LEASE, RENEW, owner), owner.toString());
    }

    private static WorldLockedException expectLocked(final Callable<?> attempt) throws Exception {
        try {
            attempt.call();
        } catch (final WorldLockedException refused) {
            return refused;
        }
        throw new AssertionError("expected the write to be refused for want of the lease");
    }

    private static WorldLockedException expectLocked(final ThrowingRunnable attempt) throws Exception {
        return expectLocked(() -> {
            attempt.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The lease on {@code world} runs out, as if its holder had stopped renewing it long enough. */
    private static void expire(final HikariDataSource database, final String world) throws Exception {
        sql(database, "UPDATE worlds SET locked = 0 WHERE name = '" + world + "'");
    }

    /** Every lease released, generations kept: the state after every holder stopped cleanly. */
    private static void free(final HikariDataSource database) throws Exception {
        sql(database, "UPDATE worlds SET lock_owner = NULL, locked = 0");
    }

    private static String owner(final HikariDataSource database, final String world) throws Exception {
        try (Connection con = database.getConnection();
             PreparedStatement statement = con.prepareStatement("SELECT lock_owner FROM worlds WHERE name = ?")) {
            statement.setString(1, world);
            try (ResultSet set = statement.executeQuery()) {
                check(set.next(), "world " + world + " exists");
                return set.getString(1);
            }
        }
    }

    private static String scalar(final HikariDataSource database, final String query) throws Exception {
        try (Connection con = database.getConnection(); Statement statement = con.createStatement();
             ResultSet set = statement.executeQuery(query)) {
            check(set.next(), "a row for " + query);
            return set.getString(1);
        }
    }

    private static void sql(final HikariDataSource database, final String statementText) throws Exception {
        try (Connection con = database.getConnection(); Statement statement = con.createStatement()) {
            statement.execute(statementText);
        }
    }

    private static void dropSchema(final HikariDataSource database) throws Exception {
        sql(database, "DROP TABLE IF EXISTS worlds");
        sql(database, "DROP TABLE IF EXISTS database_version");
        sql(database, "DROP SEQUENCE IF EXISTS worlds_lock_generation_seq");
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
