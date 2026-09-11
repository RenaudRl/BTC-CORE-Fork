package com.infernalsuite.asp.plugin;

import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionService;
import dev.btc.core.integrity.sanction.SanctionType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Where a recorded sanction becomes something a player experiences.
 *
 * <p>Three moments: entry is refused to a banned account, the enforcement cache is filled on join and
 * dropped on quit, and a muted player's messages are stopped.
 *
 * <p><b>Every failure here lets the player through.</b> A database that is slow or down must not become
 * a server that refuses everyone: the cost of briefly missing a mute is a message, the cost of the
 * opposite is an outage indistinguishable from a mass ban. That choice is deliberate and is why the
 * lookup below has a timeout rather than waiting indefinitely.
 */
public final class SanctionListener implements Listener {

    /** How long entry may wait on the sanction store before the player is let in regardless. */
    private static final long LOGIN_LOOKUP_TIMEOUT_MILLIS = 2000L;

    /**
     * Refuses entry to a banned account.
     *
     * <p>Runs on Paper's asynchronous pre-login thread, which exists precisely so that a lookup like
     * this one can block without touching a tick.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!SanctionService.isReady()) {
            return;
        }
        List<Sanction> active;
        try {
            active = SanctionService.activeFromDatabase(event.getUniqueId())
                .get(LOGIN_LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception unavailable) {
            Bukkit.getLogger().log(Level.WARNING, "[Sentinel] could not check sanctions for "
                + event.getName() + "; letting them in.", unavailable);
            return;
        }

        Optional<Sanction> ban = active.stream()
            .filter(sanction -> sanction.type() == SanctionType.BAN)
            .findFirst();
        if (ban.isEmpty()) {
            return;
        }

        String remaining = SanctionDuration.describeRemaining(
            System.currentTimeMillis(), ban.get().expiresAtMillis());
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Component.text(
            "You are banned (" + remaining + ")\n" + ban.get().reason(), NamedTextColor.RED));
    }

    /** Fills the enforcement cache. Asynchronous: nothing here delays the join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        SanctionService.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SanctionService.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Stops a muted player's messages.
     *
     * <p>Answered from the cache, never from the database: this runs on every message sent by every
     * player, and a round trip per message is not something a chat handler can afford.
     *
     * <p>A silent mute still tells the player their message did not go through. Silence here means the
     * reason is not disclosed, not that the player is left talking to a wall — that would be a bug they
     * would report rather than a moderation decision they can understand.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Optional<Sanction> mute =
            SanctionService.activeOfType(event.getPlayer().getUniqueId(), SanctionType.MUTE);
        if (mute.isEmpty()) {
            return;
        }
        event.setCancelled(true);

        String remaining = SanctionDuration.describeRemaining(
            System.currentTimeMillis(), mute.get().expiresAtMillis());
        Component notice = mute.get().silent()
            ? Component.text("You cannot speak right now.", NamedTextColor.RED)
            : Component.text("You are muted for " + remaining + ": " + mute.get().reason(),
                NamedTextColor.RED);
        event.getPlayer().sendMessage(notice);
    }
}
