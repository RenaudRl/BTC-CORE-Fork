package dev.btc.core.integrity.sanction;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.IntegrityDatabase;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues, lifts and reads sanctions.
 *
 * <p>Every write goes to PostgreSQL through {@link IntegrityDatabase} and never blocks a region thread.
 * Reads that gate gameplay — "is this player muted?", asked on every chat message — are answered from a
 * per-player cache loaded once on join, because a database round trip per message is not a thing a chat
 * handler can afford.
 *
 * <p>The cache is the enforcement view, not the history view. It holds only what is in force, it is
 * refreshed on every write concerning that player, and it is dropped when they leave. Anything wanting
 * the full record — a staff history listing, an appeal — reads the database.
 *
 * <p><b>Degradation.</b> With persistence disabled or the database unreachable, issuing fails and says
 * so; it does not pretend to have recorded a sanction. Enforcement then sees an empty cache, which lets
 * players through rather than locking them out: an outage must not become a mass ban.
 */
public final class SanctionService {

    /** How many rows a history listing returns by default. */
    public static final int DEFAULT_HISTORY_LIMIT = 50;

    private static final Map<UUID, List<Sanction>> ACTIVE_BY_PLAYER = new ConcurrentHashMap<>();

    private static volatile boolean ready;

    private SanctionService() {}

    private static String prefix() {
        return AnticheatConfig.storageTablePrefix;
    }

    /**
     * Creates the schema if needed and marks the service usable.
     *
     * <p>Returns a stage rather than blocking, so a database that is slow to answer delays sanctions
     * rather than server startup.
     */
    public static CompletableFuture<Void> start() {
        if (!IntegrityDatabase.isEnabled()) {
            Bukkit.getLogger().warning(
                "[Sentinel] persistence is disabled; sanctions cannot be issued or enforced.");
            return CompletableFuture.completedFuture(null);
        }
        return IntegrityDatabase.applySchema("sanction schema", SanctionStore.schema(prefix()))
            .thenRun(() -> {
                ready = true;
                Bukkit.getLogger().info("[Sentinel] sanction store ready.");
                // Started only once the tables exist: a purge against a missing table would fail on
                // every pass and fill the console with an error that says nothing useful.
                RetentionPolicy.start();
            })
            .exceptionally(failure -> {
                Bukkit.getLogger().severe(
                    "[Sentinel] sanction schema could not be applied; moderation stays unavailable.");
                return null;
            });
    }

    /** Whether sanctions can currently be issued. */
    public static boolean isReady() {
        return ready;
    }

