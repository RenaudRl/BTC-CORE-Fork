package com.infernalsuite.asp.plugin.sanction;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * The live Java/Bedrock pair of a connecting player, as Floodgate states it (AD-090).
 *
 * <p>Floodgate is optional. Without it — the production case today — {@link #NONE} answers that no
 * session is linked, and enforcement falls back to the server uuid plus the identity link journal.
 * Same loading pattern as {@code FloodgatePlatformSource}: a missing Floodgate must never stop the
 * server from starting.
 *
 * <p>Safe to call from the asynchronous pre-login thread: Floodgate registers the player during the
 * handshake ({@code FloodgateHandshakeHandler#handlePart2} calls {@code api.addPlayer}), before login
 * start, so the pair is already readable when {@code AsyncPlayerPreLoginEvent} fires.
 */
public interface LinkedAccounts {

    /** No Floodgate here: no session is ever seen as linked. */
    LinkedAccounts NONE = player -> Optional.empty();

    /**
     * The pair for a connected or connecting player, when their session is a linked one.
     *
     * @param player the uuid the server knows them by (the Java uuid of a linked Bedrock player)
     */
    Optional<Pair> pairOf(UUID player);

    /**
     * A Bedrock account and the Java account it is linked to.
     *
     * @param bedrock the Floodgate uuid derived from the XUID ({@code getJavaUniqueId})
     * @param java the linked Java uuid ({@code getCorrectUniqueId})
     */
    record Pair(UUID bedrock, UUID java) {}

    /** Floodgate-backed accounts, or {@link #NONE} when Floodgate is absent or not started. */
    static LinkedAccounts createIfPresent() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi", false,
                LinkedAccounts.class.getClassLoader());
            FloodgateApi api = FloodgateApi.getInstance();
            return api == null ? NONE : new Floodgate(api);
        } catch (ClassNotFoundException | LinkageError absent) {
            return NONE;
        }
    }

    /** Reads the pair from Floodgate. Only loaded once Floodgate is known to be on the classpath. */
    final class Floodgate implements LinkedAccounts {

        private final FloodgateApi api;

        private Floodgate(FloodgateApi api) {
            this.api = api;
        }

        @Override
        public Optional<Pair> pairOf(UUID player) {
            FloodgatePlayer session = api.getPlayer(player);
            if (session == null || !session.isLinked()) {
                return Optional.empty();
            }
            return Optional.of(new Pair(session.getJavaUniqueId(), session.getCorrectUniqueId()));
        }
    }
}
