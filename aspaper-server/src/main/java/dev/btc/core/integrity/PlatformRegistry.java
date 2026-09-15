package dev.btc.core.integrity;

import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which platform each session's client comes from, as bound by the proxy.
 *
 * <p>This registry answers {@link ClientPlatform#UNKNOWN} for anyone it has not been told about, and
 * it can only be told by server-side code holding an authenticated binding — the bridge V2 handler,
 * once {@code bridge-v2-authenticated-control-plane} is closed. There is deliberately no way to
 * derive a platform from the player: a Floodgate name prefix, the {@code 00000000-0000-0000-0009-…}
 * UUID shape and any client-declared attribute are all things a Java client can imitate, and a
 * Bedrock origin buys a wider movement margin (design D14). Whatever cannot be authenticated stays
 * unknown, and an unknown session keeps the movement family in observation.
 *
 * <p>Thread-safe: bound from the network thread that receives the binding, read from region threads.
 */
public final class PlatformRegistry {

    /** A binding: the platform and the channel that vouched for it. */
    public record Binding(ClientPlatform platform, String source) {
        public Binding {
            if (platform == null || platform == ClientPlatform.UNKNOWN) {
                throw new IllegalArgumentException("a binding names a platform; unknown is the absence of one");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("a binding names the channel that vouched for it");
            }
        }
    }

    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private final Set<UUID> missingJournalled = ConcurrentHashMap.newKeySet();

    /**
     * Records what the proxy vouched for. Rebinding replaces: a session that changes server is
     * re-announced by the proxy, and the latest announcement wins.
     */
    public void bind(UUID player, ClientPlatform platform, String source) {
        if (player == null) {
            throw new IllegalArgumentException("a binding needs a player");
        }
        bindings.put(player, new Binding(platform, source));
        missingJournalled.remove(player);
    }

    /** The bound platform, or {@link ClientPlatform#UNKNOWN} when the proxy has said nothing. */
    public ClientPlatform platformOf(UUID player) {
        Binding binding = player == null ? null : bindings.get(player);
        return binding == null ? ClientPlatform.UNKNOWN : binding.platform();
    }

    /** Who vouched for the binding, for the verbose record of a violation. Empty when unbound. */
    public java.util.Optional<String> sourceOf(UUID player) {
        Binding binding = player == null ? null : bindings.get(player);
        return binding == null ? java.util.Optional.empty() : java.util.Optional.of(binding.source());
    }

    /**
     * Whether the absence of a binding still has to be journalled for this session.
     *
     * <p>True exactly once per session while it stays unbound: the spec asks for the missing binding
     * to be journalled once, not on every movement packet. Binding the player resets it, so a
     * session that loses and regains its binding is reported again — that is a second incident.
     */
    public boolean shouldJournalMissing(UUID player) {
        if (player == null || bindings.containsKey(player)) {
            return false;
        }
        return missingJournalled.add(player);
    }

    /** Drops everything about a player. Called when they disconnect. */
    public void clearPlayer(UUID player) {
        bindings.remove(player);
        missingJournalled.remove(player);
    }
}
