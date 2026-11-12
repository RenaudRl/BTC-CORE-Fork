package dev.btc.core.stats.sql;

import com.google.gson.JsonParser;
import dev.btc.core.playerdata.PlayerDataIo;
import dev.btc.core.stats.sql.StatsSqlService.Loaded;
import dev.btc.core.stats.sql.StatsSqlService.Migration;
import dev.btc.core.stats.sql.StatsSqlService.Mode;
import dev.btc.core.stats.sql.StatsSqlService.State;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two servers are two services with their own owner id and their own stats folder on one H2
 * database (PostgreSQL mode). Timers are set far away: the tests run the batch and the probe
 * themselves.
 */
class StatsSqlServiceTest {

    private static final String SECRET = "hunter2";

    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    private final UUID player = UUID.randomUUID();
    private final List<Server> servers = new ArrayList<>();
    private Connection keepAlive;
    private StatsSqlStore inspector;
    @TempDir
    Path folder;

    /** One connection source per store, so a test can cut or hold exactly one of them. */
    private final class Source implements StatsSqlStore.ConnectionSource {
        private final List<Connection> live = new CopyOnWriteArrayList<>();
        private volatile boolean down;
        private volatile CountDownLatch hold;
        private volatile CountDownLatch waiting = new CountDownLatch(1);
        /** Transactions committed through this store. */
        private final AtomicInteger commits = new AtomicInteger();

        @Override
        public Connection open() throws SQLException {
            CountDownLatch latch = hold;
            if (latch != null) {
                waiting.countDown();
                try {
                    latch.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    throw new SQLException("interrupted", "08000");
                }
            }
            if (down) {
                throw new SQLException("refused for user=stats_user password=" + SECRET, "08001");
            }
            Connection real = DriverManager.getConnection(url);
            Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("commit")) commits.incrementAndGet();
                    try {
                        return method.invoke(real, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            live.add(connection);
            return connection;
        }

        void cut() throws SQLException {
            for (Connection connection : live) connection.close();
            live.clear();
        }

        /** The next call of this store stays in open() until {@link #release()}. */
        void hold() throws SQLException {
            waiting = new CountDownLatch(1);
            hold = new CountDownLatch(1);
            cut();
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(waiting.await(5, TimeUnit.SECONDS), "the store never asked for a connection");
        }

        void release() {
            CountDownLatch latch = hold;
            hold = null;
            if (latch != null) latch.countDown();
        }
    }

    private final class Server {
        final Source reader = new Source();
        final Source writer = new Source();
        final Source prober = new Source();
        final PlayerDataIo files = new PlayerDataIo();
        final Path stats;
        final StatsSqlService service;
        /** What each connection was given by the layer, as the server keeps it for the player's counter. */
        final Map<UUID, CompletableFuture<Loaded>> connections = new HashMap<>();

        Server(String name, Object... keysAndValues) throws IOException {
            stats = Files.createDirectories(folder.resolve(name).resolve("stats"));
            YamlConfiguration yaml = new YamlConfiguration();
            Object[] base = {"host", "localhost", "port", "5432", "database", "stats", "username", "u", "password", "p",
                "table-prefix", "t_", "handoff-wait-ms", 300, "handoff-poll-ms", 20, "batch-interval-ms", 3_600_000,
                "probe-interval-seconds", 3600, "failure-threshold", 2};
            for (Object[] pairs : new Object[][] {base, keysAndValues}) {
                for (int i = 0; i < pairs.length; i += 2) yaml.set("stats-sql." + pairs[i], pairs[i + 1]);
            }
            StatsSqlConfig config = StatsSqlConfig.read(yaml.getConfigurationSection("stats-sql"), variable -> null);
            UUID owner = UUID.randomUUID();
            service = new StatsSqlService(config, store(reader, owner), store(writer, owner), store(prober, owner), files);
            servers.add(this);
            assertEquals(State.HEALTHY, service.start().join());
        }

        Path file(UUID id) {
            return stats.resolve(id + ".json");
        }

        Loaded login(UUID id) throws Exception {
            connections.put(id, service.preload(id, file(id)));
            return connections.get(id).get(5, TimeUnit.SECONDS);
        }

        List<UUID> loginMany(int count) throws Exception {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(UUID.randomUUID());
                login(ids.get(i));
            }
            return ids;
        }

        /** What the counter of the player of the last {@link #login} does. */
        Loaded claim(UUID id) {
            return service.claim(id, connections.get(id));
        }

        /** The connection of the last {@link #login} closes. */
        void abandon(UUID id, boolean online) {
            service.abandon(id, connections.get(id), online);
        }

        /** What the server does when a player leaves: a last save, then the quit. */
        void quit(UUID id, String document) throws Exception {
            assertTrue(service.save(id, document, 4000));
            service.quit(id).get(5, TimeUnit.SECONDS);
            files.await(id);
        }

        void outage() throws SQLException {
            for (Source source : List.of(reader, writer, prober)) {
                source.down = true;
                source.cut();
            }
        }

        void repaired() {
            for (Source source : List.of(reader, writer, prober)) source.down = false;
        }

