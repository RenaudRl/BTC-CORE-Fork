package dev.btc.core.stats.sql;

import dev.btc.core.playerdata.PlayerDataIo;
import dev.btc.core.stats.sql.StatsSqlService.Loaded;
import dev.btc.core.stats.sql.StatsSqlService.Mode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Entry point of the SQL statistics layer ({@code stats-sql} in {@code btccore.yml}), and the only
 * thing the server code calls.
 *
 * <p>Decides, once at startup, whether the layer may open at all. It stays closed, and the
 * statistics stay on their {@code .json} files exactly as before, when it is disabled, when
 * {@code zero-features.stats} is on, or when its configuration is unusable. Closed, every method
 * here answers "not mine" without doing anything.
 */
public final class StatsSql {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");

    private record Running(StatsSqlService service, long loadTimeoutNanos, Predicate<UUID> online, Path folder) {}

    private static volatile @Nullable Running running;

    private StatsSql() {}

    /**
     * @param root             the whole {@code btccore.yml}
     * @param zeroFeatureStats the global {@code zero-features.stats}, the one the counter itself reads
     * @return the checked configuration, or null when the layer stays closed; the environment is
     *         only read when the layer is enabled and not cut by zero-features
     */
    public static @Nullable StatsSqlConfig configure(ConfigurationSection root, UnaryOperator<String> environment,
                                                     boolean zeroFeatureStats) {
        ConfigurationSection section = root.getConfigurationSection(StatsSqlConfig.SECTION);
        if (section == null || !section.getBoolean("enabled", false)) {
            return null;
        }
        if (zeroFeatureStats) {
            LOGGER.info("[BTCCore] stats-sql is ignored: zero-features.stats turns statistics off.");
            return null;
        }
        try {
            return StatsSqlConfig.read(section, environment);
        } catch (IllegalArgumentException problem) {
            // The message names a key and, for an unset reference, a variable: never a value.
            LOGGER.error("[BTCCore] stats-sql stays closed, statistics stay on files: {}", problem.getMessage());
            return null;
        }
    }

    /**
     * Opens the layer for the life of the server. The database is first reached on the layer's own
     * thread: until it answers, players come in on their files.
     *
     * @param config what {@link #configure} returned; null leaves the layer closed
     * @param online whether a player with that UUID is in the game
     * @param folder the {@code stats} folder of the server
     */
    public static void start(@Nullable StatsSqlConfig config, Predicate<UUID> online, Path folder) {
        if (config == null) {
            return;
        }
        StatsSqlThreadGuard.install(thread -> thread instanceof ca.spottedleaf.moonrise.common.util.TickThread, true);
        StatsSqlService service = StatsSqlService.postgres(config, PlayerDataIo.shared());
        open(service, config.loadTimeoutMs(), online, folder);
        service.start();
        LOGGER.info("[BTCCore] stats-sql enabled: vanilla statistics are kept in PostgreSQL ({}player_stats).", config.tablePrefix());
    }

    static void open(StatsSqlService service, long loadTimeoutMs, Predicate<UUID> online, Path folder) {
        running = new Running(service, TimeUnit.MILLISECONDS.toNanos(loadTimeoutMs), online, folder);
    }

    /** After the players were saved and removed: what is queued is stored, the leases are given back. */
    public static void stop() {
        Running was = running;
        running = null;
        if (was != null) {
            was.service.shutdown();
        }
    }

    /** @return the running service, for the admin command; null when the layer is closed */
    public static @Nullable StatsSqlService service() {
        Running now = running;
        return now == null ? null : now.service;
    }

    /** @return the server's {@code stats} folder; null when the layer is closed */
    public static @Nullable Path folder() {
        Running now = running;
        return now == null ? null : now.folder;
    }

    // ---- login gate ----

    /**
     * Starts reading a player's statistics while its spawn is prepared.
     *
     * @param file the player's local file; only asked for when the layer is open
     * @return null when the layer is closed: there is nothing to wait for
     */
    public static @Nullable CompletableFuture<Loaded> preload(UUID player, Supplier<Path> file) {
        Running now = running;
        return now == null ? null : now.service.preload(player, file.get());
    }

    /**
     * Asked every tick while the spawn is prepared; never waits.
     *
     * @return true when the player may come in: its statistics are loaded, or {@code load-timeout-ms}
     *         has passed and it comes in on its local file, a session that never writes the database
     */
    public static boolean ready(@Nullable CompletableFuture<Loaded> load, long startedNanos, UUID player) {
        Running now = running;
        if (load == null || load.isDone() || now == null) {
            return true;
        }
        if (System.nanoTime() - startedNanos < now.loadTimeoutNanos) {
            return false;
        }
        if (load.complete(new Loaded(Mode.FILE_ONLY, null))) {
            LOGGER.warn("[BTCCore] stats-sql: statistics of {} were not loaded in time, this session stays on its local file.", player);
        }
        return true;
    }

    /** The connection closed while the spawn was being prepared, or before the player was in the game. */
    public static void abandon(@Nullable CompletableFuture<Loaded> load, UUID player) {
        Running now = running;
        if (load == null || now == null) {
            return;
        }
        load.complete(new Loaded(Mode.FILE_ONLY, null)); // a lease taken after this is given straight back
        now.service.abandon(player, now.online.test(player));
    }

    // ---- the player's counter ----

    /**
     * Called once by the counter built for a player.
     *
     * @return what the login loaded for it; null when this counter is not a session of the layer
     */
    public static @Nullable Loaded claim(UUID player) {
        Running now = running;
        return now == null ? null : now.service.claim(player);
    }

    /**
     * @param changed  whether a statistic changed since the last save the layer took
     * @param document encodes the counter; only called when the layer takes the save
     * @return false when the counter must write its file itself, as vanilla does
     */
    public static boolean save(UUID player, boolean changed, int dataVersion, Supplier<String> document) {
        Running now = running;
        if (now == null || !now.service.takes(player)) {
            return false;
        }
        return !changed || now.service.save(player, document.get(), dataVersion);
    }

    /** After the player's last save: its snapshot is stored ahead of the batch, its lease given back. */
    public static void quit(UUID player) {
        Running now = running;
        if (now != null) {
            now.service.quit(player);
        }
    }

    /**
     * A counter is about to write its file itself while the layer is open (a session on its local
     * file, an offline player): that file will hold play the database has not seen, and is marked
     * first, to be merged in at the player's next login here.
     */
    public static void writingFile(Path file) {
        if (running == null) {
            return;
        }
        try {
            StatsFiles.markPending(file);
        } catch (IOException failure) {
            LOGGER.warn("[BTCCore] stats-sql: could not mark {} as pending ({})", file.getFileName(), failure.toString());
        }
    }
}
