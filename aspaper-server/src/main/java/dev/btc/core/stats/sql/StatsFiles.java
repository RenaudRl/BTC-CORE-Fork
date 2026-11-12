package dev.btc.core.stats.sql;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * database the next time the player's row is held. The marker is created before the file and
 * removed only after the merge is stored.
 *
 * <p>The marker says how to merge. Empty, the file continues the stored document (a session of the
 * layer that fell back to its file, or a marker of an older build) and is merged by maximum, which
 * may be replayed freely. With a base, the file belongs to a session that started from the file
 * itself: only what it gained over that base is added, and the marker remembers the merge it was
 * last counted in, so that a replay adds nothing twice.
 */
final class StatsFiles {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");
    private static final String PENDING = ".pending";
    private static final String BASE = "base";
    private static final String REVISION = "revision";
    private static final String COUNTED = "counted";

    /**
     * What a marker says of its file.
     *
     * @param base      what the file held when its session started; null when the file continues the stored document
     * @param mergingAt the revision of the row when the merge of {@code counted} was started
     * @param counted   the file as it was handed to that merge; null when none was started
     */
    record Pending(@Nullable String base, long mergingAt, @Nullable String counted) {

        /**
         * @param revision the revision of the row now
         * @return what the file's gain is measured from: past the revision a merge was started at, that
         *         merge is stored and only what the file gained since counts
         */
        @Nullable String baseAt(long revision) {
            // shortcut: any later revision is taken for that merge. A merge whose write failed, followed by a
            // write of another server before this one tries again, skips that file's gain once; stamp the
            // row with a merge id (schema change) if that ever shows.
            return counted != null && revision > mergingAt ? counted : base;
        }
    }

    private StatsFiles() {}

    /** @return the document, or null when there is no file */
    static @Nullable String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    /** @return the file's statistics document; null when there is no file or it does not hold one */
    static @Nullable String readDocument(Path file) throws IOException {
        String document = read(file);
        if (document == null) {
            return null;
        }
        try {
            StatsMerge.dataVersion(document);
            return document;
        } catch (IllegalArgumentException notStatistics) {
            LOGGER.warn("[BTCCore] stats-sql: {} is not a statistics document and was left out", file.getFileName());
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
        markPending(file, null);
        write(file, document);
    }

    /**
     * Marks the file, unless it is marked already: the first marker is the one that knows where the
     * file left the database.
     *
     * @param base what the file's session starts from; null when the file continues the stored document
     */
    static void markPending(Path file, @Nullable String base) throws IOException {
        if (!hasPending(file)) {
            mark(file, new Pending(base, 0, null));
        }
    }

    /** Before the merge of {@code counted} is written to a row at {@code revision}: a replay will know it. */
    static void merging(Path file, String base, long revision, String counted) throws IOException {
        mark(file, new Pending(base, revision, counted));
    }

    /** That merge was refused, nothing was written: the file is still to be merged from {@code base}. */
    static void notMerged(Path file, String base) throws IOException {
        mark(file, new Pending(base, 0, null));
    }

    /** @return what the marker says; null when the file is not marked */
    static @Nullable Pending pending(Path file) throws IOException {
        String marker = read(marker(file));
        if (marker == null) {
            return null;
        }
        try {
            JsonObject said = JsonParser.parseString(marker).getAsJsonObject();
            return new Pending(said.get(BASE).getAsString(), said.has(REVISION) ? said.get(REVISION).getAsLong() : 0,
                said.has(COUNTED) ? said.get(COUNTED).getAsString() : null);
        } catch (RuntimeException emptyOrOld) {
            return new Pending(null, 0, null); // merged by maximum, as before markers said anything
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

    private static void mark(Path file, Pending pending) throws IOException {
        if (pending.base == null) {
            Files.createDirectories(file.getParent());
            Files.write(marker(file), new byte[0]);
            return;
        }
        JsonObject says = new JsonObject();
        says.addProperty(BASE, pending.base);
        if (pending.counted != null) {
            says.addProperty(REVISION, pending.mergingAt);
            says.addProperty(COUNTED, pending.counted);
        }
        write(marker(file), says.toString());
    }

    private static Path marker(Path file) {
        return file.resolveSibling(file.getFileName() + PENDING);
    }
}
