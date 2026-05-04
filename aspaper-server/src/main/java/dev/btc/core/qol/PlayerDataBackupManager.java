package dev.btc.core.qol;

import dev.btc.core.config.BTCCoreConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * BTCCore: Player Data Backup Manager.
 * Handles automatic backups of player data.
 */
public final class PlayerDataBackupManager {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static long lastBackupTick = 0;

    private PlayerDataBackupManager() {}

    /**
     * Called each tick to check if backup should run.
     *
     * @param currentTick The current server tick
     */
    public static void onTick(long currentTick) {
        if (!BTCCoreConfig.playerDataBackupEnabled) {
            return;
        }

        if (currentTick - lastBackupTick >= BTCCoreConfig.playerDataBackupIntervalTicks) {
            lastBackupTick = currentTick;
            runBackup();
        }
    }

    /**
     * Runs a backup of all online player data.
     */
    public static void runBackup() {
        if (!BTCCoreConfig.playerDataBackupEnabled) {
            return;
        }

        String timestamp = LocalDateTime.now().format(FORMATTER);
        File backupDir = new File("backups/playerdata/" + timestamp);

        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        File playerDataDir = new File(Bukkit.getWorldContainer(), 
                Bukkit.getWorlds().get(0).getName() + "/playerdata");

        if (!playerDataDir.exists()) {
            return;
        }

        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                File playerFile = new File(playerDataDir, player.getUniqueId() + ".dat");
                if (playerFile.exists()) {
                    Path source = playerFile.toPath();
                    Path target = new File(backupDir, player.getUniqueId() + ".dat").toPath();
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Bukkit.getLogger().info("[BTCCore] Player data backup completed: " + backupDir.getPath());
        } catch (IOException e) {
            Bukkit.getLogger().warning("[BTCCore] Failed to backup player data: " + e.getMessage());
        }
    }

    /**
     * Backs up a specific player's data.
     *
     * @param player The player to backup
     */
    public static void backupPlayer(Player player) {
        if (!BTCCoreConfig.playerDataBackupEnabled) {
            return;
        }

        String timestamp = LocalDateTime.now().format(FORMATTER);
        File backupDir = new File("backups/playerdata/manual");

        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        File playerDataDir = new File(Bukkit.getWorldContainer(),
                player.getWorld().getName() + "/playerdata");
        File playerFile = new File(playerDataDir, player.getUniqueId() + ".dat");

        if (!playerFile.exists()) {
            return;
        }

        try {
            Path source = playerFile.toPath();
            Path target = new File(backupDir, player.getUniqueId() + "_" + timestamp + ".dat").toPath();
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Bukkit.getLogger().warning("[BTCCore] Failed to backup player " + player.getName() + ": " + e.getMessage());
        }
    }
}

