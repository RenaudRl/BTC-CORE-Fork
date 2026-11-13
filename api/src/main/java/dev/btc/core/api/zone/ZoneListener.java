package dev.btc.core.api.zone;

import org.bukkit.entity.Player;

/**
 * Called when a player enters or leaves a {@link Zone}.
 *
 * <p>Both calls run synchronously, on the thread that moves the player - the one that ticks the
 * player's world, or its region under Folia - so the player may be read and changed from them. A
 * call that teleports the player is followed at once by the calls of that teleport. An exception
 * thrown here is logged under the owner of the zone (the first time, then once every thousand
 * failures) and stops nothing else.
 *
 * <p>Where the player is when called. For a move on foot or driving a vehicle, and for a teleport
 * within a world, the call comes just before the move is applied, as {@code PlayerMoveEvent} and
 * {@code PlayerTeleportEvent} do: {@code player.getLocation()} still reads the block it comes from,
 * while the zone is the one of the block it goes to. For a change of world, a join or a respawn, the
 * call comes after: the player already stands at the new place.
 *
 * @since 26.3.build.3-alpha
 */
public interface ZoneListener {

    /**
     * The player is entering the zone: a move on foot or driving a vehicle, a teleport, a change of
     * world, a join or a respawn takes it there.
     */
    default void onEnter(Player player, Zone zone) {
    }

    /**
     * The player is leaving the zone: a move, a teleport, a change of world or a respawn takes it
     * out, or it left the server.
     */
    default void onLeave(Player player, Zone zone) {
    }
}
