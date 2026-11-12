package dev.btc.core.stats.sql;

import dev.btc.core.playerdata.PlayerDataIo;
import dev.btc.core.stats.sql.StatsSqlStore.Row;
import dev.btc.core.stats.sql.StatsSqlStore.Write;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The life of the statistics documents of one server: loaded under a lease at login, written in
 * batches while the player is online, flushed and released at quit, kept on the local
 * {@code .json} files whenever the database cannot take them.
 *
 * <p>Threads: every database write runs on the one writer thread, which is what keeps a player's
 * writes in order; logins are read on the reader thread; the probe has its own. None of them is a
 * game thread, and the game threads only ever hand a document over ({@link #save}, {@link #quit})
 * or look at a future ({@link #preload}).
 */
public final class StatsSqlService {

    public enum State { UNCONFIGURED, HEALTHY, DEGRADED, RECOVERING }

    /** How one player's session stores its statistics. */
    public enum Mode {
        /** Under a lease, written to the database. */
        SQL,
        /** On the local file only, as vanilla does; never written to the database. */
        FILE_ONLY,
        /** On the local file with a pending marker, until the database is back. */
        DEGRADED
    }

    /** What a login got. {@code document} is null when the counter must read its file as vanilla does. */
    public record Loaded(Mode mode, @Nullable String document) {}

    /** What a bulk migration did. */
    public record Migration(int imported, int present, int unreadable) {}

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final String JSON = ".json";

    private static final class Session {
        final Path file;
        volatile Mode mode;
        volatile long generation;
        /** After a merge: what later snapshots of this session may not go below. */
        volatile @Nullable String floor;
        /** What the login loaded, until the player's counter takes it. */
        volatile @Nullable String document;
        final AtomicBoolean claimed = new AtomicBoolean();

        Session(Path file, Mode mode, long generation) {
            this.file = file;
            this.mode = mode;
            this.generation = generation;
        }
    }

    private record Snapshot(UUID player, Session session, String document, int dataVersion, long queuedAtNanos) {}

    private final StatsSqlConfig config;
    private final StatsSqlStore reader;
    private final StatsSqlStore writer;
    private final StatsSqlStore prober;
    private final PlayerDataIo files;
    private final ScheduledExecutorService readerThread = thread("btccore-stats-sql-reader");
    private final ScheduledExecutorService writerThread = thread("btccore-stats-sql-writer");
    private final ScheduledExecutorService probeThread = thread("btccore-stats-sql-probe");
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Snapshot> queued = new ConcurrentHashMap<>();
    /** Handed to the writer thread and not stored yet: what a shutdown that times out still has to save. */
    private final ConcurrentHashMap<UUID, Snapshot> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> quitting = new ConcurrentHashMap<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger takeovers = new AtomicInteger();
    /** Once stopped, every player is the file's again: a late save is written as vanilla does, not queued for nobody. */
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile State state = State.UNCONFIGURED;
    private volatile String lastFailure = "none";
    private volatile @Nullable Path statsFolder;

    /**
     * @param reader each store has its own connection: a slow login never holds a write back
     * @param files  where the local files are written, in order for each player
     */
    public StatsSqlService(StatsSqlConfig config, StatsSqlStore reader, StatsSqlStore writer, StatsSqlStore prober,
                           PlayerDataIo files) {
        this.config = config;
        this.reader = reader;
        this.writer = writer;
        this.prober = prober;
        this.files = files;
    }

    /** The service of a real server: three PostgreSQL connections under one owner id, drawn for this run. */
    public static StatsSqlService postgres(StatsSqlConfig config, PlayerDataIo files) {
        StatsSqlStatements sql = new StatsSqlStatements(config.tablePrefix(), StatsSqlStatements.POSTGRES_NOW_MS);
        UUID owner = UUID.randomUUID();
        StatsSqlStore.ConnectionSource source = StatsSqlStore.postgres(config);
        return new StatsSqlService(config,
            new StatsSqlStore(sql, source, owner, config.leaseMs(), true),
            new StatsSqlStore(sql, source, owner, config.leaseMs(), true),
            new StatsSqlStore(sql, source, owner, config.leaseMs(), true), files);
    }

    /** Creates the schema on the writer thread, then starts the batch, renewal and probe timers. */
    public CompletableFuture<State> start() {
        CompletableFuture<State> opened = CompletableFuture.supplyAsync(() -> {
            try {
                writer.ensureSchema();
                state = State.HEALTHY;
                LOGGER.info("[BTCCore] stats-sql is open.");
            } catch (StatsSqlStore.NewerSchemaException newer) {
                LOGGER.error("[BTCCore] stats-sql stays closed, statistics stay on files: {}", newer.getMessage());
            } catch (SQLException down) {
                lastFailure = describe(down);
                state = State.DEGRADED;
                LOGGER.warn("[BTCCore] stats-sql: database unreachable at startup ({}), statistics stay on files until it answers.", lastFailure);
            }
            return state;
        }, writerThread);
        writerThread.scheduleWithFixedDelay(repeating(this::flushBatch), config.batchIntervalMs(), config.batchIntervalMs(), TimeUnit.MILLISECONDS);
        writerThread.scheduleWithFixedDelay(repeating(this::renew), config.leaseRenewSeconds(), config.leaseRenewSeconds(), TimeUnit.SECONDS);
        probeThread.scheduleWithFixedDelay(repeating(this::probe), config.probeIntervalSeconds(), config.probeIntervalSeconds(), TimeUnit.SECONDS);
        return opened;
    }

    /** A repeating task that throws is never run again by its executor: a lease would silently stop being renewed. */
    private static Runnable repeating(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                LOGGER.error("[BTCCore] stats-sql: a repeating task failed and will run again", failure);
            }
        };
    }

    public State state() {
        return state;
    }

    /**
     * Starts loading a player at login. The future always completes; the login gate may complete it
     * itself with {@link Mode#FILE_ONLY} when it stops waiting, and a lease taken after that is
     * given back at once, nothing written.
     *
     * @param file the player's local {@code stats/<uuid>.json}
     */
    public CompletableFuture<Loaded> preload(UUID player, Path file) {
        statsFolder = file.getParent();
        CompletableFuture<Loaded> result = new CompletableFuture<>();
        State now = state;
        if (now != State.HEALTHY || stopped.get()) {
            Mode mode = now == State.UNCONFIGURED || stopped.get() ? Mode.FILE_ONLY : Mode.DEGRADED;
            sessions.put(player, new Session(file, mode, 0));
            result.complete(new Loaded(mode, null));
            return result;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.handoffWaitMs());
        // A quit of the same player on this server is flushed first: its lease is ours to take again only then.
        quitting.getOrDefault(player, CompletableFuture.completedFuture(null))
            .whenComplete((flushed, failure) -> readerThread.execute(() -> load(player, file, deadline, result)));
        return result;
    }

    /**
     * The counter built for the player who just came through the login takes the session, once: a
     * second counter made later for the same UUID (a fake player) is not this player's.
     *
     * @return what the login loaded; null when there is no session, or it is already taken
     */
    public @Nullable Loaded claim(UUID player) {
        Session session = sessions.get(player);
        if (session == null || !session.claimed.compareAndSet(false, true)) {
            return null;
        }
        String document = session.document;
        session.document = null;
        return new Loaded(session.mode, document);
    }

    /** @return true when a {@link #save} of this player is the layer's to take */
    public boolean takes(UUID player) {
        Session session = sessions.get(player);
        return session != null && session.mode != Mode.FILE_ONLY && !stopped.get();
    }

    /**
     * The connection closed before the player was in the game. A session nobody claimed, or whose
     * player never got in, ends here: no quit will ever come for it.
     *
     * @param online whether a player with this UUID is in the game right now
     */
    public void abandon(UUID player, boolean online) {
        Session session = sessions.get(player);
        if (session != null && !(session.claimed.get() && online)) {
            quit(player);
        }
    }

    /**
     * Takes a snapshot of an online player.
     *
     * @return false when this player's statistics are not the layer's: the caller writes the file itself
     */
    public boolean save(UUID player, String document, int dataVersion) {
        Session session = sessions.get(player);
        if (session == null || session.mode == Mode.FILE_ONLY || stopped.get()) {
            return false;
        }
        Snapshot snapshot = new Snapshot(player, session, document, dataVersion, System.nanoTime());
        if (session.mode == Mode.DEGRADED || state != State.HEALTHY) {
            toFile(snapshot);
            return true;
        }
        queued.put(player, snapshot); // the latest replaces the one still waiting
        if (queued.size() >= config.batchMaxSize()) {
            writerThread.execute(this::flushBatch);
        }
        return true;
    }

    /**
     * A player leaves, after its last {@link #save}: the snapshot still queued is stored and the
     * lease given back in one statement, ahead of the batch; with nothing queued, only the lease
     * goes back.
     *
     * @return completes once that is done; null when this player's statistics are not the layer's
     */
    public @Nullable CompletableFuture<Void> quit(UUID player) {
        Session session = sessions.remove(player);
        Snapshot last = queued.remove(player);
        if (session == null || session.mode == Mode.FILE_ONLY) {
            return null;
        }
        if (session.mode == Mode.DEGRADED || state != State.HEALTHY || stopped.get()) {
            return last == null ? CompletableFuture.completedFuture(null) : toFile(last);
        }
        if (last != null) {
            inFlight.put(player, last);
        }
        CompletableFuture<Void> flushed = CompletableFuture.runAsync(() -> flushQuit(player, session, last), writerThread);
        quitting.put(player, flushed);
        flushed.whenComplete((done, failure) -> quitting.remove(player, flushed));
        return flushed;
    }

    /** Runs a batch now, on the writer thread. */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(this::flushBatch, writerThread);
    }

    /** Runs a probe now, on the probe thread; a successful one starts the recovery. */
    public CompletableFuture<State> probeNow() {
        return CompletableFuture.runAsync(this::probe, probeThread)
            .thenCompose(probed -> CompletableFuture.supplyAsync(() -> state, writerThread));
    }

    /**
     * Imports every {@code <uuid>.json} of a folder that the database has no document for. The
     * files are only read. Running it again imports nothing.
     *
     * @param merge also merge, by maximum, the files of players who already have a document
     */
    public CompletableFuture<Migration> migrate(Path folder, boolean merge) {
        return CompletableFuture.supplyAsync(() -> {
            if (state != State.HEALTHY) { // a schema this build does not know is never written either
                throw new IllegalStateException("the database is not available (state " + state + ")");
            }
            int imported = 0;
            int present = 0;
            int unreadable = 0;
            List<Path> jsons;
            try (Stream<Path> list = Files.list(folder)) {
                jsons = list.filter(file -> file.getFileName().toString().endsWith(JSON)).sorted().toList();
            } catch (IOException failure) {
                throw new IllegalStateException("cannot list " + folder, failure);
            }
            for (Path file : jsons) {
                String name = file.getFileName().toString();
                try {
                    UUID player = UUID.fromString(name.substring(0, name.length() - JSON.length()));
                    String document = StatsFiles.read(file);
                    int dataVersion = StatsMerge.dataVersion(document);
                    if (prober.importIfAbsent(player, document, dataVersion)) {
                        imported++;
                    } else {
                        present++;
                        if (merge) mergeOffline(player, document);
                    }
                } catch (SQLException down) {
                    failed(down);
                    throw new IllegalStateException("migration stopped, database error: " + describe(down));
                } catch (IOException | RuntimeException bad) {
                    unreadable++;
                    LOGGER.warn("[BTCCore] stats-sql migrate: {} skipped ({})", name, bad.getClass().getSimpleName());
                }
            }
            LOGGER.info("[BTCCore] stats-sql migrate: {} imported, {} already present, {} unreadable", imported, present, unreadable);
            return new Migration(imported, present, unreadable);
        }, probeThread); // neither the logins nor the batches wait behind a long import
    }

    /** One line per fact, for {@code /btccore stats-sql status}; never a host, user or password. */
    public List<String> status() {
        long oldest = Stream.concat(queued.values().stream(), inFlight.values().stream())
            .mapToLong(Snapshot::queuedAtNanos).min().orElse(0);
        return List.of(
            "state: " + state,
            "sessions: " + sessions.size(),
            "queued snapshots: " + (queued.size() + inFlight.size()),
            "oldest snapshot: " + (oldest == 0 ? "none" : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest) + " ms"),
            "pending markers: " + pendingMarkers(),
            "last failure: " + lastFailure,
            "forced takeovers: " + takeovers.get());
    }

    /**
     * Stores what is queued, gives every lease back, closes the connections. Past
     * {@code shutdown-flush-timeout-seconds}, what is left goes to the local files with a marker.
     *
     * @return the number of documents that had to go to files
     */
    public int shutdown() {
        if (stopped.getAndSet(true)) {
            return 0;
        }
        probeThread.shutdownNow();
        CompletableFuture<Void> drained = CompletableFuture.runAsync(() -> {
            flushBatch();
            releaseAll();
        }, writerThread);
        int toFiles = 0;
        try {
            drained.get(config.shutdownFlushTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException late) {
            // Falls through: whatever is still in memory is written below.
        }
        writerThread.shutdownNow();
        readerThread.shutdownNow();
        Map<UUID, Snapshot> left = new HashMap<>(inFlight);
        left.putAll(queued);
        for (Snapshot snapshot : left.values()) {
            toFile(snapshot).join(); // behind any file write of that player already queued
            toFiles++;
        }
        if (toFiles > 0) {
            LOGGER.warn("[BTCCore] stats-sql: {} document(s) not stored in time at shutdown, kept in their .json with a pending marker.", toFiles);
        }
        reader.close();
        writer.close();
        prober.close();
        return toFiles;
    }

    // ---- reader thread ----

    private void load(UUID player, Path file, long deadline, CompletableFuture<Loaded> result) {
        if (result.isDone()) {
            return; // the gate stopped waiting: no lease is taken for nobody
        }
        Row row = null;
        try {
            row = reader.acquire(player, false);
            if (row == null) {
                if (System.nanoTime() < deadline) {
                    readerThread.schedule(() -> load(player, file, deadline, result), config.handoffPollMs(), TimeUnit.MILLISECONDS);
                    return;
                }
                if (!config.handoffTakeover()) {
                    LOGGER.warn("[BTCCore] stats-sql: statistics of {} are still held elsewhere, this session stays on its file.", player);
                    open(player, new Session(file, Mode.FILE_ONLY, 0), null, result);
                    return;
                }
                UUID former = reader.holder(player);
                row = reader.acquire(player, true);
                takeovers.incrementAndGet();
                LOGGER.warn("[BTCCore] stats-sql: took the statistics of {} over from {} after {} ms.", player, former, config.handoffWaitMs());
            }
            succeeded();
            Session session = new Session(file, Mode.SQL, row.generation());
            // The gate may have let the player in on its file meanwhile: nothing is written then, the lease is given back.
            boolean entered = !result.isDone() && open(player, session, settle(player, session, row, reader), result);
            if (!entered && session.mode == Mode.SQL) {
                reader.release(player, row.generation());
            }
        } catch (SQLException down) {
            failed(down);
            open(player, new Session(file, Mode.DEGRADED, 0), null, result);
        } catch (RuntimeException failure) {
            LOGGER.error("[BTCCore] stats-sql: loading {} failed, this session stays on its file", player, failure);
            open(player, new Session(file, Mode.FILE_ONLY, 0), null, result);
            if (row != null) {
                try {
                    reader.release(player, row.generation());
                } catch (SQLException expires) {
                    lastFailure = describe(expires); // the lease runs out by itself
                }
            }
        }
    }

    /**
     * Registers the session only when the login still waits for it.
     *
     * @return false when the gate had already let the player in on its file
     */
    private boolean open(UUID player, Session session, @Nullable String document, CompletableFuture<Loaded> result) {
        session.document = session.mode == Mode.SQL ? document : null;
        sessions.put(player, session);
        if (result.complete(new Loaded(session.mode, session.mode == Mode.SQL ? document : null))) {
            return true;
        }
        sessions.remove(player, session);
        return false;
    }

    /**
     * Brings the row and the local file together under the lease just taken: a row without a
     * document is seeded from the file, a file with a pending marker is merged in by maximum.
     *
     * @return the document the session starts from; the session turns FILE_ONLY when the lease was lost meanwhile
     */
    private @Nullable String settle(UUID player, Session session, Row row, StatsSqlStore store) throws SQLException {
        String stored = row.payload();
        boolean pending = StatsFiles.hasPending(session.file);
        if (stored != null && !pending) {
            return stored; // the database has it and the file has nothing newer: the file plays no part
        }
        files.await(player);
        String merged = stored;
        try {
            String local = StatsFiles.read(session.file);
            if (local != null) {
                StatsMerge.dataVersion(local); // a file that is not a statistics document is left out whole
                merged = StatsMerge.max(stored, local);
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            LOGGER.warn("[BTCCore] stats-sql: local statistics file of {} is unreadable and was left out ({})", player, unreadable.getClass().getSimpleName());
        }
        if (merged != null && !merged.equals(stored)) {
            String source = stored == null ? StatsSqlStore.SOURCE_IMPORT : StatsSqlStore.SOURCE_MERGE;
            if (!store.write(new Write(player, session.generation, merged, StatsMerge.dataVersion(merged), source, false))) {
                session.mode = Mode.DEGRADED; // the lease went elsewhere meanwhile: the file and its marker stay
                return null;
            }
        }
        if (pending) {
            try {
                StatsFiles.clearPending(session.file); // only now: the merge is stored
            } catch (IOException kept) {
                LOGGER.warn("[BTCCore] stats-sql: pending marker of {} could not be removed, it will be merged again", player);
            }
        }
        return merged;
    }

    // ---- writer thread ----

    private void flushBatch() {
        List<Snapshot> batch = new ArrayList<>();
        for (UUID player : queued.keySet()) {
            Snapshot snapshot = queued.remove(player);
            if (snapshot == null) continue;
            if (snapshot.session.mode != Mode.SQL || state != State.HEALTHY) {
                toFile(snapshot);
            } else {
                inFlight.putIfAbsent(player, snapshot); // a quit already waiting there is the newer one
                batch.add(snapshot);
            }
        }
        if (batch.isEmpty()) return;
        try {
            List<Write> writes = batch.stream().map(snapshot -> write(snapshot, false)).toList();
            List<Write> fenced = writer.writeBatch(writes);
            succeeded();
            for (Snapshot snapshot : batch) {
                inFlight.remove(snapshot.player, snapshot);
                if (fenced.stream().anyMatch(write -> write.player().equals(snapshot.player))) fenced(snapshot);
            }
        } catch (SQLException down) {
            failed(down);
            // Kept somewhere before it leaves inFlight: a shutdown that stops waiting looks at both.
            for (Snapshot snapshot : batch) {
                if (state == State.HEALTHY && sessions.get(snapshot.player) == snapshot.session) {
                    queued.putIfAbsent(snapshot.player, snapshot); // a newer snapshot queued meanwhile wins
                } else {
                    toFile(snapshot); // nobody left to try again for: the file keeps it, marked
                }
                inFlight.remove(snapshot.player, snapshot);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[BTCCore] stats-sql: batch failed", failure);
            batch.forEach(snapshot -> {
                toFile(snapshot);
                inFlight.remove(snapshot.player, snapshot);
            });
        }
    }

    private void flushQuit(UUID player, Session session, @Nullable Snapshot snapshot) {
        try {
            if (snapshot == null) {
                writer.release(player, session.generation); // nothing new since the last write
                succeeded();
                return;
            }
            boolean stored = writer.write(write(snapshot, true));
            succeeded();
            if (!stored) {
                fenced(snapshot);
            } else if (config.mirrorJson()) {
                String document = document(snapshot);
                files.submit(snapshot.player, () -> {
                    try {
                        StatsFiles.write(snapshot.session.file, document);
                    } catch (IOException failure) {
                        LOGGER.warn("[BTCCore] stats-sql: local copy of {} not written ({})", snapshot.player, failure.toString());
                    }
                });
            }
        } catch (SQLException down) {
            failed(down);
            if (snapshot != null) toFile(snapshot);
        } finally {
            if (snapshot != null) inFlight.remove(player, snapshot);
        }
    }

    private void renew() {
        Map<UUID, Long> held = new HashMap<>();
        sessions.forEach((player, session) -> {
            if (session.mode == Mode.SQL) held.put(player, session.generation);
        });
        if (state != State.HEALTHY || held.isEmpty()) return;
        try {
            for (UUID lost : writer.renew(held)) {
                Session session = sessions.get(lost);
                if (session != null && session.generation == held.get(lost)) {
                    session.mode = Mode.DEGRADED;
                    LOGGER.warn("[BTCCore] stats-sql: the lease of {} was taken elsewhere, this session goes on in its .json with a pending marker.", lost);
                }
            }
            succeeded();
        } catch (SQLException down) {
            failed(down);
        }
    }

    private void releaseAll() {
        sessions.forEach((player, session) -> {
            if (session.mode != Mode.SQL) return;
            try {
                writer.release(player, session.generation);
            } catch (SQLException down) {
                lastFailure = describe(down); // the lease runs out by itself
            }
        });
        sessions.clear();
    }

    /** After a successful probe: online players take a lease again and their files are merged in. */
    private void recover() {
        int merged = 0;
        try {
            writer.ensureSchema();
            for (Map.Entry<UUID, Session> online : sessions.entrySet()) {
                Session session = online.getValue();
                if (session.mode != Mode.DEGRADED) continue;
                Row row = writer.acquire(online.getKey(), false);
                if (row == null) continue; // held elsewhere: stays on its file, merged at its next login
                session.generation = row.generation();
                session.mode = Mode.SQL;
                String document = settle(online.getKey(), session, row, writer);
                session.floor = document;
                if (session.mode == Mode.SQL) merged++;
            }
            failures.set(0);
            state = State.HEALTHY;
            LOGGER.info("[BTCCore] stats-sql: database is back, {} online player(s) merged; offline players are merged at their next login.", merged);
        } catch (StatsSqlStore.NewerSchemaException newer) {
            state = State.UNCONFIGURED;
            LOGGER.error("[BTCCore] stats-sql stays closed, statistics stay on files: {}", newer.getMessage());
        } catch (SQLException down) {
            lastFailure = describe(down);
            sessions.values().forEach(session -> {
                if (session.mode == Mode.SQL) session.mode = Mode.DEGRADED;
            });
            state = State.DEGRADED; // markers are still there: the next probe starts over
        }
    }

    private void mergeOffline(UUID player, String document) throws SQLException {
        for (int attempt = 0; attempt < 2; attempt++) { // a row that moved under us is read and replayed once
            Row row = prober.peek(player);
            String merged = StatsMerge.max(row == null ? null : row.payload(), document);
            if (row == null || merged.equals(row.payload())
                || prober.writeOffline(player, row.revision(), merged, StatsMerge.dataVersion(merged), StatsSqlStore.SOURCE_MERGE)) {
                return;
            }
        }
        LOGGER.warn("[BTCCore] stats-sql migrate: {} is in use, its file was not merged.", player);
    }

    // ---- probe thread ----

    private void probe() {
        if (state != State.DEGRADED) return;
        try {
            prober.probe();
        } catch (SQLException stillDown) {
            lastFailure = describe(stillDown);
            return;
        }
        state = State.RECOVERING;
        writerThread.execute(this::recover);
    }

    // ---- any thread ----

    private Write write(Snapshot snapshot, boolean release) {
        return new Write(snapshot.player, snapshot.session.generation, document(snapshot), snapshot.dataVersion,
            StatsSqlStore.SOURCE_RUNTIME, release);
    }

    /** The snapshot, never below what a merge already stored for this session. */
    private static String document(Snapshot snapshot) {
        String floor = snapshot.session.floor;
        return floor == null ? snapshot.document : StatsMerge.max(floor, snapshot.document);
    }

    private void fenced(Snapshot snapshot) {
        snapshot.session.mode = Mode.DEGRADED;
        LOGGER.warn("[BTCCore] stats-sql: write for {} refused, its lease was taken elsewhere; kept in its .json with a pending marker.", snapshot.player);
        toFile(snapshot);
    }

    private CompletableFuture<Void> toFile(Snapshot snapshot) {
        return files.submit(snapshot.player, () -> {
            try {
                StatsFiles.writePending(snapshot.session.file, document(snapshot));
            } catch (IOException failure) {
                LOGGER.error("[BTCCore] stats-sql: statistics of {} could not be written to their file", snapshot.player, failure);
            }
        });
    }

    private void succeeded() {
        failures.set(0);
    }

    private void failed(SQLException failure) {
        lastFailure = describe(failure);
        if (failures.incrementAndGet() >= config.failureThreshold() && state == State.HEALTHY) {
            state = State.DEGRADED;
            sessions.values().forEach(session -> {
                if (session.mode == Mode.SQL) session.mode = Mode.DEGRADED;
            });
            LOGGER.warn("[BTCCore] stats-sql: database unavailable ({}), statistics go to their .json files until it answers.", lastFailure);
        }
    }

    /** The kind of error only: a driver message can carry a host or a user name. */
    private static String describe(SQLException failure) {
        return failure.getClass().getSimpleName() + (failure.getSQLState() == null ? "" : " " + failure.getSQLState());
    }

    private long pendingMarkers() {
        Path folder = statsFolder;
        if (folder == null) return 0;
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(StatsFiles::isMarker).count();
        } catch (IOException unreadable) {
            return -1;
        }
    }

    private static ScheduledExecutorService thread(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
