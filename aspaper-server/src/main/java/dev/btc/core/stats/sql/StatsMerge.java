package dev.btc.core.stats.sql;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Merges two statistics documents by keeping the highest value of every counter.
 *
 * <p>Vanilla counters only grow, so the highest value is the one that saw the most play, whichever
 * side wrote it; merging twice gives the same document. Known limit, accepted: a counter reset on
 * one side during a database outage comes back.
 *
 * <p>shortcut: both documents are taken to be of the same DataVersion (the result carries the
 * higher one); run the data fixers on each side first if documents of two game versions must be
 * merged.
 */
public final class StatsMerge {

    private static final String STATS = "stats";
    private static final String DATA_VERSION = "DataVersion";

    private StatsMerge() {}

    /**
     * @return the merged document; the other one when a side is null
     * @throws IllegalArgumentException when a document is not a statistics document
     */
    public static @Nullable String max(@Nullable String left, @Nullable String right) {
        if (left == null) return right;
        if (right == null) return left;
        JsonObject merged = parse(left);
        JsonObject other = parse(right);
        JsonObject stats = section(merged);
        try {
            for (Map.Entry<String, JsonElement> category : section(other).entrySet()) {
                if (!stats.has(category.getKey())) {
                    stats.add(category.getKey(), category.getValue());
                    continue;
                }
                JsonObject counters = stats.getAsJsonObject(category.getKey());
                for (Map.Entry<String, JsonElement> counter : category.getValue().getAsJsonObject().entrySet()) {
                    int value = counter.getValue().getAsInt();
                    if (!counters.has(counter.getKey()) || counters.get(counter.getKey()).getAsInt() < value) {
                        counters.addProperty(counter.getKey(), value);
                    }
                }
            }
            merged.addProperty(DATA_VERSION, Math.max(dataVersion(merged), dataVersion(other)));
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("not a statistics document", unreadable);
        }
        return merged.toString();
    }

    /** @return the DataVersion of a document, 0 when it has none */
    public static int dataVersion(String document) {
        return dataVersion(parse(document));
    }

    private static int dataVersion(JsonObject document) {
        return document.has(DATA_VERSION) ? document.get(DATA_VERSION).getAsInt() : 0;
    }

    private static JsonObject parse(String document) {
        try {
            JsonObject parsed = JsonParser.parseString(document).getAsJsonObject();
            section(parsed);
            return parsed;
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("not a statistics document", unreadable);
        }
    }

    private static JsonObject section(JsonObject document) {
        if (!document.has(STATS)) {
            document.add(STATS, new JsonObject());
        }
        return document.getAsJsonObject(STATS);
    }
}
