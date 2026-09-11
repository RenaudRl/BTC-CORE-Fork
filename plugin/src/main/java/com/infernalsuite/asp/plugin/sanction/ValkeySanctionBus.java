package com.infernalsuite.asp.plugin.sanction;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.SanctionBus;
import dev.btc.core.integrity.sanction.SanctionService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Cross-server propagation over any Redis-protocol store.
 *
 * <p>Valkey, Dragonfly and Redis all speak the same protocol, so one client covers all three; the URI
 * scheme is normalised the same way the SlimeWorld loader already does it, so an operator can write
 * {@code valkey://}, {@code dragonfly://} or {@code redis://} interchangeably.
 *
 * <p>Lives in the plugin module because that is where the client is — and where it is relocated at
 * shadow time. The server module holds only the {@link SanctionBus} contract.
 *
 * <p><b>Two connections, not one.</b> A Lettuce connection in subscribe mode cannot publish: that is a
 * property of the protocol, not a Lettuce limitation. A single connection would work until the first
 * sanction issued on a subscribed node, then fail there and only there.
 */
public final class ValkeySanctionBus implements SanctionBus {

    /**
     * Identity of this node, regenerated at every start.
     *
     * <p>It only ever has to distinguish "me" from "not me" for messages in flight, so persistence
     * would buy nothing — and a stale identity shared by two nodes after a clone would be worse than
     * none.
     */
    private final UUID nodeId = UUID.randomUUID();

    private final Plugin host;
    private final RedisClient client;
    private final StatefulRedisPubSubConnection<String, String> subscriber;
    private final io.lettuce.core.api.StatefulRedisConnection<String, String> publisher;
    private final String channel;

    private ValkeySanctionBus(Plugin host, RedisClient client,
                              StatefulRedisPubSubConnection<String, String> subscriber,
                              io.lettuce.core.api.StatefulRedisConnection<String, String> publisher,
                              String channel) {
        this.host = host;
        this.client = client;
        this.subscriber = subscriber;
        this.publisher = publisher;
        this.channel = channel;
    }

    /**
     * Connects and subscribes, or reports why it could not.
     *
     * <p>A failure here is never fatal: sanctions still work, they just reach the other servers at the
     * player's next connection instead of immediately. That is a degradation worth logging loudly and
     * not worth refusing to start over.
     */
    public static Optional<ValkeySanctionBus> connect(Plugin host) {
        if (!AnticheatConfig.busEnabled) {
            return Optional.empty();
        }
        // One channel per network: two networks sharing a store must not invalidate each other.
        String channel = "btc:sentinel:sanctions:" + AnticheatConfig.networkId;

        try {
            RedisClient client = RedisClient.create(normalizeUri(AnticheatConfig.busUri));
            var subscriber = client.connectPubSub();
            var publisher = client.connect();

            ValkeySanctionBus bus = new ValkeySanctionBus(host, client, subscriber, publisher, channel);
            subscriber.addListener(bus.new Receiver());
            subscriber.sync().subscribe(channel);

            Bukkit.getLogger().info("[Sentinel] sanction bus connected on '" + channel
                + "' as node " + bus.nodeId + ".");
            return Optional.of(bus);

        } catch (RuntimeException failure) {
            Bukkit.getLogger().log(Level.WARNING, "[Sentinel] the sanction bus could not connect; "
                + "sanctions still apply, but other servers will only see them at the player's next "
                + "connection.", failure);
            return Optional.empty();
        }
    }

    @Override
    public void publish(UUID player) {
        // Asynchronous on purpose: this is reached from the database thread, and a store that has
        // become slow must not turn into a stalled sanction pipeline.
        publisher.async().publish(channel, new Invalidation(nodeId, player).encode());
    }

    @Override
    public void close() {
        try {
            subscriber.close();
            publisher.close();
            client.shutdown();
        } catch (RuntimeException ignored) {
            // Shutting down; a store that is already gone is not a problem worth reporting.
        }
    }

    /** Applies what another server decided. */
    private final class Receiver extends RedisPubSubAdapter<String, String> {

        @Override
        public void message(String channel, String message) {
            Invalidation.decode(message)
                .filter(invalidation -> !invalidation.nodeId().equals(nodeId))
                .ifPresent(invalidation -> SanctionService.onRemoteChange(
                    invalidation.player(),
                    () -> enforce(invalidation.player())));
        }
    }

    /**
     * Enforces what the freshly read cache now says.
     *
     * <p>Hops onto the player's region scheduler first: this runs on a Lettuce I/O thread, which may
     * not touch a player.
     */
    private void enforce(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        player.getScheduler().run(host, task -> SanctionEnforcement.applyNow(player), null);
    }

    /**
     * Accepts the same URI vocabulary as the SlimeWorld loader, by calling it.
     *
     * <p>Deliberately delegated rather than reimplemented. Valkey, Dragonfly and Redis differ only in
     * the scheme an operator writes, and that vocabulary is defined in exactly one place — otherwise a
     * scheme added for worlds and forgotten for sanctions produces "it connects for one and not the
     * other", which is a genuinely slow bug to find.
     */
    static String normalizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("identity.bus.uri is empty");
        }
        return com.infernalsuite.asp.loaders.redis.RedisLoader.normalizeUri(uri.trim());
    }
}
