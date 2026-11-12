package dev.btc.core.qol;

import dev.btc.core.playerdata.PlayerDataIo;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerDataBackupManagerTest {

    private static final byte[] DAT = {1, 2, 3, 4};

    private final PlayerDataIo io = new PlayerDataIo();
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void stop() {
        io.shutdown(5, TimeUnit.SECONDS);
    }

    /** The folder the server itself saves players into, taken from its own constant. */
    private Path savedByTheServer(Path levelRoot) throws IOException {
        Path dir = Files.createDirectories(levelRoot.resolve(LevelResource.PLAYER_DATA_DIR.id()));
        Files.write(dir.resolve(player + ".dat"), DAT);
        return dir;
    }

    @Test
    @DisplayName("a player saved by the server is found under the level folder and copied")
    void copiesFromWhereTheServerSaves(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        savedByTheServer(levelRoot);
        Path backupDir = backups.resolve("round");

        int copied = PlayerDataBackupManager
            .backup(PlayerDataBackupManager.playerDataDir(levelRoot), backupDir, List.of(player), io)
            .get(5, TimeUnit.SECONDS);

        assertEquals(1, copied);
        assertArrayEquals(DAT, Files.readAllBytes(backupDir.resolve(player + ".dat")));
    }

    @Test
    @DisplayName("no backup folder is created when no player has a file")
    void leavesNoEmptyFolder(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path backupDir = backups.resolve("round");

        int copied = PlayerDataBackupManager
            .backup(PlayerDataBackupManager.playerDataDir(levelRoot), backupDir, List.of(player), io)
            .get(5, TimeUnit.SECONDS);

        assertEquals(0, copied);
        assertFalse(Files.exists(backupDir));
    }

    @Test
    @DisplayName("the copy never runs on the caller: it waits behind the player's pending file work")
    void copyIsQueuedBehindThePlayersOwnWork(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path playerDataDir = savedByTheServer(levelRoot);
        Path backupDir = backups.resolve("round");
        CountDownLatch pendingWrite = new CountDownLatch(1);
        io.submit(player, () -> await(pendingWrite));

        CompletableFuture<Integer> round = PlayerDataBackupManager.backup(playerDataDir, backupDir, List.of(player), io);

        assertFalse(Files.exists(backupDir), "copied on the calling thread, or ahead of the pending write");
        pendingWrite.countDown();
        assertEquals(1, round.get(5, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
