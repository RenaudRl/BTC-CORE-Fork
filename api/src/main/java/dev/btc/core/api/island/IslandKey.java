package dev.btc.core.api.island;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one island, anchored on the world name rather than on the world's UUID.
 *
 * <p>The anchor matters. A SlimeWorld is handed a fresh {@link org.bukkit.World#getUID() UUID} every
 * time it is loaded, so anything persisted against that UUID is dead after a restart. The world
 * name is the only identifier that survives an unload/reload cycle, which is why it — and not the
 * runtime world object — is what a catch-up operation is keyed on.
 *
 * <p>{@code islandId} is the canonical row identifier held by MySQL. It is carried alongside the
 * world name so a handler never has to parse one out of the other: island ids may contain
 * {@code _}, and a world name may embed a suffix, so any parsing rule invented at the call site is
 * wrong for some island.
 *
 * @param worldName the persisted world name, exactly as stored by the island's dimension data
 * @param islandId  the canonical island identifier
 */
public record IslandKey(String worldName, UUID islandId) {

    public IslandKey {
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(islandId, "islandId");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }

    @Override
    public String toString() {
        return "IslandKey[" + worldName + " / " + islandId + ']';
    }
}
