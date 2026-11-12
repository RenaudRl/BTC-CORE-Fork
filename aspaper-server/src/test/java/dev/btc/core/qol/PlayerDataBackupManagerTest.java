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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataBackupManagerTest {

    private static final byte[] DAT = {1, 2, 3, 4};
    private static final String FIRST = "2026-11-12_10-00-00";
    private static final String SECOND = "2026-11-12_10-05-00";
    private static final String THIRD = "2026-11-12_10-10-00";
    private static final int KEEP_ALL = 0;

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

    private int round(Path playerDataDir, Path backups, String stamp, int keepLast) throws Exception {
        return PlayerDataBackupManager.backup(playerDataDir, backups, stamp, keepLast, List.of(player), io)
            .get(5, TimeUnit.SECONDS);
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("a player saved by the server is found under the level folder and copied to its own folder")
    void copiesFromWhereTheServerSaves(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        savedByTheServer(levelRoot);

        int copied = round(PlayerDataBackupManager.playerDataDir(levelRoot), backups, FIRST, KEEP_ALL);

        assertEquals(1, copied);
        assertArrayEquals(DAT, Files.readAllBytes(backups.resolve(player.toString()).resolve(FIRST + ".dat")));
    }

    @Test
    @DisplayName("no backup folder is created when no player has a file")
    void leavesNoEmptyFolder(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        int copied = round(PlayerDataBackupManager.playerDataDir(levelRoot), backups, FIRST, KEEP_ALL);

        assertEquals(0, copied);
        assertFalse(Files.exists(backups.resolve(player.toString())));
    }

    @Test
    @DisplayName("the copy never runs on the caller: it waits behind the player's pending file work")
    void copyIsQueuedBehindThePlayersOwnWork(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path playerDataDir = savedByTheServer(levelRoot);
        CountDownLatch pendingWrite = new CountDownLatch(1);
        io.submit(player, () -> await(pendingWrite));

        CompletableFuture<Integer> round = PlayerDataBackupManager.backup(playerDataDir, backups, FIRST, KEEP_ALL, List.of(player), io);

        assertFalse(Files.exists(backups.resolve(player.toString())), "copied on the calling thread, or ahead of the pending write");
        pendingWrite.countDown();
        assertEquals(1, round.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("past keep-last, the oldest copies of the player are deleted after the new one is written")
    void keepsOnlyTheLastCopiesOfAPlayer(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path playerDataDir = savedByTheServer(levelRoot);

        round(playerDataDir, backups, FIRST, 2);
        round(playerDataDir, backups, SECOND, 2);
        round(playerDataDir, backups, THIRD, 2);

        assertEquals(List.of(SECOND + ".dat", THIRD + ".dat"), names(backups.resolve(player.toString())));
    }

    @Test
    @DisplayName("pruning only deletes copies it named, in that player's folder: anything else stays")
    void neverDeletesWhatItDidNotName(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path playerDataDir = savedByTheServer(levelRoot);
        Path mine = Files.createDirectories(backups.resolve(player.toString()));
        Files.write(mine.resolve("keep-me.dat"), DAT);
        Files.write(mine.resolve("2026-11-12_09-00-00.dat.bak"), DAT);
        Files.write(Files.createDirectories(mine.resolve("2020-01-01_00-00-00.dat")).resolve("inside.dat"), DAT);
        Path other = Files.createDirectories(backups.resolve(UUID.randomUUID().toString()));
        Files.write(other.resolve("2020-01-01_00-00-00.dat"), DAT);
        Files.write(other.resolve("2020-01-02_00-00-00.dat"), DAT);
        Files.write(backups.resolve("2020-01-03_00-00-00.dat"), DAT);

        round(playerDataDir, backups, FIRST, 1);
        round(playerDataDir, backups, SECOND, 1);

        assertEquals(List.of("2020-01-01_00-00-00.dat", "2026-11-12_09-00-00.dat.bak", SECOND + ".dat", "keep-me.dat"), names(mine));
        assertTrue(Files.exists(mine.resolve("2020-01-01_00-00-00.dat").resolve("inside.dat")), "a folder is never a copy");
        assertEquals(List.of("2020-01-01_00-00-00.dat", "2020-01-02_00-00-00.dat"), names(other), "another player's copies");
        assertTrue(Files.exists(backups.resolve("2020-01-03_00-00-00.dat")), "a file next to the players' folders");
    }

    @Test
    @DisplayName("keep-last 0 deletes nothing")
    void zeroKeepsEveryCopy(@TempDir Path levelRoot, @TempDir Path backups) throws Exception {
        Path playerDataDir = savedByTheServer(levelRoot);

        round(playerDataDir, backups, FIRST, KEEP_ALL);
        round(playerDataDir, backups, SECOND, KEEP_ALL);
        round(playerDataDir, backups, THIRD, KEEP_ALL);

        assertEquals(List.of(FIRST + ".dat", SECOND + ".dat", THIRD + ".dat"), names(backups.resolve(player.toString())));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
