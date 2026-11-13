package com.infernalsuite.asp.plugin;

import com.infernalsuite.asp.level.SlimeLevelInstance;
import dev.btc.core.playerdata.SaveAlignment;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Task 5.1, observe mode: at each save of a Slime world, logs which players it should be aligned with
 * (those in it, and those who left it since its last save). It saves nothing and changes nothing.
 *
 * <p>Folia: {@link WorldSaveEvent} is emitted on the thread that owns the world, or on the main thread
 * while the worlds are stopped, so the world's players are read from a thread allowed to; a player of
 * another world is only looked up by UUID and asked who owns it. The state is per world, in the world.
 */
final class SaveAlignmentListener implements Listener {

    /** What the thread saving the world knows about a player. */
    private static final SaveAlignment.Probe PLAYERS = new SaveAlignment.Probe() {
        @Override
        public boolean persistent(final UUID player) {
            final Player online = Bukkit.getPlayer(player);
            return online != null && online.isPersistent();
        }

        @Override
        public boolean online(final UUID player) {
            return Bukkit.getPlayer(player) != null;
        }

        @Override
        public boolean ownedHere(final UUID player) {
            final Player online = Bukkit.getPlayer(player);
            return online != null && Bukkit.isOwnedByCurrentRegion(online);
        }
    };

    private final Logger log;

    SaveAlignmentListener(final Logger log) {
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(final WorldSaveEvent event) {
        final World world = event.getWorld();
        if (!(((CraftWorld) world).getHandle() instanceof SlimeLevelInstance slime)) {
            return;
        }
        final List<UUID> present = world.getPlayers().stream().map(Player::getUniqueId).toList();
        final SaveAlignment.Plan plan = slime.saveAlignment().onWorldSaved(present, PLAYERS);
        log.debug("[SaveAlignment] {}", plan.describe(world.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        if (((CraftWorld) event.getFrom()).getHandle() instanceof SlimeLevelInstance slime) {
            slime.saveAlignment().onLeft(event.getPlayer().getUniqueId());
        }
    }
}