    /**
     * Writes a sanction and returns it carrying its database identity.
     *
     * <p>The record's own constructor has already refused an automatic ban, an unnamed human decision
     * and a missing reason before this point; the schema refuses them again on the way in.
     */
    public static CompletableFuture<Sanction> issue(Sanction sanction) {
        if (!ready) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("the sanction store is not available"));
        }
        return IntegrityDatabase.query("issue " + sanction.type(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                SanctionStore.insertStatement(prefix()), Statement.RETURN_GENERATED_KEYS)) {

                setNullableString(statement, 1, sanction.playerUuid().map(UUID::toString));
                setNullableString(statement, 2, sanction.ipAddress());
                statement.setString(3, sanction.type().name());
                statement.setString(4, sanction.scope().name());
                statement.setString(5, sanction.networkId());
                setNullableString(statement, 6, sanction.serverId());
                statement.setString(7, sanction.reason());
                statement.setBoolean(8, sanction.silent());
                setNullableString(statement, 9, sanction.actorId().map(UUID::toString));
                statement.setString(10, sanction.actorKind().name());
                setNullableString(statement, 11, sanction.evidence());
                statement.setLong(12, sanction.createdAtMillis());
                setNullableLong(statement, 13, sanction.expiresAtMillis());

                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    Optional<Long> id = keys.next()
                        ? Optional.of(keys.getLong(1))
                        : Optional.empty();
                    return withId(sanction, id);
                }
            }
        }).thenApply(stored -> {
            stored.playerUuid().ifPresent(player -> {
                refresh(player);
                // Told to the network only once the row exists. Publishing before the write would send
                // the other servers to read a sanction that is not there yet.
                SanctionBus.Holder.publish(player);
            });
            return stored;
        });
    }

    /**
     * Lifts a sanction, recording who did it and why.
     *
     * @param player who the sanction targets. Passed in rather than read back: the caller already
     *     knows it, and it is what makes the cache refresh and the network invalidation precise
     *     instead of a blanket refresh of everyone connected.
     * @return {@code true} when a standing sanction was lifted, {@code false} when the id was unknown or
     *     already revoked — the caller must tell the moderator which, rather than reporting success
     */
    public static CompletableFuture<Boolean> revoke(long id, UUID player, UUID by, String reason) {
        if (!ready) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("the sanction store is not available"));
        }
        if (reason == null || reason.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("lifting a sanction requires a reason"));
        }
        return IntegrityDatabase.query("revoke sanction " + id, connection -> {
            try (PreparedStatement statement =
                     connection.prepareStatement(SanctionStore.revokeStatement(prefix()))) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, by == null ? null : by.toString());
                statement.setString(3, reason);
                statement.setLong(4, id);
                return statement.executeUpdate() > 0;
            }
        }).thenApply(lifted -> {
            if (lifted) {
                refresh(player);
                SanctionBus.Holder.publish(player);
            }
            return lifted;
        });
    }

    /** Full history for a player, most recent first, revoked entries included. */
    public static CompletableFuture<List<Sanction>> history(UUID player, int limit) {
        if (!ready) {
            return CompletableFuture.completedFuture(List.of());
        }
        return IntegrityDatabase.query("sanction history", connection -> {
            try (PreparedStatement statement =
                     connection.prepareStatement(SanctionStore.historyStatement(prefix()))) {
                statement.setString(1, player.toString());
                statement.setInt(2, Math.max(1, limit));
                return readAll(statement);
            }
        }).exceptionally(IntegrityDatabase.degradeTo(List.of()));
    }

    /** What is in force for a player right now, read from the database. */
    public static CompletableFuture<List<Sanction>> activeFromDatabase(UUID player) {
        if (!ready) {
            return CompletableFuture.completedFuture(List.of());
        }
        return IntegrityDatabase.query("active sanctions", connection -> {
            try (PreparedStatement statement =
                     connection.prepareStatement(SanctionStore.activeStatement(prefix()))) {
                statement.setString(1, player.toString());
                statement.setString(2, AnticheatConfig.networkId);
                statement.setLong(3, System.currentTimeMillis());
                return readAll(statement);
            }
        }).exceptionally(IntegrityDatabase.degradeTo(List.of()));
    }

    /**
     * What is in force for a player, answered from the cache without touching the database.
     *
     * <p>Expiry is applied on read: a mute that lapsed while the player stayed connected has to stop
     * applying at that instant, not at their next reconnection.
     */
    public static List<Sanction> active(UUID player) {
        List<Sanction> cached = ACTIVE_BY_PLAYER.get(player);
        if (cached == null || cached.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return cached.stream().filter(sanction -> sanction.activeAt(now)).toList();
    }

    /** The standing sanction of this type, if any. */
    public static Optional<Sanction> activeOfType(UUID player, SanctionType type) {
        return active(player).stream().filter(sanction -> sanction.type() == type).findFirst();
    }

    /** Loads a player's standing sanctions into the cache. Called when they join. */
    public static CompletableFuture<Void> load(UUID player) {
        return activeFromDatabase(player).thenAccept(list -> ACTIVE_BY_PLAYER.put(player, list));
    }

    /** Drops a player's cache. Called when they leave. */
    public static void forget(UUID player) {
        ACTIVE_BY_PLAYER.remove(player);
    }

    /**
     * Handles an invalidation received from another server.
     *
     * <p>Re-reads only if the player is connected here. A message about someone who is not on this
     * server is not an error and not a miss: they will be read from the database at their next
     * connection, wherever that happens.
     *
     * @param onRefreshed run once the cache is up to date, so the caller can enforce what it now says
     */
    public static void onRemoteChange(UUID player, Runnable onRefreshed) {
        if (!ACTIVE_BY_PLAYER.containsKey(player)) {
            return;
        }
        activeFromDatabase(player).thenAccept(list -> {
            ACTIVE_BY_PLAYER.replace(player, list);
            onRefreshed.run();
        });
    }

    /** Re-reads a cached player. A player who is not cached is not re-added: they are offline. */
    private static void refresh(UUID player) {
        if (!ACTIVE_BY_PLAYER.containsKey(player)) {
            return;
        }
        activeFromDatabase(player).thenAccept(list -> ACTIVE_BY_PLAYER.replace(player, list));
    }

    /**
     * Purges addresses older than the retention horizon, keeping the sanctions themselves.
     *
     * @param retentionMillis how long an address may be kept
     * @return how many rows lost their address
     */
    public static CompletableFuture<Integer> purgeAddresses(long retentionMillis) {
        if (!ready) {
            return CompletableFuture.completedFuture(0);
        }
        long horizon = System.currentTimeMillis() - retentionMillis;
        return IntegrityDatabase.query("address purge", connection -> {
            try (PreparedStatement statement =
                     connection.prepareStatement(SanctionStore.purgeAddressesStatement(prefix()))) {
                statement.setLong(1, horizon);
                return statement.executeUpdate();
            }
        }).exceptionally(IntegrityDatabase.degradeTo(0));
    }

    private static List<Sanction> readAll(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<Sanction> found = new ArrayList<>();
            while (rows.next()) {
                try {
                    found.add(read(rows));
                } catch (IllegalArgumentException unreadable) {
                    // A type or actor kind this version does not know, or a malformed uuid. Skipping the
                    // row keeps the rest of the history readable; staying silent about it would not.
                    Bukkit.getLogger().warning("[Sentinel] skipping unreadable sanction row id="
                        + rows.getLong("id") + ": " + unreadable.getMessage());
                }
            }
            return List.copyOf(found);
        }
    }

    /**
     * Rebuilds a sanction from a row.
     *
     * <p>An unknown {@code type} or {@code actor_kind} — a row written by a newer version, or edited by
     * hand — is skipped rather than crashing the listing, but it is reported: a sanction that silently
     * disappears from a history is worse than one that is visibly not understood.
     */
    private static Sanction read(ResultSet row) throws SQLException {
        return new Sanction(
            Optional.of(row.getLong("id")),
            optionalUuid(row, "player_uuid"),
            Optional.ofNullable(row.getString("ip_address")),
            SanctionType.valueOf(row.getString("type")),
            SanctionScope.valueOf(row.getString("scope")),
            row.getString("network_id"),
            Optional.ofNullable(row.getString("server_id")),
            row.getString("reason"),
            row.getBoolean("silent"),
            optionalUuid(row, "actor_id"),
            ActorKind.valueOf(row.getString("actor_kind")),
            Optional.ofNullable(row.getString("evidence")),
            row.getLong("created_at"),
            optionalLong(row, "expires_at"),
            optionalLong(row, "revoked_at"),
            optionalUuid(row, "revoked_by"),
            Optional.ofNullable(row.getString("revoked_reason"))
        );
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        String raw = row.getString(column);
        return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static void setNullableString(PreparedStatement statement, int index, Optional<String> value)
        throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Optional<Long> value)
        throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.get());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }

    private static Sanction withId(Sanction sanction, Optional<Long> id) {
        return new Sanction(
            id, sanction.playerUuid(), sanction.ipAddress(), sanction.type(), sanction.scope(),
            sanction.networkId(), sanction.serverId(), sanction.reason(), sanction.silent(),
            sanction.actorId(), sanction.actorKind(), sanction.evidence(), sanction.createdAtMillis(),
            sanction.expiresAtMillis(), sanction.revokedAtMillis(), sanction.revokedBy(),
            sanction.revokedReason());
    }
}
