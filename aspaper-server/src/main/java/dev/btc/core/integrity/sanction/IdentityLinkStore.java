package dev.btc.core.integrity.sanction;

/**
 * The journal of Java/Bedrock account pairs, and the alias closure enforcement reads through it
 * (AD-090).
 *
 * <p>A sanction is issued against one UUID and stays recorded against it: the history says who was
 * targeted. Enforcement, however, asks about the <em>person</em>. Floodgate only knows the pair while
 * the link exists, so every linked session observed here is written down, and the journal is read back
 * at every check — which is how a sanction survives an unlink, and reaches a fresh Java account linked
 * to a sanctioned Bedrock one.
 *
 * <p><b>Nothing here ever deletes a row.</b> Unlinking does not erase the pair (decision of 06/11),
 * and the pairs are kept as long as the sanctions they serve, so retention does not purge them either.
 */
public final class IdentityLinkStore {

    /**
     * How many links the closure follows from a seed.
     *
     * <p>A person rarely holds more than one Bedrock and one Java account; four hops already covers
     * re-linking twice. The bound is what keeps one long chain of shared accounts from pulling a whole
     * population of strangers into each other's sanctions.
     */
    public static final int MAX_LINK_DEPTH = 4;

    private IdentityLinkStore() {}

    /** Statements creating the journal and its indexes, in order, idempotent, additive only. */
    public static String[] schema(String prefix) {
        String table = prefix + "identity_links";
        return new String[] {
            """
            CREATE TABLE IF NOT EXISTS %s (
                bedrock_uuid VARCHAR(36) NOT NULL,
                java_uuid    VARCHAR(36) NOT NULL,
                first_seen   BIGINT      NOT NULL,
                last_seen    BIGINT      NOT NULL,
                PRIMARY KEY (bedrock_uuid, java_uuid)
            )""".formatted(table),

            // The primary key serves lookups by bedrock_uuid; the closure walks the other side too.
            "CREATE INDEX IF NOT EXISTS idx_%sidentity_links_java ON %s (java_uuid)"
                .formatted(prefix, table)
        };
    }

    /**
     * Records a linked session: inserts the pair, or only moves {@code last_seen} when it is known.
     *
     * <p>Parameters: bedrock uuid, java uuid, first seen, last seen.
     */
    public static String recordStatement(String prefix) {
        return """
            INSERT INTO %sidentity_links (bedrock_uuid, java_uuid, first_seen, last_seen)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (bedrock_uuid, java_uuid) DO UPDATE SET last_seen = EXCLUDED.last_seen"""
            .formatted(prefix);
    }

    /**
     * Every UUID reachable from the seeds through the journal, the seeds included.
     *
     * <p>Single parameter: the seeds, as a {@code VARCHAR(36) ARRAY}.
     */
    public static String aliasesStatement(String prefix) {
        return closure(prefix) + "\nSELECT DISTINCT alias_uuid FROM aliases";
    }

    /**
     * The recursive CTE {@code aliases(alias_uuid, hops)}, seeded by the first statement parameter.
     *
     * <p>Links are walked in both directions. {@code UNION} rather than {@code UNION ALL} drops a row
     * already reached at the same depth, and the depth bound ends a cycle: A–B–A stops at
     * {@link #MAX_LINK_DEPTH} instead of looping. Both terms are cast to the column type, which
     * PostgreSQL requires of a recursive CTE.
     */
    static String closure(String prefix) {
        return """
            WITH RECURSIVE aliases (alias_uuid, hops) AS (
                SELECT CAST(seed.alias_uuid AS VARCHAR(36)), 0
                  FROM UNNEST(CAST(? AS VARCHAR(36) ARRAY)) AS seed (alias_uuid)
                UNION
                SELECT CAST(CASE WHEN edge.bedrock_uuid = known.alias_uuid
                                 THEN edge.java_uuid ELSE edge.bedrock_uuid END AS VARCHAR(36)),
                       known.hops + 1
                  FROM aliases known
                  JOIN %sidentity_links edge
                    ON edge.bedrock_uuid = known.alias_uuid OR edge.java_uuid = known.alias_uuid
                 WHERE known.hops < %d
            )""".formatted(prefix, MAX_LINK_DEPTH);
    }
}
