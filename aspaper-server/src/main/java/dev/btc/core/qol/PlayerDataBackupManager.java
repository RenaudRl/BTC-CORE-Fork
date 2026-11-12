package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.playerdata.PlayerDataIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BTCCore: periodic copy of the online players' {@code .dat} files ({@code qol.player-data-backup}),
 * one folder per player, the oldest copies pruned after each new one.
 *
 * <p>The tick only decides when and for whom; every file is read, written and deleted on
 * {@link PlayerDataIo}, queued behind that player's own pending writes.
 */
public final class PlayerDataBackupManager {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** What {@link #FORMATTER} names a copy: the only files the pruning may delete. */
    private static final Pattern COPY_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.dat");
    private static final Path BACKUP_ROOT = Path.of("backups", "playerdata");
    private static long lastBackupTick = 0; // global tick thread only

    private PlayerDataBackupManager() {}

    /**
     * Called each tick, on the global tick thread, to check if a backup is due.
     *
     * @param currentTick The current server tick
     */
    public static void onTick(long currentTick) {
        if (!BTCCoreConfig.playerDataBackupEnabled) {
            return;
        }
        if (currentTick - lastBackupTick < BTCCoreConfig.playerDataBackupIntervalTicks) {
            return;
        }
        lastBackupTick = currentTick;

        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        Path levelRoot = MinecraftServer.getServer().getWorldPath(LevelResource.ROOT);
        backup(playerDataDir(levelRoot), BACKUP_ROOT, LocalDateTime.now().format(FORMATTER),
            BTCCoreConfig.playerDataBackupKeepLast, online, PlayerDataIo.shared());
    }

    /** Where the server keeps {@code <uuid>.dat} under a level folder. */
    static Path playerDataDir(Path levelRoot) {
        return levelRoot.resolve(LevelResource.PLAYER_DATA_DIR.id()); // "players/data" since 26.x, not "playerdata"
    }

    /**
     * Queues one copy per player, to {@code <backupRoot>/<uuid>/<stamp>.dat}, and returns at once.
     * Each copy is followed, on the same worker, by the pruning of that player's older copies.
     *
     * @param stamp    the name of this round's copies, in {@link #FORMATTER}'s form
     * @param keepLast copies kept for each player; 0 keeps them all
     * @return the number of files copied, once every copy has run
     */
    static CompletableFuture<Integer> backup(Path playerDataDir, Path backupRoot, String stamp, int keepLast,
                                             Collection<UUID> players, PlayerDataIo io) {
        AtomicInteger copied = new AtomicInteger();
        CompletableFuture<?>[] copies = players.stream().map(id -> io.submit(id, () -> {
            try {
                Path playerBackups = backupRoot.resolve(id.toString());
                if (copy(playerDataDir, playerBackups, stamp, id)) {
                    copied.incrementAndGet();
                    prune(playerBackups, keepLast);
                }
            } catch (IOException failure) {
                LOGGER.warn("[BTCCore] Failed to back up player data of {}: {}", id, failure.toString());
            }
        })).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(copies).thenApply(done -> {
            if (copied.get() > 0) {
                LOGGER.info("[BTCCore] Player data backup completed: {} file(s) under {}", copied.get(), backupRoot);
            }
            return copied.get();
        });
    }

    /** @return false when the player has no file to copy; the player's backup folder is only created for a file */
    private static boolean copy(Path playerDataDir, Path playerBackups, String stamp, UUID id) throws IOException {
        byte[] data;
        try {
            // Read, not Files.copy: a plain read never holds the file against the server renaming it.
            data = Files.readAllBytes(playerDataDir.resolve(id + ".dat"));
        } catch (NoSuchFileException absent) {
            return false;
        }
        Files.createDirectories(playerBackups);
        Files.write(playerBackups.resolve(stamp + ".dat"), data);
        return true;
    }

    /**
     * Deletes the oldest copies of one player beyond {@code keepLast}. Only regular files of that
     * player's folder whose name this manager produced are counted or deleted: anything else an
     * operator put there stays.
     */
    private static void prune(Path playerBackups, int keepLast) throws IOException {
        if (keepLast <= 0) {
            return; // keep them all
        }
        List<Path> copies;
        try (Stream<Path> files = Files.list(playerBackups)) {
            copies = files
                .filter(file -> COPY_NAME.matcher(file.getFileName().toString()).matches()
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(file -> file.getFileName().toString())) // the stamp sorts by date
                .toList();
        }
        for (Path oldest : copies.subList(0, Math.max(0, copies.size() - keepLast))) {
            Files.delete(oldest);
        }
    }
}
