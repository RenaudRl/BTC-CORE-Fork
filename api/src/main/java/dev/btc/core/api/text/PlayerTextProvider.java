package dev.btc.core.api.text;

import net.kyori.adventure.text.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the texts BTC-CORE shows to a player, in the language the network serves that player.
 *
 * <p>BTC-CORE keeps its own configured English defaults and asks the registered provider first
 * ({@link dev.btc.core.api.BTCCoreAPI#registerPlayerTextProvider}). The set of keys BTC-CORE may ask
 * for is published at run time by {@link dev.btc.core.api.BTCCoreAPI#playerTextKeys()}; keys are not
 * part of this API, so a new text never needs a new API version.
 *
 * <p><b>Threading.</b> Called from any thread: the asynchronous pre-login thread, the asynchronous
 * chat thread, a database thread, a region thread or the global region. An implementation reads its
 * own in-memory state only: it never blocks, never does I/O and never touches a world. A slow
 * provider delays the kick or the message that is waiting for it.
 *
 * <p><b>Arguments.</b> Values are inserted as given. Anything a person typed (a sanction reason, a
 * player name) arrives as a plain-text component and must be inserted as such, never interpreted as
 * markup. Texts about a linked account receive no reason and no duration: a translation cannot show
 * what it is never given.
 *
 * <p>The console and the logs never go through a provider.
 *
 * @since 26.3.build.4-alpha
 */
public interface PlayerTextProvider {

    /**
     * Renders one text for one player.
     *
     * @param viewer    the player the text is for; may not be online yet (pre-login)
     * @param key       the text key, one of {@link dev.btc.core.api.BTCCoreAPI#playerTextKeys()}
     * @param arguments the named values the text may use, never {@code null}
     * @return the rendered text, or empty when this provider has no text for the key or the player:
     *         BTC-CORE then shows its own configured default
     */
    Optional<Component> render(UUID viewer, String key, Map<String, Component> arguments);
}
