package dev.btc.core.text;

import dev.btc.core.api.text.PlayerTextProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one registry of text providers: who may hold it, and what is shown when the provider says nothing. */
class PlayerTextsTest {

    private static final UUID VIEWER = UUID.randomUUID();
    /** Has two variables and no setting behind it: the template is its default, whatever the config is. */
    private static final PlayerText TEXT = PlayerText.COMMAND_PING;
    private static final Map<String, Component> ARGUMENTS =
        Map.of("name", Component.text("Steve"), "ping", Component.text("42"));

    private final List<String> warnings = new ArrayList<>();
    private PlayerTexts texts;

    @BeforeEach
    void freshRegistry() {
        texts = new PlayerTexts(warnings::add);
    }

    @Test
    @DisplayName("the provider's text is what the player sees")
    void rendersProviderTextWhenProviderAnswers() throws Exception {
        UUID[] askedFor = new UUID[1];
        String[] askedKey = new String[1];
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> {
            askedFor[0] = viewer;
            askedKey[0] = key;
            return Optional.of(Component.text("Ping de " + plain(arguments.get("name")) + ": " + plain(arguments.get("ping"))));
        });

        assertEquals("Ping de Steve: 42", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
        assertEquals(VIEWER, askedFor[0]);
        assertEquals("btccore.command.ping", askedKey[0]);
    }

    @Test
    @DisplayName("without a provider the built-in template is shown, variables filled")
    void fallsBackToConfiguredTemplateWithoutProvider() {
        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
    }

    @Test
    @DisplayName("a template the caller holds is the one filled when the provider has nothing")
    void fallsBackToTheCallersTemplate() {
        assertEquals("<<Steve>>", plain(texts.render(VIEWER, TEXT, "<<<name>>>", ARGUMENTS)));
    }

    @Test
    @DisplayName("a provider with no text for the key leaves the built-in template")
    void fallsBackWhenProviderHasNoKey() {
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> Optional.empty());

        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
        assertEquals(List.of(), warnings, "an empty answer is normal, not a warning");
    }

    @Test
    @DisplayName("a provider that throws is skipped, and said once")
    void fallsBackAndLogsOnceWhenProviderThrows() {
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("boom") && warnings.get(0).contains("btccore.command.ping"), warnings.get(0));
    }

    @Test
    @DisplayName("a link error of the provider (class absent at run time) falls back too")
    void fallsBackWhenProviderFailsToLink() {
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> {
            throw new NoClassDefFoundError("dev/btc/Missing");
        });

        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
    }

    @Test
    @DisplayName("the console never reaches the provider")
    void consoleNeverAsksTheProvider() {
        boolean[] asked = new boolean[1];
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> {
            asked[0] = true;
            return Optional.of(Component.text("from the provider"));
        });

        assertEquals("Steve's Ping: 42ms", plain(texts.render(null, TEXT, ARGUMENTS)));
        assertFalse(asked[0], "the provider was asked for the console");
    }

    @Test
    @DisplayName("a provider whose plugin is disabled is not asked")
    void ignoresProviderOfDisabledOwner() {
        Plugin owner = plugin("Translate", true);
        texts.register(owner, (viewer, key, arguments) -> Optional.of(Component.text("from the provider")));
        when(owner.isEnabled()).thenReturn(false);

        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
    }

    @Test
    @DisplayName("a second plugin is refused while the first is enabled, and the first keeps serving")
    void refusesSecondOwnerAndKeepsFirst() {
        texts.register(plugin("Translate", true), (viewer, key, arguments) -> Optional.of(Component.text("first")));
        texts.register(plugin("Intruder", true), (viewer, key, arguments) -> Optional.of(Component.text("second")));

        assertEquals("first", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    @DisplayName("a second plugin takes the place of a first one that was disabled")
    void takesOverFromDisabledOwner() {
        Plugin first = plugin("Translate", true);
        texts.register(first, (viewer, key, arguments) -> Optional.of(Component.text("first")));
        when(first.isEnabled()).thenReturn(false);
        texts.register(plugin("Other", true), (viewer, key, arguments) -> Optional.of(Component.text("second")));

        assertEquals("second", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
    }

    @Test
    @DisplayName("closing a handle that was replaced since leaves the newer provider")
    void closingStaleHandleKeepsNewerProvider() throws Exception {
        Plugin owner = plugin("Translate", true);
        AutoCloseable stale = texts.register(owner, (viewer, key, arguments) -> Optional.of(Component.text("old")));
        AutoCloseable current = texts.register(owner, (viewer, key, arguments) -> Optional.of(Component.text("new")));

        stale.close();
        assertEquals("new", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));

        current.close();
        assertEquals("Steve's Ping: 42ms", plain(texts.render(VIEWER, TEXT, ARGUMENTS)));
    }

    @Test
    @DisplayName("what a person typed in an argument is text, not a tag, in the built-in template too")
    void argumentIsNeverParsedAsMarkup() {
        Component rendered = texts.render(VIEWER, TEXT,
            Map.of("name", Component.text("<click:run_command:/op x>Steve"), "ping", Component.text("1")));

        assertEquals("<click:run_command:/op x>Steve's Ping: 1ms", plain(rendered));
        assertFalse(hasClick(rendered), "a click event leaked out of an argument");
    }

    private static boolean hasClick(Component component) {
        return component.clickEvent() != null || component.children().stream().anyMatch(PlayerTextsTest::hasClick);
    }

    private static Plugin plugin(String name, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
