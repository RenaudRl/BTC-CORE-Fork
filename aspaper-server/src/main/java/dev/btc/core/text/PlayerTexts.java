package dev.btc.core.text;

import dev.btc.core.api.text.PlayerTextProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Turns a {@link PlayerText} into the {@link Component} a player sees: the registered
 * {@link PlayerTextProvider} first, BTC-CORE's own MiniMessage template when it has nothing to say.
 *
 * <p>One provider at a time, one owner: the plugin holding it may register again (a reload replaces
 * it), another plugin is refused while the holder is enabled. A provider of a disabled plugin is not
 * asked. Nothing about a player is retained here.
 *
 * <p><b>Any thread.</b> The provider is called on whichever thread needs the text (asynchronous
 * pre-login included) and must not block; this class adds no lock and does no I/O. A provider that
 * throws is logged once and the template is shown: a broken translation never turns a kick into an
 * exception.
 *
 * <p><b>Input is text.</b> Every argument is a {@link Component}; what a person typed (a reason, a
 * name) goes in as {@link Component#text(String)} and is never parsed as MiniMessage, by the
 * template here nor, by contract, by the provider.
 */
public final class PlayerTexts {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final PlayerTexts SHARED = new PlayerTexts(
        message -> org.apache.logging.log4j.LogManager.getLogger("BTCCore").warn(message));

    /** The provider and the plugin it belongs to. */
    private record Registration(Plugin owner, PlayerTextProvider provider, AtomicBoolean failureReported) {}

    private final Consumer<String> warnings;
    private final AtomicReference<Registration> registration = new AtomicReference<>();

    /** @param warnings where the one-line warnings go (the server log) */
    public PlayerTexts(Consumer<String> warnings) {
        this.warnings = warnings;
    }

    /** The registry of this server, the one {@code BTCCoreAPI#registerPlayerTextProvider} fills. */
    public static PlayerTexts shared() {
        return SHARED;
    }

    /**
     * Registers the provider. A refused registration (another plugin holds the place) returns a handle
     * that does nothing.
     *
     * @return a handle that removes this registration, and only this one: closing a handle replaced
     *         since leaves the newer provider in place
     */
    public AutoCloseable register(Plugin owner, PlayerTextProvider provider) {
        Registration mine = new Registration(owner, provider, new AtomicBoolean());
        while (true) {
            Registration current = registration.get();
            if (current != null && current.owner().isEnabled() && !current.owner().equals(owner)) {
                warnings.accept("[BTCCore] player texts: " + owner.getName() + " tried to register a text provider "
                    + "while " + current.owner().getName() + " holds one - refused.");
                return () -> { };
            }
            // Check and take in one step: two plugins registering together cannot both win.
            if (registration.compareAndSet(current, mine)) {
                return () -> registration.compareAndSet(mine, null);
            }
        }
    }

    /**
     * Renders {@code text} for {@code viewer} with the template in force ({@link PlayerText#template()}).
     *
     * @param viewer    the player the text is for; {@code null} for the console, which never reaches
     *                  the provider
     * @param arguments the named values, keyed by the variables {@code text} declares
     */
    public Component render(@Nullable UUID viewer, PlayerText text, Map<String, Component> arguments) {
        return render(viewer, text, text.template(), arguments);
    }

    /**
     * Same, for a text whose template the caller holds (a setting read from a file BTC-CORE does not
     * own, such as the lease-lost kick message of {@code sources.yml}).
     */
    public Component render(@Nullable UUID viewer, PlayerText text, String template, Map<String, Component> arguments) {
        Optional<Component> provided = provide(viewer, text, arguments);
        return provided.isPresent() ? provided.get() : fill(template, arguments);
    }

    private Optional<Component> provide(@Nullable UUID viewer, PlayerText text, Map<String, Component> arguments) {
        Registration current = registration.get();
        if (current != null && !current.owner().isEnabled()) {
            // A plugin disabled without closing its handle: let go of it, so it can be collected.
            registration.compareAndSet(current, null);
            return Optional.empty();
        }
        if (viewer == null || current == null) {
            return Optional.empty();
        }
        try {
            Optional<Component> answer = current.provider().render(viewer, text.key(), Map.copyOf(arguments));
            return answer == null ? Optional.empty() : answer;
        } catch (Throwable failure) {
            // Only the heap is fatal here. A StackOverflowError is a provider that recurses: it must not
            // escape into a kick, so it falls back like any other failure.
            if (failure instanceof OutOfMemoryError fatal) {
                throw fatal;
            }
            if (current.failureReported().compareAndSet(false, true)) {
                warnings.accept("[BTCCore] player texts: the provider of " + current.owner().getName()
                    + " failed for " + text.key() + " (" + failure + "); showing the built-in texts. "
                    + "This is reported once.");
            }
            return Optional.empty();
        }
    }

    /** The template with every variable replaced by its component: text in, text out, no markup from a value. */
    static Component fill(String template, Map<String, Component> arguments) {
        List<TagResolver> tags = new ArrayList<>(arguments.size());
        arguments.forEach((name, value) -> tags.add(Placeholder.component(name, value)));
        return MINI.deserialize(template, TagResolver.resolver(tags));
    }

    /**
     * The built-in text of the fork, ignoring the provider and the admin's setting: what a refusal shows
     * when nothing else can be rendered.
     */
    public static Component builtIn(PlayerText text, Map<String, Component> arguments) {
        return fill(text.defaultTemplate(), arguments);
    }

    /** The built-in English text as a plain string, for the places that need one (logs, the English staff commands). */
    public static String plainDefault(PlayerText text, Map<String, Component> arguments) {
        return PlainTextComponentSerializer.plainText().serialize(builtIn(text, arguments));
    }
}
