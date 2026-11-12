package dev.btc.core.api.zone;

import org.bukkit.entity.Player;

/**
 * Called when a player enters or leaves a {@link Zone}.
 *
 * <p>Both calls run synchronously, on the thread that moves the player - the one that ticks the
 * player's world, or its region under Folia - so the player may be read and changed from them. A
 * call that teleports the player is followed at once by the calls of that teleport. An exception
 * thrown here is logged under the owner of the zone and stops nothing else.
 *
 * @since 26.3.build.3-alpha
 */
public interface ZoneListener {

    /**
     * The player now stands in the zone: a move on foot or driving a vehicle, a teleport, a change
     * of world, a join or a respawn took it there.
     */
    default void onEnter(Player player, Zone zone) {
    }

    /**
     * The player no longer stands in the zone: a move, a teleport, a change of world or a respawn
     * took it out, or it left the server.
     */
    default void onLeave(Player player, Zone zone) {
    }
}
