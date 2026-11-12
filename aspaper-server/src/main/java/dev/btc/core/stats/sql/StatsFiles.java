package dev.btc.core.stats.sql;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The local {@code stats/<uuid>.json} files as the fallback of the SQL layer.
 *
 * <p>A file written while the database could not take the document gets a {@code .pending} marker
 * next to it: that file then holds play the database has not seen, and is merged into the
 * database, by maximum, the next time the player's row is held. The marker is created before the
 * file and removed only after the merge is stored, so a crash at any point errs towards merging
 * once more, which changes nothing.
 */
final class StatsFiles {

    private static final String PENDING = ".pending";

    private StatsFiles() {}

    /** @return the document, or null when there is no file */
    static @Nullable String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    /** Replaces the file as a whole: a reader sees the old document or the new one, never a part. */
    static void write(Path file, String document) throws IOException {
        Files.createDirectories(file.getParent());
        Path fresh = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(fresh, document, StandardCharsets.UTF_8);
            Files.move(fresh, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(fresh);
        }
    }

    /** Writes a document the database did not take: marker first, then the file. */
    static void writePending(Path file, String document) throws IOException {
        markPending(file);
        write(file, document);
    }

    static void markPending(Path file) throws IOException {
        if (!hasPending(file)) {
            Files.createDirectories(file.getParent());
            Files.write(marker(file), new byte[0]);
        }
    }

    static boolean hasPending(Path file) {
        return Files.exists(marker(file));
    }

    static void clearPending(Path file) throws IOException {
        Files.deleteIfExists(marker(file));
    }

    static boolean isMarker(Path file) {
        return file.getFileName().toString().endsWith(PENDING);
    }

    private static Path marker(Path file) {
        return file.resolveSibling(file.getFileName() + PENDING);
    }
}