        /** Two failed batches, of a player nobody looks at: the breaker opens at failure-threshold 2. */
        void breakerOpens() throws Exception {
            UUID canary = UUID.randomUUID();
            login(canary);
            service.save(canary, doc(0, 0), 4000);
            outage();
            service.flush().join();
            service.flush().join();
            assertEquals(State.DEGRADED, service.state());
        }

        String status() {
            return String.join("\n", service.status());
        }
    }

    @BeforeEach
    void open() throws SQLException {
        StatsSqlStoreTest.CLOCK.set(1_000);
        keepAlive = DriverManager.getConnection(url);
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE ALIAS btc_now_ms FOR \"" + StatsSqlStoreTest.class.getName() + ".nowMs\"");
        }
        inspector = store(() -> DriverManager.getConnection(url), UUID.randomUUID());
    }

    @AfterEach
    void close() throws SQLException {
        StatsSqlThreadGuard.install(thread -> false, false);
        StatsSql.stop();
        for (Server server : servers) {
            for (Source source : List.of(server.reader, server.writer, server.prober)) source.release();
            server.service.shutdown();
            server.files.shutdown(5, TimeUnit.SECONDS);
        }
        inspector.close();
        keepAlive.close();
    }

    // ---- service switch and handoff ----

    @Test
    void statisticKeptAfterServiceSwitch() throws Exception {
        Server a = new Server("a");
        Server b = new Server("b");
        Files.writeString(b.file(player), doc(7, 999)); // B's own old file plays no part

        assertEquals(new Loaded(Mode.SQL, null), a.login(player), "a new player starts from nothing");
        a.quit(player, doc(42, 5));
        Loaded onB = b.login(player);

        assertEquals(Mode.SQL, onB.mode());
        assertDocument(doc(42, 5), onB.document());
        b.quit(player, doc(43, 5)); // one more on B, from what A left

        assertDocument(doc(43, 5), payload(player), "the value after the switch counts what was played on both");
        assertNull(holder(player));
    }

    @Test
    void theDocumentHandedOverIsStoredAsItWasEncoded() throws Exception {
        Server a = new Server("a");
        String encoded = "{ \"stats\": {\"minecraft:mined\": {\"minecraft:stone\": 7}, \"minecraft:custom\": {\"minecraft:jump\": 3}},\n  \"DataVersion\": 4000 }";
        a.login(player);

        a.quit(player, encoded);

        assertEquals(encoded, payload(player), "the layer stores the counter's own encoding, character for character");
        assertEquals(encoded, a.login(player).document(), "and hands it back as it was stored");
    }

    @Test
    void aSecondConnectionOfAPlayerInTheGameStaysOnItsFileAndLeavesTheSessionAlone() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.claim(player);
        long generation = inspector.peek(player).generation();

        CompletableFuture<Loaded> second = a.service.preload(player, a.file(player));

        assertEquals(new Loaded(Mode.FILE_ONLY, null), second.getNow(null), "at once: it neither waits for the row nor takes it");
        assertNull(a.service.claim(player, second), "the session is not the second connection's to take");
        a.service.abandon(player, second, false);
        a.login(UUID.randomUUID()); // the reader thread has nothing left to do for the second connection
        assertTrue(a.service.takes(player), "the session in the game goes on");
        a.service.save(player, doc(42, 5), 4000);
        a.service.flush().join();
        assertDocument(doc(42, 5), payload(player), "and still writes: its lease was not taken a second time");
        assertEquals(generation, inspector.peek(player).generation());
    }

    @Test
    void handoffWaitsForReleaseThenLoadsWithoutTakeover() throws Exception {
        Server a = new Server("a");
        Server b = new Server("b", "handoff-wait-ms", 4000);
        a.login(player);

        CompletableFuture<Loaded> onB = b.service.preload(player, b.file(player));
        Thread.sleep(150);
        assertFalse(onB.isDone(), "B waits while A holds the row");
        a.quit(player, doc(42, 5));

        assertDocument(doc(42, 5), onB.get(5, TimeUnit.SECONDS).document());
        assertTrue(b.status().contains("forced takeovers: 0"), b.status());
    }

    @Test
    void handoffTakesOverAfterTimeoutAndTheLateFlushOfTheFormerOwnerGoesToItsFile() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        Server b = new Server("b");
        a.login(player);

        Loaded onB = b.login(player);
        a.quit(player, doc(42, 5));

        assertEquals(new Loaded(Mode.SQL, null), onB);
        assertTrue(b.status().contains("forced takeovers: 1"), b.status());
        assertNull(inspector.peek(player).payload(), "A's late write changed no row");
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertTrue(pending(a.file(player)), "A keeps what the database refused, marked for a later merge");
    }

    @Test
    void handoffFallsBackToFileOnlyWhenTakeoverIsDisabled() throws Exception {
        Server a = new Server("a");
        Server b = new Server("b", "handoff-takeover", false);
        a.login(player);
        long generation = inspector.peek(player).generation();

        assertEquals(new Loaded(Mode.FILE_ONLY, null), b.login(player));

        assertFalse(b.service.save(player, doc(1, 1), 4000), "a file-only session leaves the save to the file, as vanilla");
        assertNull(b.service.quit(player));
        assertEquals(generation, inspector.peek(player).generation(), "A still holds its lease");
    }

    @Test
    void sameJvmReloginAfterQuitFlushesBeforeReacquiring() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.writer.hold();

        a.service.save(player, doc(42, 5), 4000);
        CompletableFuture<Void> flushed = a.service.quit(player);
        a.writer.awaitHeld();
        CompletableFuture<Loaded> again = a.service.preload(player, a.file(player));
        Thread.sleep(150);
        assertFalse(again.isDone(), "the login waits for the quit of the same player to be stored");
        a.writer.release();

        assertDocument(doc(42, 5), again.get(5, TimeUnit.SECONDS).document());
        assertTrue(flushed.isDone());
    }

    @Test
    void aLoginRightAfterAQuitOnTheFileWaitsForThatFileAndKeepsItsLastSnapshot() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        Server b = new Server("b");
        a.login(player);
        b.login(player); // forced after 300 ms: A's lease is gone
        b.quit(player, doc(50, 5));
        CountDownLatch slowDisk = new CountDownLatch(1);
        a.files.submit(player, () -> await(slowDisk));
        a.service.save(player, doc(42, 9), 4000);
        a.service.flush().join(); // refused: the snapshot goes to A's file, behind the slow write
        a.service.quit(player);

        CompletableFuture<Loaded> back = a.service.preload(player, a.file(player));
        Thread.sleep(150);
        assertFalse(back.isDone(), "the row is not settled before the file of the session that just left is written and marked");
        slowDisk.countDown();

        Loaded loaded = back.get(5, TimeUnit.SECONDS);
        assertEquals(Mode.SQL, loaded.mode());
        assertDocument(doc(50, 9), loaded.document(), "what the session that left counted is merged in, not left to be overwritten");
        assertDocument(doc(50, 9), payload(player));
        assertFalse(pending(a.file(player)));
    }

    @Test
    void lateCompletionAfterGateTimeoutReleasesLeaseWithoutWriting() throws Exception {
        Server a = new Server("a");
        Files.writeString(a.file(player), doc(42, 5));
        a.reader.hold();

        CompletableFuture<Loaded> login = a.service.preload(player, a.file(player));
        a.reader.awaitHeld();
        login.complete(new Loaded(Mode.FILE_ONLY, null)); // what the gate does when it stops waiting
        a.reader.release();
        a.login(UUID.randomUUID()); // the reader thread is past the abandoned login

        assertNull(inspector.holder(player), "the lease taken for nobody is given back");
        assertNull(inspector.peek(player).payload(), "and nothing was written under it");
        assertFalse(a.service.save(player, doc(43, 5), 4000), "the session is file-only");
    }

    // ---- first load ----

    @Test
    void firstLoadSeedsFromJsonOnceThenTheJsonIsIgnored() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        Files.writeString(a.file(player), doc(42, 5));

        assertDocument(doc(42, 5), a.login(player).document());
        assertEquals("json-import", source(player));
        a.quit(player, doc(43, 6));
        Files.writeString(a.file(player), doc(99, 99)); // no marker: the database has the document

        assertDocument(doc(43, 6), a.login(player).document());
        assertEquals("runtime", source(player));
    }

    @Test
    void aCorruptLocalFileIsLeftOutAndTheSessionStillOpens() throws Exception {
        Server a = new Server("a");
        Files.writeString(a.file(player), "{ not json");

        assertEquals(new Loaded(Mode.SQL, null), a.login(player));
        assertNull(inspector.peek(player).payload());
    }

    // ---- batch and quit ----

    @Test
    void batchCoalescesLatestSnapshotPerUuid() throws Exception {
        Server a = new Server("a");
        a.login(player);
        long before = inspector.peek(player).revision();

        assertTrue(a.service.save(player, doc(1, 1), 4000));
        a.service.save(player, doc(2, 1), 4000);
        a.service.save(player, doc(3, 1), 4000);
        assertNull(inspector.peek(player).payload(), "nothing is written before the batch runs");
        a.service.flush().join();

        assertDocument(doc(3, 1), inspector.peek(player).payload());
        assertEquals(before + 1, inspector.peek(player).revision(), "three saves, one write");
        assertTrue(a.status().contains("queued snapshots: 0"), a.status());
    }

    @Test
    void aFullQueueIsWrittenWithoutWaitingForTheInterval() throws Exception {
        Server a = new Server("a", "batch-max-size", 2);
        UUID other = UUID.randomUUID();
        a.login(player);
        a.login(other);

        a.service.save(player, doc(1, 1), 4000);
        a.service.save(other, doc(2, 2), 4000);

        eventually(() -> payload(player) != null && payload(other) != null);
    }

    @Test
    void aFlushWritesWhatIsQueuedInTransactionsOfBatchMaxSizeAtMost() throws Exception {
        Server a = new Server("a", "batch-max-size", 2);
        List<UUID> players = a.loginMany(5);
        a.writer.hold();
        a.service.save(players.get(0), doc(1, 1), 4000);
        a.service.save(players.get(1), doc(1, 1), 4000); // the queue is full: a flush starts and waits for the database
        a.writer.awaitHeld();
        for (UUID late : players.subList(2, 5)) a.service.save(late, doc(1, 1), 4000);
        int before = a.writer.commits.get();
        a.writer.release();
        a.service.flush().join();

        for (UUID id : players) assertDocument(doc(1, 1), payload(id));
        assertEquals(before + 3, a.writer.commits.get(), "two waiting, then three more: 2, then 2 and 1, never three documents in one transaction");
    }

    @Test
    void savesPastTheBatchSizeAskForOneFlushAndAFlushStopsAtItsFirstFailure() throws Exception {
        Server a = new Server("a", "batch-max-size", 2, "failure-threshold", 5);
        List<UUID> players = a.loginMany(8);
        a.writer.hold();
        a.service.save(players.get(0), doc(1, 1), 4000);
        a.service.save(players.get(1), doc(1, 1), 4000);
        a.writer.awaitHeld(); // the first flush has its two documents and waits for the database
        for (UUID late : players.subList(2, 8)) a.service.save(late, doc(1, 1), 4000); // every one of these finds the queue full

        a.writer.down = true;
        a.writer.release();

        assertEquals(State.HEALTHY, a.service.probeNow().get(5, TimeUnit.SECONDS),
            "two failures, the flush that waited and the one flush asked for since: a flush per save, or per chunk, would have opened the breaker at 5");
    }

    @Test
    void quitFlushOvertakesBatchQueueAndReleasesLease() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.service.save(player, doc(1, 1), 4000);

        a.quit(player, doc(42, 5)); // returns within seconds: the batch interval is an hour

        assertDocument(doc(42, 5), inspector.peek(player).payload());
        assertNull(inspector.holder(player), "written and released in one statement");
        assertEquals(1, inspector.peek(player).revision(), "the snapshot still queued was dropped, not written after");
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertFalse(pending(a.file(player)), "the local copy of a stored document carries no marker");
    }

    @Test
    void aQuitWithNothingNewOnlyGivesTheLeaseBack() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.service.save(player, doc(42, 5), 4000);
        a.service.flush().join();

        a.service.quit(player).get(5, TimeUnit.SECONDS);

        assertNull(inspector.holder(player));
        assertEquals(1, inspector.peek(player).revision(), "no second write of the same document");
    }

    @Test
    void aBatchThatFailsAfterThePlayerLeftKeepsItsSnapshotInTheFile() throws Exception {
        Server a = new Server("a", "failure-threshold", 5);
        a.login(player);
        a.service.save(player, doc(42, 5), 4000);
        a.writer.hold();
        CompletableFuture<Void> batch = a.service.flush();
        a.writer.awaitHeld(); // the batch has taken the snapshot
        a.service.quit(player);
        a.writer.down = true;
        a.writer.release();
        batch.join();

        a.files.await(player);
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertTrue(pending(a.file(player)));
    }

    @Test
    void mirrorJsonOffLeavesNoLocalCopy() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        a.login(player);

        a.quit(player, doc(42, 5));

        assertFalse(Files.exists(a.file(player)));
    }

    @Test
    void aLeaseTakenElsewhereSendsLaterSavesToTheFileWithAMarker() throws Exception {
        Server a = new Server("a", "lease-seconds", 3, "lease-renew-seconds", 1);
        Server b = new Server("b");
        a.login(player);
        b.login(player); // forced after 300 ms

        eventually(() -> { // A learns it at its next renewal
            a.service.save(player, doc(42, 5), 4000);
            a.files.await(player);
            return pending(a.file(player));
        });

        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertNull(inspector.peek(player).payload());
    }

    // ---- outage, fallback, recovery ----

    @Test
    void failedBatchIsRequeuedAndCountsForBreaker() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.service.save(player, doc(42, 5), 4000);
        a.outage();

        a.service.flush().join();
        assertEquals(State.HEALTHY, a.service.state(), "one failure does not open the breaker");
        assertTrue(a.status().contains("queued snapshots: 1"), a.status());
        assertFalse(Files.exists(a.file(player)));

        a.service.flush().join();
        a.files.await(player);
        assertEquals(State.DEGRADED, a.service.state());
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertTrue(pending(a.file(player)));
        assertTrue(a.status().contains("pending markers: 1"), a.status());
        assertFalse(a.status().contains(SECRET) || a.status().contains("stats_user"), a.status());
    }

    @Test
    void degradedSavesAndLoginsStayOnTheJsonWithPendingMarker() throws Exception {
        Server a = new Server("a");
        UUID late = UUID.randomUUID();
        a.login(player);
        a.breakerOpens();

        assertTrue(a.service.save(player, doc(42, 5), 4000), "the layer still takes the save: it knows where it goes");
        a.files.await(player);
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertTrue(pending(a.file(player)));

        assertEquals(new Loaded(Mode.DEGRADED, null), a.login(late), "no wait on a database known to be down");
        a.quit(late, doc(1, 1));
        assertTrue(pending(a.file(late)));

        Throwable refused = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
            () -> a.service.migrate(a.stats, false).get(5, TimeUnit.SECONDS)).getCause();
        assertTrue(refused.getMessage().contains("DEGRADED"), "a migration does not start on a database known to be down");
    }

    @Test
    void pendingWithoutABaseIsMergedByMaxPerKeyThenMarkerRemoved() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.quit(player, doc(42, 5));
        Files.createFile(marker(a.file(player))); // as an older build left it: nothing says where the file started from
        a.breakerOpens();
        a.login(player); // on its file
        a.service.save(player, doc(40, 9), 4000);
        a.files.await(player);
        a.repaired();

        assertEquals(State.HEALTHY, a.service.probeNow().get(5, TimeUnit.SECONDS));

        assertDocument(doc(42, 9), inspector.peek(player).payload());
        assertFalse(pending(a.file(player)), "the marker goes once the merge is stored");
        a.service.save(player, doc(41, 10), 4000);
        a.service.flush().join();
        assertDocument(doc(42, 10), inspector.peek(player).payload(), "a later snapshot never undoes the merge");
    }

    @Test
    void interruptedRecoveryKeepsMarkers() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.breakerOpens();
        a.service.save(player, doc(42, 5), 4000);
        a.files.await(player);
        a.prober.down = false; // the probe answers, the writes still fail

        assertEquals(State.DEGRADED, a.service.probeNow().get(5, TimeUnit.SECONDS));

        assertTrue(pending(a.file(player)));
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
    }

    @Test
    void playOnTheFileDuringAnOutageIsAddedToWhatTheDatabaseHasAtTheNextLogin() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        a.login(player);
        a.quit(player, doc(500, 5));
        Files.writeString(a.file(player), doc(100, 1)); // this server's own old file: the database is far ahead of it
        a.breakerOpens();

        Loaded onFile = a.login(player);
        a.quit(player, doc(130, 1)); // 30 played on the file, left during the outage
        a.repaired();
        a.service.probeNow().get(5, TimeUnit.SECONDS);
        assertTrue(pending(a.file(player)), "nobody online to merge for");
        Loaded back = a.login(player);

        assertEquals(Mode.DEGRADED, onFile.mode());
        assertDocument(doc(100, 1), onFile.document(), "the session of the outage started from the file");
        assertDocument(doc(530, 5), back.document(), "500 stored and 30 played: the highest of 500 and 130 would drop them");
        assertDocument(doc(530, 5), payload(player));
        assertFalse(pending(a.file(player)));
    }

    @Test
    void aMergeReplayedAfterItWasStoredAddsNothingTwice() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        a.login(player);
        a.quit(player, doc(500, 5));
        Files.writeString(a.file(player), doc(130, 1)); // what a session that started from 100 left in its file
        StatsFiles.markPending(a.file(player), doc(100, 1));
        long beforeTheMerge = inspector.peek(player).revision();
        assertDocument(doc(530, 5), a.login(player).document());
        a.service.quit(player).get(5, TimeUnit.SECONDS);

        // The server died once the merge was stored, before its marker went: the marker still tells of that merge.
        StatsFiles.merging(a.file(player), doc(100, 1), beforeTheMerge, doc(130, 1));
        assertDocument(doc(530, 5), a.login(player).document(), "the file that was counted is not added again");
        a.service.quit(player).get(5, TimeUnit.SECONDS);

        StatsFiles.merging(a.file(player), doc(100, 1), beforeTheMerge, doc(130, 1));
        Files.writeString(a.file(player), doc(135, 1)); // and the player went on in its file after that merge
        assertDocument(doc(535, 5), a.login(player).document(), "only what the file gained since is added");
        assertFalse(pending(a.file(player)));
    }

    @Test
    void aRecoveryCutDuringTheMergeIsReplayedOnceAndThePlayerInTheGameGoesOn() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        a.login(player);
        a.quit(player, doc(500, 5));
        Files.writeString(a.file(player), doc(100, 1));
        a.breakerOpens();
        a.login(player);
        a.service.save(player, doc(130, 1), 4000);
        a.files.await(player);
        a.repaired();
        CountDownLatch fileQueue = new CountDownLatch(1);
        a.files.submit(player, () -> await(fileQueue));

        CompletableFuture<State> recovery = a.service.probeNow();
        eventually(() -> holder(player) != null); // the recovery holds the row and waits for the player's file
        a.writer.down = true;
        a.writer.cut();
        fileQueue.countDown();

        assertEquals(State.DEGRADED, recovery.get(5, TimeUnit.SECONDS));
        assertTrue(pending(a.file(player)), "the marker stays");
        assertDocument(doc(500, 5), payload(player));

        a.writer.down = false;
        assertEquals(State.HEALTHY, a.service.probeNow().get(5, TimeUnit.SECONDS));
        assertDocument(doc(530, 5), payload(player), "merged at the next probe, once");
        assertFalse(pending(a.file(player)));

        a.service.save(player, doc(135, 1), 4000); // the counter in the game still counts from its file
        a.service.flush().join();
        assertDocument(doc(535, 5), payload(player), "what is played after the merge is added to what was stored");
    }

    @Test
    void aLoginDuringAnOutageReadsTheFileBehindThePlayersQueuedWrites() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.breakerOpens();
        CountDownLatch slowDisk = new CountDownLatch(1);
        a.files.submit(player, () -> await(slowDisk));
        a.service.save(player, doc(42, 5), 4000); // its last save, queued behind the slow write
        a.service.quit(player);

        CompletableFuture<Loaded> back = a.service.preload(player, a.file(player));
        Thread.sleep(150);
        assertFalse(back.isDone(), "the file is not read before the player's last save is in it, and the caller never waits for it");
        slowDisk.countDown();

        Loaded loaded = back.get(5, TimeUnit.SECONDS);
        assertEquals(Mode.DEGRADED, loaded.mode());
        assertDocument(doc(42, 5), loaded.document());
    }

    @Test
    void leasesAreRenewedWhileABatchWaitsOnTheDatabase() throws Exception {
        Server a = new Server("a", "lease-seconds", 3, "lease-renew-seconds", 1);
        a.login(player);
        long until = lockUntil(player);
        a.service.save(player, doc(42, 5), 4000);
        a.writer.hold();
        a.service.flush();
        a.writer.awaitHeld(); // the writer thread is stuck in its batch
        StatsSqlStoreTest.CLOCK.set(5_000);

        eventually(() -> lockUntil(player) > until);
    }

    // ---- shutdown ----

    @Test
    void shutdownDrainsQueueAndReleasesLeases() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.service.save(player, doc(42, 5), 4000);

        assertEquals(0, a.service.shutdown());

        assertDocument(doc(42, 5), inspector.peek(player).payload());
        assertNull(inspector.holder(player));
    }

    @Test
    void shutdownTimeoutWritesJsonWithPending() throws Exception {
        Server a = new Server("a", "shutdown-flush-timeout-seconds", 1);
        a.login(player);
        a.service.save(player, doc(42, 5), 4000);
        a.writer.hold();

        assertEquals(1, a.service.shutdown());

        a.files.await(player);
        assertDocument(doc(42, 5), Files.readString(a.file(player)));
        assertTrue(pending(a.file(player)));
    }

    // ---- migration ----

    @Test
    void bulkMigrationImportsOnceCountsAndNeverTouchesTheFiles() throws Exception {
        Server a = new Server("a");
        UUID second = UUID.randomUUID();
        UUID known = UUID.randomUUID();
        a.login(known);
        a.quit(known, doc(43, 6));
        Files.writeString(a.file(player), doc(42, 5));
        Files.writeString(a.file(second), doc(1, 1));
        Files.writeString(a.file(known), doc(99, 1));
        Files.writeString(a.stats.resolve(UUID.randomUUID() + ".json"), "{ not json");
        String before = listing(a.stats);

        assertEquals(new Migration(2, 1, 1, 0), a.service.migrate(a.stats, false).get(5, TimeUnit.SECONDS));
        assertEquals(new Migration(0, 3, 1, 0), a.service.migrate(a.stats, false).get(5, TimeUnit.SECONDS), "a second run imports nothing");

        assertDocument(doc(42, 5), inspector.peek(player).payload());
        assertDocument(doc(43, 6), inspector.peek(known).payload(), "a document already there is never overwritten");
        assertEquals(before, listing(a.stats), "the files are only read");
    }

    @Test
    void mergeMigrationTakesTheMaximumAndLeavesAnOnlinePlayerAlone() throws Exception {
        Server a = new Server("a");
        UUID online = UUID.randomUUID();
        a.login(player);
        a.quit(player, doc(43, 6));
        Files.writeString(a.file(player), doc(99, 1));
        a.login(online);
        a.service.save(online, doc(5, 5), 4000);
        a.service.flush().join();
        Files.writeString(a.file(online), doc(99, 99));

        a.service.migrate(a.stats, true).get(5, TimeUnit.SECONDS);

        assertDocument(doc(99, 6), inspector.peek(player).payload());
        assertDocument(doc(5, 5), inspector.peek(online).payload(), "a row under a live lease is not written from outside");
    }

    @Test
    void migrationLeavesAFileThatCountsFromItsOwnBaseToThePlayersNextLogin() throws Exception {
        Server a = new Server("a");
        Files.writeString(a.file(player), doc(130, 1));
        StatsFiles.markPending(a.file(player), doc(100, 1));

        assertEquals(new Migration(0, 0, 0, 1), a.service.migrate(a.stats, true).get(5, TimeUnit.SECONDS));

        assertNull(payload(player), "neither imported nor merged: the login would add what it gained a second time");
    }

    @Test
    void aSecondMigrationIsRefusedWhileOneRunsAndAllowedOnceItIsOver() throws Exception {
        Server a = new Server("a");
        Files.createDirectories(a.stats);
        Files.writeString(a.file(UUID.randomUUID()), doc(42, 5));
        a.prober.hold();
        CompletableFuture<Migration> first = a.service.migrate(a.stats, false);
        a.prober.awaitHeld(); // the first one is at its first file, waiting for the database

        CompletableFuture<Migration> second = a.service.migrate(a.stats, false);
        assertTrue(second.isCompletedExceptionally(), "refused at once, without waiting for the first");
        Throwable refused = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, second::get).getCause();
        assertTrue(refused.getMessage().contains("already running"), refused.getMessage());

        a.prober.release();
        assertEquals(new Migration(1, 0, 0, 0), first.get(5, TimeUnit.SECONDS), "the one that runs is not disturbed");
        assertEquals(new Migration(0, 1, 0, 0), a.service.migrate(a.stats, false).get(5, TimeUnit.SECONDS), "and the next one runs");
    }

    // ---- what the server calls ----

    @Test
    void theCounterOfTheLoginClaimsTheSessionOnce() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.quit(player, doc(42, 5));
        a.login(player);

        assertDocument(doc(42, 5), a.claim(player).document());
        assertNull(a.claim(player), "a second counter for the same UUID is not the session's");
        assertNull(a.service.claim(UUID.randomUUID(), a.connections.get(player)));
    }

    @Test
    void aLoadedDocumentThatCannotBeReadLeavesTheCounterAsItWas() {
        Map<String, Integer> counter = new HashMap<>(Map.of("kills", 7));

        assertFalse(StatsSql.adopt(counter, () -> {
            throw new IllegalStateException("not a statistics document");
        }));
        assertFalse(StatsSql.adopt(counter, () -> null));
        assertEquals(Map.of("kills", 7), counter, "nothing was dropped for a document that could not be read");

        assertTrue(StatsSql.adopt(counter, () -> Map.of("jumps", 3)));
        assertEquals(Map.of("jumps", 3), counter, "a document that was read replaces the counter, it is not added to it");
    }

    @Test
    void aConnectionClosedBeforeThePlayerCameInGivesTheLeaseBack() throws Exception {
        Server a = new Server("a");
        a.login(player);
        a.abandon(player, false);
        eventually(() -> holder(player) == null);
        assertFalse(a.service.takes(player), "nobody claimed it: the session is over");

        a.login(player);
        a.claim(player);
        a.abandon(player, true);
        assertTrue(a.service.takes(player), "claimed and in the game: the session goes on");
        a.abandon(player, false);
        eventually(() -> holder(player) == null);
    }

    @Test
    void gateTimeoutFallsBackToFileAndSessionNeverWritesSql() throws Exception {
        Server a = new Server("a");
        StatsSql.open(a.service, 200, id -> false, a.stats);
        Files.writeString(a.file(player), doc(42, 5));
        a.reader.hold();

        long started = System.nanoTime();
        CompletableFuture<Loaded> load = StatsSql.preload(player, () -> a.file(player));
        a.reader.awaitHeld();
        assertFalse(StatsSql.ready(load, started, player), "the player waits while the read runs");
        Thread.sleep(250);
        assertTrue(StatsSql.ready(load, started, player), "past load-timeout-ms the player comes in");
        assertEquals(new Loaded(Mode.FILE_ONLY, null), load.join());
        a.reader.release();
        a.login(UUID.randomUUID()); // the reader thread is past the abandoned login

        assertNull(StatsSql.claim(player, load), "no session: the counter reads and writes its file");
        assertFalse(StatsSql.save(player, true, 4000, () -> {
            throw new AssertionError("never encoded for the layer");
        }));
        assertNull(holder(player));
        assertNull(payload(player));
    }

    @Test
    void theCountersSaveIsTakenOnlyForItsSessionAndOnlyWhenChanged() throws Exception {
        Server a = new Server("a");
        StatsSql.open(a.service, 5000, id -> false, a.stats);
        CompletableFuture<Loaded> load = StatsSql.preload(player, () -> a.file(player));
        load.get(5, TimeUnit.SECONDS);
        assertNotNull(StatsSql.claim(player, load));

        assertTrue(StatsSql.save(player, false, 4000, () -> {
            throw new AssertionError("an unchanged counter is not encoded");
        }));
        assertTrue(StatsSql.save(player, true, 4000, () -> doc(42, 5)));
        StatsSql.quit(player);
        eventually(() -> payload(player) != null && holder(player) == null);
        assertDocument(doc(42, 5), payload(player));
        assertFalse(StatsSql.save(player, true, 4000, () -> doc(43, 5)), "after the quit the file is the counter's again");

        a.files.await(player);
        StatsSql.writingFile(a.file(player), false);
        assertTrue(pending(a.file(player)), "a file written outside a session is marked for the next login");
    }

    @Test
    void aCounterThatWasNeverTheLayersMarksItsFileWithWhatItStartedFrom() throws Exception {
        Server a = new Server("a", "mirror-json", false);
        StatsSql.open(a.service, 5000, id -> false, a.stats);
        a.login(player);
        a.quit(player, doc(500, 5));
        Files.writeString(a.file(player), doc(100, 1)); // what a counter that came in past the gate, or of an offline player, read

        StatsSql.writingFile(a.file(player), true); // its first save
        Files.writeString(a.file(player), doc(130, 1));
        StatsSql.writingFile(a.file(player), true); // a later one changes nothing to where it started

        assertDocument(doc(530, 5), a.login(player).document(), "what it added to its file is added to what is stored");
    }

    @Test
    void theLocalCopyOfAQuitNeverOverwritesAFileMarkedByACounterOutsideTheLayer() throws Exception {
        Server a = new Server("a");
        StatsSql.open(a.service, 5000, id -> false, a.stats);
        a.login(player);
        a.quit(player, doc(100, 1)); // the local copy holds 100
        a.login(player);
        CountDownLatch slowDisk = new CountDownLatch(1);
        a.files.submit(player, () -> await(slowDisk));
        assertTrue(a.service.save(player, doc(500, 5), 4000));
        a.service.quit(player).get(5, TimeUnit.SECONDS); // 500 is stored; its local copy waits behind the slow write

        StatsSql.writingFile(a.file(player), true); // an offline increment: its counter read 100...
        Files.writeString(a.file(player), doc(101, 1)); // ...and writes 101
        slowDisk.countDown();
        a.files.await(player);

        assertDocument(doc(101, 1), Files.readString(a.file(player)), "the copy of 500 does not replace a file marked as counting from 100");
        assertDocument(doc(501, 5), a.login(player).document(), "one more than what is stored, not 500 + (500 - 100)");
    }

    @Test
    void aConnectionClosedWhileItsStatisticsLoadLeavesNothingBehind() throws Exception {
        Server a = new Server("a");
        StatsSql.open(a.service, 5000, id -> false, a.stats);
        a.reader.hold();
        CompletableFuture<Loaded> load = StatsSql.preload(player, () -> a.file(player));
        a.reader.awaitHeld();

        StatsSql.abandon(load, player);
        a.reader.release();
        a.login(UUID.randomUUID());

        assertNull(holder(player));
        assertFalse(a.service.takes(player));
    }

    @Test
    void aClosedLayerAnswersNotMineEverywhere() {
        StatsSql.stop();
        Path file = folder.resolve(player + ".json");

        assertNull(StatsSql.preload(player, () -> {
            throw new AssertionError("no file is looked for");
        }));
        assertTrue(StatsSql.ready(null, 0, player));
        assertNull(StatsSql.claim(player, new CompletableFuture<>()));
        assertFalse(StatsSql.save(player, true, 4000, () -> {
            throw new AssertionError("nothing is encoded");
        }));
        StatsSql.quit(player);
        StatsSql.abandon(null, player);
        StatsSql.writingFile(file, true);
        assertFalse(pending(file));
        assertNull(StatsSql.service());
    }

    // ---- threads ----

    @Test
    void theGameThreadOnlyHandsDocumentsOver() throws Exception {
        Server a = new Server("a");
        Thread game = Thread.currentThread();
        StatsSqlThreadGuard.install(thread -> thread == game, false);

        a.login(player);
        a.service.save(player, doc(1, 1), 4000);
        a.service.flush().join();
        a.quit(player, doc(42, 5));
        assertEquals(0, a.service.shutdown(), "stopping waits for the layer's thread, it does not do its work");
        StatsSqlThreadGuard.install(thread -> false, false);

        assertDocument(doc(42, 5), inspector.peek(player).payload());
    }

    // ---- helpers ----

    private StatsSqlStore store(StatsSqlStore.ConnectionSource source, UUID owner) {
        return new StatsSqlStore(new StatsSqlStatements("t_", "btc_now_ms()"), source, owner, 60_000, false);
    }

    private static String doc(int kills, int jumps) {
        return "{\"stats\":{\"minecraft:custom\":{\"minecraft:mob_kills\":" + kills + ",\"minecraft:jump\":" + jumps + "}},\"DataVersion\":4000}";
    }

    private static void assertDocument(String expected, String actual) {
        assertDocument(expected, actual, "document");
    }

    private static void assertDocument(String expected, String actual, String what) {
        assertNotNull(actual, "no document: " + what);
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual), what);
    }

    private static boolean pending(Path file) {
        return Files.exists(marker(file));
    }

    private static Path marker(Path file) {
        return file.resolveSibling(file.getFileName() + ".pending");
    }

    private long lockUntil(UUID id) {
        try (Statement statement = keepAlive.createStatement();
             ResultSet row = statement.executeQuery("SELECT lock_until FROM t_player_stats WHERE player_uuid = '" + id + "'")) {
            assertTrue(row.next());
            return row.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String payload(UUID id) {
        try {
            StatsSqlStore.Row row = inspector.peek(id);
            return row == null ? null : row.payload();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UUID holder(UUID id) {
        try {
            return inspector.holder(id);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String source(UUID id) throws SQLException {
        try (Statement statement = keepAlive.createStatement();
             ResultSet row = statement.executeQuery("SELECT source FROM t_player_stats WHERE player_uuid = '" + id + "'")) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static String listing(Path folder) throws IOException {
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(folder)) {
            for (Path file : files.sorted().toList()) {
                all.append(file.getFileName()).append('=').append(Files.readString(file)).append('\n');
            }
        }
        return all.toString();
    }

    private static void eventually(BooleanSupplier reached) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!reached.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "not reached within 8 s");
            Thread.sleep(50);
        }
    }
}
