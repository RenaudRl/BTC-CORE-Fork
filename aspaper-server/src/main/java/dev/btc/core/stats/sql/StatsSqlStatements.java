package dev.btc.core.stats.sql;

/**
 * Every SQL statement of the layer.
 *
 * <p>The table prefix (validated by {@link StatsSqlConfig}) and the database-clock expression (a
 * constant of this package) are the only texts concatenated; every value is a {@code ?}.
 * Time is always the database's: a game server with a wrong clock cannot shorten or stretch a lease.
 */
public final class StatsSqlStatements {

    /** Milliseconds since the epoch, on PostgreSQL's clock. */
    public static final String POSTGRES_NOW_MS = "(extract(epoch from clock_timestamp()) * 1000)::bigint";
    public static final int SCHEMA_VERSION = 1;
    static final String SCHEMA_VERSION_KEY = "schema_version";
    static final String UNIQUE_VIOLATION = "23505";

    final String createStats;
    final String createMeta;
    final String selectMeta;
    final String insertMeta;
    final String acquire;
    final String takeover;
    final String create;
    final String read;
    final String holder;
    final String write;
    final String writeAndRelease;
    final String release;
    final String renew;
    final String offlineWrite;
    final String importAbsent;
    final String importReserved;
    final String probe = "SELECT 1";

    public StatsSqlStatements(String prefix, String nowMs) {
        String stats = prefix + "player_stats";
        String meta = prefix + "player_stats_meta";
        String owned = " WHERE player_uuid = ? AND lock_owner = ? AND lock_generation = ?";
        String free = "(lock_owner IS NULL OR lock_until < " + nowMs + ")";
        createStats = "CREATE TABLE IF NOT EXISTS " + stats + " ("
            + "player_uuid UUID PRIMARY KEY, payload TEXT, data_version INTEGER, "
            + "revision BIGINT NOT NULL DEFAULT 0, lock_owner UUID, lock_generation BIGINT NOT NULL DEFAULT 0, "
            + "lock_until BIGINT NOT NULL DEFAULT 0, source VARCHAR(16) NOT NULL DEFAULT 'runtime', "
            + "updated_at BIGINT NOT NULL DEFAULT 0)";
        createMeta = "CREATE TABLE IF NOT EXISTS " + meta
            + " (meta_key VARCHAR(32) PRIMARY KEY, meta_value VARCHAR(64) NOT NULL)";
        selectMeta = "SELECT meta_value FROM " + meta + " WHERE meta_key = ?";
        insertMeta = "INSERT INTO " + meta + " (meta_key, meta_value) VALUES (?, ?)";
        takeover = "UPDATE " + stats + " SET lock_owner = ?, lock_generation = lock_generation + 1, lock_until = "
            + nowMs + " + ? WHERE player_uuid = ?";
        acquire = takeover + " AND (lock_owner IS NULL OR lock_until < " + nowMs + " OR lock_owner = ?)";
        create = "INSERT INTO " + stats + " (player_uuid, revision, lock_owner, lock_generation, lock_until, updated_at)"
            + " VALUES (?, 0, ?, 1, " + nowMs + " + ?, " + nowMs + ")";
        read = "SELECT payload, data_version, revision, lock_generation FROM " + stats + " WHERE player_uuid = ?";
        holder = "SELECT lock_owner FROM " + stats + " WHERE player_uuid = ?";
        write = "UPDATE " + stats + " SET payload = ?, data_version = ?, revision = revision + 1, lock_until = "
            + nowMs + " + ?, updated_at = " + nowMs + ", source = ?" + owned;
        writeAndRelease = "UPDATE " + stats + " SET payload = ?, data_version = ?, revision = revision + 1, "
            + "lock_owner = NULL, lock_until = 0, updated_at = " + nowMs + ", source = ?" + owned;
        release = "UPDATE " + stats + " SET lock_owner = NULL, lock_until = 0" + owned;
        renew = "UPDATE " + stats + " SET lock_until = " + nowMs + " + ?" + owned;
        offlineWrite = "UPDATE " + stats + " SET payload = ?, data_version = ?, revision = revision + 1, updated_at = "
            + nowMs + ", source = ? WHERE player_uuid = ? AND revision = ? AND " + free;
        importAbsent = "INSERT INTO " + stats + " (player_uuid, payload, data_version, source, updated_at) "
            + "SELECT CAST(? AS UUID), CAST(? AS VARCHAR), CAST(? AS INTEGER), 'json-import', " + nowMs
            + " WHERE NOT EXISTS (SELECT 1 FROM " + stats + " WHERE player_uuid = CAST(? AS UUID))";
        importReserved = "UPDATE " + stats + " SET payload = ?, data_version = ?, source = 'json-import', updated_at = "
            + nowMs + " WHERE player_uuid = ? AND payload IS NULL AND " + free;
    }
}
