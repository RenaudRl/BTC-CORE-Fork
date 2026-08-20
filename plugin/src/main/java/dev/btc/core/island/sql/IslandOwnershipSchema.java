package dev.btc.core.island.sql;

/**
 * The DDL and statements behind {@link SqlIslandOwnershipSource} and {@link SqlCatchUpJournal}.
 *
 * <p>Kept apart from the sources so the schema reads as a whole, and built per {@link SqlDialect}
 * because exactly three things differ between MySQL and PostgreSQL. Everything else is plain
 * unquoted SQL both engines accept.
 *
 * <p>The compare-and-set relies on row-level locking, which both provide under their default
 * isolation.
 */
final class IslandOwnershipSchema {

    private static final String OWNERSHIP_COLUMNS = """
          island_id         CHAR(36)     NOT NULL,
          world_name        VARCHAR(191) NOT NULL,
          owner_backend     VARCHAR(128) NULL,
          fencing_token     BIGINT       NOT NULL DEFAULT 0,
          lease_expires_at  BIGINT       NOT NULL DEFAULT 0,
          last_committed_at BIGINT       NULL,
          origin_chunk_x    INT          NOT NULL DEFAULT 0,
          origin_chunk_z    INT          NOT NULL DEFAULT 0,
          unlocked_radius   INT          NOT NULL DEFAULT 0,
          PRIMARY KEY (island_id),
          UNIQUE (world_name)
        """;

    private static final String JOURNAL_COLUMNS = """
          operation_id   CHAR(36)     NOT NULL,
          system_key     VARCHAR(128) NOT NULL,
          island_id      CHAR(36)     NOT NULL,
          world_name     VARCHAR(191) NOT NULL,
          from_ts        BIGINT       NOT NULL,
          to_ts          BIGINT       NOT NULL,
          backend_id     VARCHAR(128) NOT NULL,
          fencing_token  BIGINT       NOT NULL,
          status         VARCHAR(24)  NOT NULL,
          schema_version INT          NOT NULL,
          operations     INT          NOT NULL DEFAULT 0,
          result_hash    CHAR(64)     NULL,
          created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (operation_id, system_key)
        """;

    private static final String INSERT_OPERATION = """
        INSERT INTO btc_island_catchup_operations
          (operation_id, system_key, island_id, world_name, from_ts, to_ts, backend_id,
           fencing_token, status, schema_version, operations, result_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /**
     * The canonical row per island.
     *
     * <p>{@code world_name} is unique because it is the anchor the server resolves on: a SlimeWorld
     * gets a fresh {@code World} UUID on every load, so the name is the only identifier that
     * survives an unload. {@code island_id} stays the primary key so a rename never orphans the
     * journal.
     *
     * <p>{@code fencing_token} only ever increases. It is what lets a commit be refused when a
     * second backend has claimed the island in the meantime, which a lease expiry alone cannot
     * detect: an expired-then-reclaimed island looks identical to a still-held one from the losing
     * backend's point of view.
     */
    final String createOwnershipTable;

    /**
     * The append-only record of what actually ran.
     *
     * <p>The primary key is {@code (operation_id, system_key)}: one activation issues a single
     * operation id and every handler writes one row under it. Re-running the same activation after
     * a crash therefore collides instead of double-counting, which is the whole idempotency
     * guarantee — replay safety comes from the key, not from the caller remembering.
     */
    final String createJournalTable;

    /**
     * The journal's secondary index, or {@code null} when the dialect declares it inline.
     *
     * <p>PostgreSQL cannot declare a non-unique index inside {@code CREATE TABLE}; MySQL can, and
     * does not support {@code CREATE INDEX IF NOT EXISTS}. Hence the split rather than a shared
     * statement that would fail on one of the two.
     */
    final String createJournalIndex;

    /**
     * Writes one handler's outcome, doing nothing when the key already exists.
     *
     * <p>A replayed operation must leave the first record intact, because that record is the
     * evidence of what the island actually did; overwriting it would erase the very thing the
     * journal exists to prove. Both forms report one affected row on insert and zero on collision,
     * which is how the caller tells a fresh write from a replay.
     *
     * <p>Deliberately not {@code INSERT IGNORE} on MySQL: that downgrades every error to a warning,
     * including truncation and type conversion, so a genuinely malformed row would be swallowed as
     * if it were a replay. {@code ON DUPLICATE KEY UPDATE} of a column to itself ignores only the
     * duplicate-key case.
     */
    final String recordOperation;

    IslandOwnershipSchema(SqlDialect dialect) {
        String tableOptions = dialect == SqlDialect.MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";

        this.createOwnershipTable =
            "CREATE TABLE IF NOT EXISTS btc_island_ownership (\n" + OWNERSHIP_COLUMNS + ")" + tableOptions;

        this.createJournalTable = switch (dialect) {
            case MYSQL -> "CREATE TABLE IF NOT EXISTS btc_island_catchup_operations (\n" + JOURNAL_COLUMNS
                + ",\n  KEY idx_island_window (island_id, to_ts)\n)" + tableOptions;
            case POSTGRESQL -> "CREATE TABLE IF NOT EXISTS btc_island_catchup_operations (\n"
                + JOURNAL_COLUMNS + ")";
        };

        this.createJournalIndex = switch (dialect) {
            case MYSQL -> null;
            case POSTGRESQL -> "CREATE INDEX IF NOT EXISTS idx_island_window "
                + "ON btc_island_catchup_operations (island_id, to_ts)";
        };

        this.recordOperation = switch (dialect) {
            case MYSQL -> INSERT_OPERATION + "\nON DUPLICATE KEY UPDATE operation_id = operation_id";
            case POSTGRESQL -> INSERT_OPERATION + "\nON CONFLICT DO NOTHING";
        };
    }

    static final String SELECT_BY_WORLD =
        "SELECT island_id FROM btc_island_ownership WHERE world_name = ?";

    /**
     * The compare-and-set claim.
     *
     * <p>A claim succeeds only when nobody holds the island, when this backend already held it, or
     * when the previous lease has lapsed. The token is bumped in the same statement so two backends
     * racing here cannot come away with the same token.
     */
    static final String CLAIM =
        "UPDATE btc_island_ownership SET owner_backend = ?, fencing_token = fencing_token + 1, "
            + "lease_expires_at = ? WHERE island_id = ? "
            + "AND (owner_backend IS NULL OR owner_backend = ? OR lease_expires_at <= ?)";

    static final String SELECT_TOKEN =
        "SELECT fencing_token FROM btc_island_ownership WHERE island_id = ?";

    static final String SELECT_LAST_COMMITTED =
        "SELECT last_committed_at FROM btc_island_ownership WHERE island_id = ?";

    /** Advances the window and releases the lease, refused when the token is no longer current. */
    static final String COMMIT =
        "UPDATE btc_island_ownership SET last_committed_at = ?, owner_backend = NULL, "
            + "lease_expires_at = 0 WHERE island_id = ? AND owner_backend = ? AND fencing_token = ?";

    /** Releases the lease without advancing the window, so the same window is replayed. */
    static final String ABANDON =
        "UPDATE btc_island_ownership SET owner_backend = NULL, lease_expires_at = 0 "
            + "WHERE island_id = ? AND owner_backend = ? AND fencing_token = ?";

    static final String SELECT_PERIMETER =
        "SELECT origin_chunk_x, origin_chunk_z, unlocked_radius FROM btc_island_ownership "
            + "WHERE island_id = ?";
}
