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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BTCCore: periodic copy of the online players' {@code .dat} files ({@code qol.player-data-backup}).
 *
 * <p>The tick only decides when and for whom; every file is read and written on {@link PlayerDataIo},
 * queued behind that player's own pending writes.
 */
public final class PlayerDataBackupManager {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
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
        Path backupDir = BACKUP_ROOT.resolve(LocalDateTime.now().format(FORMATTER));
        backup(playerDataDir(levelRoot), backupDir, online, PlayerDataIo.shared());
    }

    /** Where the server keeps {@code <uuid>.dat} under a level folder. */
    static Path playerDataDir(Path levelRoot) {
        return levelRoot.resolve(LevelResource.PLAYER_DATA_DIR.id()); // "players/data" since 26.x, not "playerdata"
    }

    /**
     * Queues one copy per player and returns at once.
     *
     * @return the number of files copied, once every copy has run
     */
    static CompletableFuture<Integer> backup(Path playerDataDir, Path backupDir, Collection<UUID> players, PlayerDataIo io) {
        AtomicInteger copied = new AtomicInteger();
        CompletableFuture<?>[] copies = players.stream().map(id -> io.submit(id, () -> {
            try {
                if (copy(playerDataDir, backupDir, id)) {
                    copied.incrementAndGet();
                }
            } catch (IOException failure) {
                LOGGER.warn("[BTCCore] Failed to back up player data of {}: {}", id, failure.toString());
            }
        })).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(copies).thenApply(done -> {
            if (copied.get() > 0) {
                LOGGER.info("[BTCCore] Player data backup completed: {} file(s) in {}", copied.get(), backupDir);
            }
            return copied.get();
        });
    }

    /** @return false when the player has no file to copy; the backup folder is only created for a file */
    private static boolean copy(Path playerDataDir, Path backupDir, UUID id) throws IOException {
        byte[] data;
        try {
            // Read, not Files.copy: a plain read never holds the file against the server renaming it.
            data = Files.readAllBytes(playerDataDir.resolve(id + ".dat"));
        } catch (NoSuchFileException absent) {
            return false;
        }
        Files.createDirectories(backupDir);
        Files.write(backupDir.resolve(id + ".dat"), data);
        return true;
    }
}
