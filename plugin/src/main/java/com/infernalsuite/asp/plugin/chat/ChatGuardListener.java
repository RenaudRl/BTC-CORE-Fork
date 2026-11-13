package com.infernalsuite.asp.plugin.chat;

import dev.btc.core.integrity.chat.ChatJournal;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Feeds public chat and private messages to {@link ChatGuardService}. Both handlers run before the chat
 * plugins that format or relay the message (LOW), and skip an event another plugin already cancelled.
 *
 * <p>Public chat arrives on an asynchronous thread, a private-message command on the player's region
 * thread: the service is safe from both, and nothing here reads a world.
 */
final class ChatGuardListener implements Listener {

    private final ChatGuardService service;

    ChatGuardListener(final ChatGuardService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (service.mustCancel(event.getPlayer(), text, ChatJournal.Channel.PUBLIC)) {
            event.setCancelled(true);
        }
    }

    /** {@code /msg Bob hi} is judged on {@code hi}: the same account and the same rules as the public chat. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        service.whisperText(event.getMessage()).ifPresent(said -> {
            if (service.mustCancel(event.getPlayer(), said, ChatJournal.Channel.WHISPER)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }
}
