package dev.btc.core.overlay;

import dev.btc.core.api.overlay.BlockOverlayAPI;
import dev.btc.core.api.overlay.PlayerBlockOverlay;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Registered through META-INF/services for {@link BlockOverlayAPI#find()}. */
public final class BlockOverlayAPIImpl implements BlockOverlayAPI {

    @Override
    public PlayerBlockOverlay overlay(final Player player) {
        return PlayerBlockOverlayImpl.of(((CraftPlayer) player).getHandle());
    }
}
