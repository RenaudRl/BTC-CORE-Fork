package dev.btc.core.stats.sql;

import dev.btc.core.playerdata.PlayerDataIo;
import dev.btc.core.stats.sql.StatsSqlStore.Row;
import dev.btc.core.stats.sql.StatsSqlStore.Write;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
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
 * <p>Threads: every document write runs on the one writer thread, which is what keeps a player's
 * writes in order; logins are read on the reader thread, the local file included; the probe thread
 * also renews the leases, so that a batch stuck on the database never lets one run out. None of
 * them is a game thread, and the game threads only ever hand a document over ({@link #save},
 * {@link #quit}) or look at a future ({@link #preload}).
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

    /**
     * What a login got. {@code document} is what the counter starts from, the stored document or,
     * for a session on its file, that file read behind the player's queued writes; null when the
     * counter keeps what it read itself, as vanilla does.
     */
    public record Loaded(Mode mode, @Nullable String document) {}

    /** What a bulk migration did. {@code pending}: files left to their player's next login, see {@link #migrate}. */
    public record Migration(int imported, int present, int unreadable, int pending) {}

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final String JSON = ".json";

    private static final class Session {
        final Path file;
        /** The connection this session is for. Weak: it holds the loaded document, wanted only until the counter took it. */
        final WeakReference<CompletableFuture<Loaded>> login;
        /** FILE_ONLY until the load is over: nothing is taken for a session that is not open yet. */
        volatile Mode mode = Mode.FILE_ONLY;
        volatile long generation;
        /** The connection got this session's answer, not the gate's. */
        volatile boolean entered;
        /** After a merge: what later snapshots of this session may not go below. */
        volatile @Nullable String floor;
        /** After the merge of a session that started from its file: how its later snapshots become the database's. */
        volatile @Nullable Rebase rebase;
        final AtomicBoolean claimed = new AtomicBoolean();

        Session(Path file, CompletableFuture<Loaded> login) {
            this.file = file;
            this.login = new WeakReference<>(login);
        }

        boolean isOf(CompletableFuture<Loaded> connection) {
            return entered && login.refersTo(connection);
        }
    }

    /** A counter that was at {@code counted} when the database took {@code stored}: what it gains since is added to {@code stored}. */
    private record Rebase(String stored, String counted) {}

    /**
     * What bringing a row and its file together gave.
     *
     * @param document what the row holds now; null when nothing was ever stored
     * @param counted  the state of the file's own count that {@code document} includes; null when the file continued the row
     */
    private record Settled(@Nullable String document, @Nullable String counted) {}

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
    /** A flush asked for by a full queue waits on the writer thread: the saves that follow do not each add one behind a slow database. */
    private final AtomicBoolean flushAsked = new AtomicBoolean();
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

    /** Creates the schema on the writer thread, then starts the batch timer there, the renewal and probe timers on the probe thread. */
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
        probeThread.scheduleWithFixedDelay(repeating(this::renew), config.leaseRenewSeconds(), config.leaseRenewSeconds(), TimeUnit.SECONDS);
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
     * <p>A player has one session here. While it lives, a second connection of the same player
     * gets {@link Mode#FILE_ONLY} at once, with no session and no lease: taking the row again
     * would fence the writes of the session in the game.
     *
     * @param file the player's local {@code stats/<uuid>.json}
     */
    public CompletableFuture<Loaded> preload(UUID player, Path file) {
        statsFolder = file.getParent();
        CompletableFuture<Loaded> result = new CompletableFuture<>();
        Session session = new Session(file, result);
        if (stopped.get() || sessions.putIfAbsent(player, session) != null) {
            result.complete(new Loaded(Mode.FILE_ONLY, null));
            return result;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.handoffWaitMs());
        // A quit of the same player on this server is flushed first: its lease is ours to take again only then.
        quitting.getOrDefault(player, CompletableFuture.completedFuture(null))
            .whenComplete((flushed, failure) -> readerThread.execute(() -> load(player, session, result, deadline)));
        return result;
    }

    /**
     * The counter built for the player who just came through the login takes the session, once: a
     * counter made for another connection of the same UUID, or later (a fake player), is not this
     * session's.
     *
     * @param login what {@link #preload} returned to that connection
     * @return what the login loaded; null when that connection has no session, or it is already taken
     */
    public @Nullable Loaded claim(UUID player, CompletableFuture<Loaded> login) {
        Session session = sessions.get(player);
        if (session == null || !session.isOf(login) || !session.claimed.compareAndSet(false, true)) {
            return null;
        }
        return login.getNow(null);
    }

    /** @return true when a {@link #save} of this player is the layer's to take */
    public boolean takes(UUID player) {
        Session session = sessions.get(player);
        return session != null && session.mode != Mode.FILE_ONLY && !stopped.get();
    }

    /**
     * The connection closed before the player was in the game. Its session, when nobody claimed
     * it or its player never got in, ends here: no quit will ever come for it. The session of
     * another connection of that player is left alone.
     *
     * @param login  what {@link #preload} returned to that connection
     * @param online whether a player with this UUID is in the game right now
     */
    public void abandon(UUID player, CompletableFuture<Loaded> login, boolean online) {
        Session session = sessions.get(player);
        if (session != null && session.isOf(login) && !(session.claimed.get() && online) && sessions.remove(player, session)) {
            end(player, session);
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
        if (queued.size() >= config.batchMaxSize() && flushAsked.compareAndSet(false, true)) {
            writerThread.execute(() -> {
                flushAsked.set(false);
                flushBatch();
            });
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
        return session == null ? null : end(player, session);
    }

    private @Nullable CompletableFuture<Void> end(UUID player, Session session) {
        Snapshot last = queued.remove(player);
        if (session.mode == Mode.FILE_ONLY) {
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
     * <p>A file whose marker carries a base is left out: only what it gained is the player's, and
     * that is added once, at the player's next login here. Imported or merged now, it would be
     * counted again then.
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
            int pending = 0;
            List<Path> jsons;
            try (Stream<Path> list = Files.list(folder)) {
                jsons = list.filter(file -> file.getFileName().toString().endsWith(JSON)).sorted().toList();
            } catch (IOException failure) {
                throw new IllegalStateException("cannot list " + folder, failure);
            }
            for (Path file : jsons) {
                String name = file.getFileName().toString();
                if (stopped.get()) {
                    throw new IllegalStateException("migration stopped, the server is stopping");
                }
                try {
                    UUID player = UUID.fromString(name.substring(0, name.length() - JSON.length()));
                    StatsFiles.Pending marker = StatsFiles.pending(file);
                    if (marker != null && marker.base() != null) {
                        pending++;
                        continue;
                    }
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
            LOGGER.info("[BTCCore] stats-sql migrate: {} imported, {} already present, {} unreadable, {} left to their player's next login",
                imported, present, unreadable, pending);
            return new Migration(imported, present, unreadable, pending);
        }, StatsSqlService::migrationThread); // neither the logins, the batches nor the lease renewals wait behind a long import
    }

    private static void migrationThread(Runnable migration) {
        Thread thread = new Thread(migration, "btccore-stats-sql-migrate");
        thread.setDaemon(true);
        thread.start();
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

    private void load(UUID player, Session session, CompletableFuture<Loaded> login, long deadline) {
        if (login.isDone()) {
            sessions.remove(player, session); // the gate stopped waiting: no lease is taken for nobody
            return;
        }
        State now = state;
        if (now != State.HEALTHY) {
            onFile(player, session, login, now == State.UNCONFIGURED ? Mode.FILE_ONLY : Mode.DEGRADED);
            return;
        }
        Row row = null;
        try {
            row = reader.acquire(player, false);
            if (row == null) {
                if (System.nanoTime() < deadline) {
                    readerThread.schedule(() -> load(player, session, login, deadline), config.handoffPollMs(), TimeUnit.MILLISECONDS);
                    return;
                }
                if (!config.handoffTakeover()) {
                    LOGGER.warn("[BTCCore] stats-sql: statistics of {} are still held elsewhere, this session stays on its file.", player);
                    onFile(player, session, login, Mode.FILE_ONLY);
                    return;
                }
                UUID former = reader.holder(player);
                row = reader.acquire(player, true);
                takeovers.incrementAndGet();
                LOGGER.warn("[BTCCore] stats-sql: took the statistics of {} over from {} after {} ms.", player, former, config.handoffWaitMs());
            }
            succeeded();
            session.generation = row.generation();
            // The gate may have let the player in on its file meanwhile: nothing is written then.
            Settled settled = login.isDone() ? null : settle(player, session, row, reader, false);
            if (settled != null && offer(player, session, login, Mode.SQL, settled.document())) {
                return;
            }
            reader.release(player, row.generation()); // nobody to hold it for, or the file could not be merged: the lease goes back
            onFile(player, session, login, Mode.DEGRADED);
        } catch (SQLException down) {
            failed(down);
            onFile(player, session, login, Mode.DEGRADED);
        } catch (RuntimeException failure) {
            LOGGER.error("[BTCCore] stats-sql: loading {} failed, this session stays on its file", player, failure);
            onFile(player, session, login, Mode.FILE_ONLY);
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
     * Opens the session on its local file. The file is read here, behind every write of that player
     * still queued: a player back within the same outage starts from what its last session saved,
     * not from the file as it was before. What it held is kept as the base of the marker, so that
     * only what this session adds is merged into the database later.
     */
    private void onFile(UUID player, Session session, CompletableFuture<Loaded> login, Mode mode) {
        if (login.isDone()) {
            sessions.remove(player, session);
            return;
        }
        files.await(player);
        String local = null;
        try {
            local = StatsFiles.readDocument(session.file);
            StatsFiles.markPending(session.file, local == null ? StatsMerge.EMPTY : local);
        } catch (IOException failure) {
            LOGGER.warn("[BTCCore] stats-sql: local statistics file of {} could not be read or marked ({})", player, failure.getClass().getSimpleName());
        }
        offer(player, session, login, mode, local);
    }

    /**
     * Opens the session for the connection that still waits for it.
     *
     * @return false when the gate had already let the player in on its file, or the session was ended meanwhile
     */
    private boolean offer(UUID player, Session session, CompletableFuture<Loaded> login, Mode mode, @Nullable String document) {
        session.mode = mode;
        if (sessions.get(player) == session && login.complete(new Loaded(mode, document))) {
            session.entered = true;
            return true;
        }
        sessions.remove(player, session);
        login.complete(new Loaded(Mode.FILE_ONLY, null));
        return false;
    }

    /**
     * Brings the row and the local file together under the lease just taken: a row without a
     * document is seeded from the file; a marked file is merged in, by what it gained over the base
     * of its marker, or by maximum when the marker has none.
     *
     * <p>A merge by gain is not safe to replay, so the marker is told of it before the row is
     * written: a replay after a crash, or after a marker that could not be removed, measures the
     * gain from the file as it was counted.
     *
     * @param online the player's counter is in the game: it goes on from where it is, and the session is moved onto what is stored
     * @return null when the session must stay on its file: the lease was lost, or the marker could not be kept right
     */
    private @Nullable Settled settle(UUID player, Session session, Row row, StatsSqlStore store, boolean online) throws SQLException {
        String stored = row.payload();
        // First: a session that just left on its file may still have its last write queued, and that
        // write is what sets the marker looked for below.
        files.await(player);
        if (stored != null && !StatsFiles.hasPending(session.file)) {
            Settled untouched = new Settled(stored, null); // the database has it and the file has nothing newer: the file plays no part
            if (online) move(session, untouched);
            return untouched;
        }
        try {
            StatsFiles.Pending pending = StatsFiles.pending(session.file);
            String base = pending == null ? null : pending.baseAt(row.revision());
            String local = StatsFiles.readDocument(session.file);
            String merged = local == null ? stored : base == null ? StatsMerge.max(stored, local) : StatsMerge.delta(stored, local, base);
            if (merged != null && !merged.equals(stored)) {
                if (base != null) {
                    StatsFiles.merging(session.file, base, row.revision(), local);
                }
                String source = stored == null ? StatsSqlStore.SOURCE_IMPORT : StatsSqlStore.SOURCE_MERGE;
                if (!store.write(new Write(player, session.generation, merged, StatsMerge.dataVersion(merged), source, false))) {
                    if (base != null) {
                        StatsFiles.notMerged(session.file, base);
                    }
                    return null; // the lease went elsewhere meanwhile: the file and its marker stay
                }
            }
            Settled settled = new Settled(merged, base == null ? null : local == null ? base : local);
            // In the player's file queue: no save of this session is written to the file between the marker
            // going and the session moving, where the file and its marker would not tell the same story.
            AtomicBoolean unmarked = new AtomicBoolean();
            files.submit(player, () -> {
                try {
                    StatsFiles.clearPending(session.file); // only now: the merge is stored
                    if (online) move(session, settled);
                    unmarked.set(true);
                } catch (IOException kept) {
                    LOGGER.warn("[BTCCore] stats-sql: pending marker of {} could not be removed ({}), this session stays on its file.", player, kept.getClass().getSimpleName());
                }
            }).join();
            return unmarked.get() ? settled : null;
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("[BTCCore] stats-sql: local statistics of {} could not be merged ({}), this session stays on its file.", player, failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The counter in the game goes on from where it was, not from what was just stored: a session
     * that started from its file is moved onto the stored document, any other is only kept above it.
     */
    private static void move(Session session, Settled settled) {
        // shortcut: a session moved once is only kept above a later merge by gain; compose the two moves if a
        // session ever has to be merged by gain twice.
        if (settled.document() != null && settled.counted() != null && session.rebase == null
            && !settled.counted().equals(settled.document())) {
            session.rebase = new Rebase(settled.document(), settled.counted());
        }
        session.floor = settled.document();
    }

    // ---- writer thread ----

    /** Stores what is queued, {@code batch-max-size} documents to a transaction at most: a long queue never holds one long transaction. */
    private void flushBatch() {
        List<Snapshot> batch = new ArrayList<>();
        for (UUID player : queued.keySet()) {
            Snapshot snapshot = queued.remove(player);
            if (snapshot == null) continue;
            if (snapshot.session.mode != Mode.SQL || state != State.HEALTHY) {
                toFile(snapshot);
                continue;
            }
            inFlight.putIfAbsent(player, snapshot); // a quit already waiting there is the newer one
            batch.add(snapshot);
            if (batch.size() >= config.batchMaxSize()) {
                if (!writeBatch(batch)) return; // the database refused: the rest stays queued and counts as one failure, not one per transaction
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) writeBatch(batch);
    }

    /** @return false when the database could not be written to */
    private boolean writeBatch(List<Snapshot> batch) {
        try {
            List<Write> writes = batch.stream().map(snapshot -> write(snapshot, false)).toList();
            List<Write> fenced = writer.writeBatch(writes);
            succeeded();
            for (Snapshot snapshot : batch) {
                inFlight.remove(snapshot.player, snapshot);
                if (fenced.stream().anyMatch(write -> write.player().equals(snapshot.player))) fenced(snapshot);
            }
            return true;
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
            return false;
        } catch (RuntimeException failure) {
            LOGGER.error("[BTCCore] stats-sql: batch failed", failure);
            batch.forEach(snapshot -> {
                toFile(snapshot);
                inFlight.remove(snapshot.player, snapshot);
            });
            return true; // these documents are on their files; the database itself did not refuse
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
                        StatsFiles.writeCopy(snapshot.session.file, document); // not over a file marked meanwhile: its marker counts from what it held
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

    /** On the probe thread, with its own connection: a batch that waits on the database holds no renewal back. */
    private void renew() {
        Map<UUID, Long> held = new HashMap<>();
        sessions.forEach((player, session) -> {
            if (session.mode == Mode.SQL) held.put(player, session.generation);
        });
        if (state != State.HEALTHY || held.isEmpty()) return;
        try {
            for (UUID lost : prober.renew(held)) {
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
                if (settle(online.getKey(), session, row, writer, true) == null) {
                    writer.release(online.getKey(), row.generation()); // stays on its file, merged at its next login
                    continue;
                }
                session.mode = Mode.SQL;
                merged++;
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

    /**
     * The snapshot as the database must see it: what the counter gained since a merge by gain added
     * to what that merge stored, and never below what any merge stored for this session.
     */
    private static String document(Snapshot snapshot) {
        Rebase rebase = snapshot.session.rebase;
        String document = rebase == null ? snapshot.document : StatsMerge.delta(rebase.stored, snapshot.document, rebase.counted);
        String floor = snapshot.session.floor;
        return floor == null ? document : StatsMerge.max(floor, document);
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
