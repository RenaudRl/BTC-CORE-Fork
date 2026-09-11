package dev.btc.core.integrity.sanction;

/**
 * The PostgreSQL schema that holds sanctions.
 *
 * <p>Kept as statements rather than as an ORM mapping so that the constraint below is visible to
 * anyone reading the schema, not buried in annotations.
 *
 * <p>Two decisions are worth stating explicitly.
 *
 * <p><b>A ban attributed to automatic detection is refused by the database.</b> The application refuses
 * it too ({@link Sanction}), and that would normally be enough — except that an invariant guarded in a
 * single place is one refactor away from being guarded nowhere. This one matters enough to be stated
 * twice.
 *
 * <p><b>Lifting a sanction is a revocation, not a deletion.</b> Every reference system studied —
 * LibertyBans, LiteBans, AdvancedBan, BanManager — converged on keeping the original row and recording
 * who lifted it, when and why. A moderation history that can be quietly rewritten is not a history.
 */
public final class SanctionStore {

    private SanctionStore() {}

    /** Statements creating the table and its indexes, in order, idempotent. */
    public static String[] schema(String prefix) {
        String table = prefix + "sanctions";
        return new String[] {
            """
            CREATE TABLE IF NOT EXISTS %s (
                id             BIGSERIAL PRIMARY KEY,
                player_uuid    VARCHAR(36)  NULL,
                ip_address     VARCHAR(45)  NULL,
                type           VARCHAR(16)  NOT NULL,
                scope          VARCHAR(16)  NOT NULL,
                network_id     VARCHAR(128) NOT NULL,
                server_id      VARCHAR(128) NULL,
                reason         TEXT         NOT NULL,
                silent         BOOLEAN      NOT NULL DEFAULT FALSE,
                actor_id       VARCHAR(36)  NULL,
                actor_kind     VARCHAR(16)  NOT NULL,
                evidence       TEXT         NULL,
                created_at     BIGINT       NOT NULL,
                expires_at     BIGINT       NULL,
                revoked_at     BIGINT       NULL,
                revoked_by     VARCHAR(36)  NULL,
                revoked_reason TEXT         NULL,
                CONSTRAINT ck_%ssanctions_no_auto_ban
                    CHECK (NOT (type = 'BAN' AND actor_kind = 'ANTICHEAT_AUTO')),
                CONSTRAINT ck_%ssanctions_has_target
                    CHECK (player_uuid IS NOT NULL OR ip_address IS NOT NULL),
                CONSTRAINT ck_%ssanctions_human_is_named
                    CHECK (actor_kind <> 'HUMAN' OR actor_id IS NOT NULL)
            )""".formatted(table, prefix, prefix, prefix),

            // History lookups are always "this player, most recent first".
            "CREATE INDEX IF NOT EXISTS idx_%ssanctions_player ON %s (player_uuid, created_at DESC)"
                .formatted(prefix, table),

            "CREATE INDEX IF NOT EXISTS idx_%ssanctions_ip ON %s (ip_address)"
                .formatted(prefix, table),

            // Partial index: enforcement asks "what is in force right now", which never concerns
            // revoked rows, so they are kept out of the index entirely.
            """
            CREATE INDEX IF NOT EXISTS idx_%ssanctions_active ON %s (type, expires_at)
                WHERE revoked_at IS NULL""".formatted(prefix, table),

            "CREATE INDEX IF NOT EXISTS idx_%ssanctions_actor ON %s (actor_id, created_at DESC)"
                .formatted(prefix, table)
        };
    }

    /**
     * Statement purging addresses older than a retention horizon, keeping the sanction itself.
     *
     * <p>An IP address is personal data. French practice puts technical security logs at six months to
     * a year, on a legitimate-interest basis that has to be documented and limited to security. The
     * sanction stays; only the address goes, so history remains readable without holding personal data
     * indefinitely.
     */
    public static String purgeAddressesStatement(String prefix) {
        return """
            UPDATE %ssanctions
               SET ip_address = NULL
             WHERE ip_address IS NOT NULL
               AND created_at < ?""".formatted(prefix);
    }

    /** Insert statement; parameter order matches {@link #INSERT_COLUMNS}. */
    public static String insertStatement(String prefix) {
        return """
            INSERT INTO %ssanctions
                (player_uuid, ip_address, type, scope, network_id, server_id, reason, silent,
                 actor_id, actor_kind, evidence, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(prefix);
    }

    /** Column order used by {@link #insertStatement(String)}. */
    public static final String[] INSERT_COLUMNS = {
        "player_uuid", "ip_address", "type", "scope", "network_id", "server_id", "reason", "silent",
        "actor_id", "actor_kind", "evidence", "created_at", "expires_at"
    };

    /** Revocation statement: records who lifted it, when and why, without touching the original. */
    public static String revokeStatement(String prefix) {
        return """
            UPDATE %ssanctions
               SET revoked_at = ?, revoked_by = ?, revoked_reason = ?
             WHERE id = ? AND revoked_at IS NULL""".formatted(prefix);
    }

    /**
     * Every column, in the order {@code SELECT_COLUMNS} names them.
     *
     * <p>Both reads return the full row rather than the subset each one strictly needs, so that a single
     * mapper reconstructs a whole {@link Sanction}. A narrower projection would force a second, partial
     * type to exist alongside it — and a partial type is where a field silently stops being carried.
     */
    private static final String SELECT_COLUMNS = """
        id, player_uuid, ip_address, type, scope, network_id, server_id, reason, silent,
        actor_id, actor_kind, evidence, created_at, expires_at, revoked_at, revoked_by, revoked_reason""";

    /** History lookup for one player, most recent first, revoked rows included. */
    public static String historyStatement(String prefix) {
        return """
            SELECT %s
              FROM %ssanctions
             WHERE player_uuid = ?
             ORDER BY created_at DESC
             LIMIT ?""".formatted(SELECT_COLUMNS, prefix);
    }

    /** Everything in force for a player right now, on this network. */
    public static String activeStatement(String prefix) {
        return """
            SELECT %s
              FROM %ssanctions
             WHERE player_uuid = ?
               AND network_id = ?
               AND revoked_at IS NULL
               AND (expires_at IS NULL OR expires_at > ?)
             ORDER BY created_at DESC""".formatted(SELECT_COLUMNS, prefix);
    }
}
