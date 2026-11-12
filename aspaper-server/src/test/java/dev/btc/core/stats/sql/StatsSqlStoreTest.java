package dev.btc.core.stats.sql;

import dev.btc.core.stats.sql.StatsSqlStore.Row;
import dev.btc.core.stats.sql.StatsSqlStore.Write;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two "services" are two stores with their own owner id on one H2 database (PostgreSQL mode).
 * The database clock is {@link #CLOCK}: it starts in 1970, far from this machine's clock.
 */
public class StatsSqlStoreTest {

    public static final AtomicLong CLOCK = new AtomicLong();
    private static final long LEASE_MS = 60_000;
    private static final String KILLS_42 = "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":42}},\"DataVersion\":4000}";
    private static final String KILLS_43 = "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":43}},\"DataVersion\":4000}";

    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    private final UUID player = UUID.randomUUID();
    private final UUID ownerB = UUID.randomUUID();
    private final List<Connection> opened = new ArrayList<>();
    private Connection keepAlive;
    private StatsSqlStore a;
    private StatsSqlStore b;

    /** The H2 stand-in for PostgreSQL's clock_timestamp(). */
    public static long nowMs() {
        return CLOCK.get();
    }

    @BeforeEach
    void open() throws SQLException {
        CLOCK.set(1_000);
        keepAlive = DriverManager.getConnection(url);
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE ALIAS btc_now_ms FOR \"" + StatsSqlStoreTest.class.getName() + ".nowMs\"");
        }
        a = store(UUID.randomUUID());
        b = store(ownerB);
        a.ensureSchema();
    }

    @AfterEach
    void close() throws SQLException {
        StatsSqlThreadGuard.install(thread -> false, false);
        a.close();
        b.close();
        keepAlive.close();
    }

    @Test
    void statisticKeptAfterServiceSwitch() throws SQLException {
        Row onA = a.acquire(player, false);
        assertNull(onA.payload(), "a new player has no document yet");
        assertTrue(a.write(new Write(player, onA.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, true)), "quit: flush and release in one statement");

        Row onB = b.acquire(player, false);
        assertEquals(KILLS_42, onB.payload(), "B reads the 42 kills made on A");
        assertTrue(b.write(new Write(player, onB.generation(), KILLS_43, 4000, StatsSqlStore.SOURCE_RUNTIME, true)));

        assertEquals(KILLS_43, a.peek(player).payload());
        assertEquals(2, a.peek(player).revision());
    }

    @Test
    void acquireIsRefusedWhileAnotherOwnerHoldsALiveLeaseThenGrantedOnRelease() throws SQLException {
        Row onA = a.acquire(player, false);

        assertNull(b.acquire(player, false), "B must wait: A still holds the row");
        assertTrue(a.release(player, onA.generation()));

        Row onB = b.acquire(player, false);
        assertNotNull(onB);
        assertEquals(onA.generation() + 1, onB.generation());
    }

    @Test
    void lateFlushFromFormerOwnerIsRejectedAfterTakeover() throws SQLException {
        Row onA = a.acquire(player, false);
        Row onB = b.acquire(player, true);
        assertTrue(b.write(new Write(player, onB.generation(), KILLS_43, 4000, StatsSqlStore.SOURCE_RUNTIME, false)));

        assertFalse(a.write(new Write(player, onA.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, true)));
        assertFalse(a.release(player, onA.generation()));
        assertEquals(List.of(player), a.renew(Map.of(player, onA.generation())), "A learns it lost the row");

        assertEquals(KILLS_43, b.peek(player).payload(), "B's document is untouched");
        assertEquals(ownerB, a.holder(player));
    }

    @Test
    void writeWithoutLeaseIsRefused() throws SQLException {
        Row onA = a.acquire(player, false);
        a.release(player, onA.generation());

        assertFalse(a.write(new Write(player, onA.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, false)));
        assertFalse(b.write(new Write(UUID.randomUUID(), 1, KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, false)), "no row at all");
        assertNull(a.peek(player).payload());
    }

    @Test
    void sameOwnerTakesItsOwnLeaseAgainWithANewGeneration() throws SQLException {
        Row first = a.acquire(player, false);
        Row second = a.acquire(player, false);

        assertEquals(first.generation() + 1, second.generation());
        assertFalse(a.write(new Write(player, first.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, false)), "the earlier session of the same JVM is fenced too");
    }

    @Test
    void databaseTimeIsUsedNotLocalClock() throws SQLException {
        a.acquire(player, false); // at database time 1000, half a century before this machine's clock

        CLOCK.set(1_000 + LEASE_MS);
        assertNull(b.acquire(player, false), "still held at the last millisecond of the lease");
        CLOCK.set(1_000 + LEASE_MS + 1);
        assertNotNull(b.acquire(player, false), "expired by the database clock: taken without force");
    }

    @Test
    void leaseRenewalKeepsRowOwnedAndExpiryAllowsTakeoverWithoutForce() throws SQLException {
        Row onA = a.acquire(player, false);

        CLOCK.set(1_000 + LEASE_MS - 1);
        assertEquals(List.of(), a.renew(Map.of(player, onA.generation())));
        CLOCK.set(1_000 + LEASE_MS + 1);
        assertNull(b.acquire(player, false), "renewed: the lease now runs from the renewal");

        CLOCK.set(1_000 + 2 * LEASE_MS);
        assertNotNull(b.acquire(player, false));
    }

    @Test
    void concurrentAcquireByManyOwnersHasExactlyOneWinner() throws Exception {
        int owners = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Row> won = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < owners; i++) {
            StatsSqlStore store = store(UUID.randomUUID());
            Thread thread = new Thread(() -> {
                try (store) {
                    go.await();
                    Row row = store.acquire(player, false);
                    if (row != null) won.add(row);
                } catch (Throwable failure) {
                    failed.add(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) thread.join(10_000);

        assertEquals(List.of(), failed);
        assertEquals(1, won.size());
    }

    @Test
    void writeBatchStoresEveryDocumentAndReportsOnlyTheFencedOnes() throws SQLException {
        UUID other = UUID.randomUUID();
        Row mine = a.acquire(player, false);
        a.acquire(other, false);
        Row taken = b.acquire(other, true);

        Write kept = new Write(player, mine.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, false);
        Write fenced = new Write(other, taken.generation() - 1, KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, true);

        assertEquals(List.of(fenced), a.writeBatch(List.of(kept, fenced)));
        assertEquals(KILLS_42, a.peek(player).payload());
        assertNull(a.peek(other).payload());
    }

    @Test
    void offlineWriteRefusedWhileLiveLeaseExists() throws SQLException {
        a.acquire(player, false);

        assertFalse(b.writeOffline(player, 0, KILLS_43, 4000, StatsSqlStore.SOURCE_MERGE));
        assertNull(b.peek(player).payload());
    }

    @Test
    void offlineWriteSucceedsWhenFreeWithRevisionCompareAndRefusesAStaleRevision() throws SQLException {
        Row onA = a.acquire(player, false);
        a.write(new Write(player, onA.generation(), KILLS_42, 4000, StatsSqlStore.SOURCE_RUNTIME, true));
        long revision = b.peek(player).revision();

        assertTrue(b.writeOffline(player, revision, KILLS_43, 4000, StatsSqlStore.SOURCE_MERGE));
        assertFalse(b.writeOffline(player, revision, KILLS_42, 4000, StatsSqlStore.SOURCE_MERGE), "the row moved on since it was read");
        assertEquals(KILLS_43, b.peek(player).payload());
    }

    @Test
    void bulkImportIsIdempotentAndNeverOverwritesExistingPayload() throws SQLException {
        assertTrue(a.importIfAbsent(player, KILLS_42, 4000));
        assertFalse(a.importIfAbsent(player, KILLS_43, 4000), "second pass: already there");
        assertEquals(KILLS_42, a.peek(player).payload());
        assertEquals(4000, a.peek(player).dataVersion());

        UUID reserved = UUID.randomUUID();
        Row row = a.acquire(reserved, false);
        assertFalse(b.importIfAbsent(reserved, KILLS_42, 4000), "reserved by a live session: that session seeds it itself");
        a.release(reserved, row.generation());
        assertTrue(b.importIfAbsent(reserved, KILLS_42, 4000), "a row without a document is filled");
        assertFalse(b.importIfAbsent(reserved, KILLS_43, 4000));
    }

    @Test
    void createIfNotExistsIsIdempotentAndVersioned() throws SQLException {
        a.ensureSchema();
        b.ensureSchema();

        try (Statement statement = keepAlive.createStatement();
             var rows = statement.executeQuery("SELECT meta_value FROM t_player_stats_meta WHERE meta_key = 'schema_version'")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void newerSchemaVersionLeavesLayerClosed() throws SQLException {
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("UPDATE t_player_stats_meta SET meta_value = '2' WHERE meta_key = 'schema_version'");
        }

        SQLException refused = assertThrows(StatsSqlStore.NewerSchemaException.class, () -> b.ensureSchema());
        assertTrue(refused.getMessage().contains("schema_version"), refused.getMessage());
    }

    @Test
    void storeCalledFromGameThreadThrowsInTests() {
        Thread gameThread = Thread.currentThread();
        StatsSqlThreadGuard.install(thread -> thread == gameThread, false);

        assertThrows(IllegalStateException.class, () -> a.acquire(player, false));
        assertThrows(IllegalStateException.class, () -> a.probe());
        assertThrows(IllegalStateException.class, () -> a.peek(player));
    }

    @Test
    void aDeadConnectionIsReopened() throws SQLException {
        StatsSqlStore store = new StatsSqlStore(new StatsSqlStatements("t_", "btc_now_ms()"), () -> {
            Connection connection = DriverManager.getConnection(url);
            opened.add(connection);
            return connection;
        }, UUID.randomUUID(), LEASE_MS, false);
        store.probe();
        store.probe();
        assertEquals(1, opened.size(), "one connection, kept between calls");

        opened.get(0).close(); // what a database restart looks like from here

        assertNotNull(store.acquire(player, false));
        assertEquals(2, opened.size());
        store.close();
        assertTrue(opened.get(1).isClosed());
    }

    private StatsSqlStore store(UUID owner) {
        return new StatsSqlStore(new StatsSqlStatements("t_", "btc_now_ms()"), () -> DriverManager.getConnection(url), owner, LEASE_MS, false);
    }
}
