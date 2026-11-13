package dev.btc.core.integrity;

import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionMessages;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionType;
import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the sanction texts hand to a {@link dev.btc.core.api.text.PlayerTextProvider}, and what they
 * show when it answers: the part of the translation hook that carries a security duty (AD-091 and
 * markup in a reason).
 */
class SanctionMessagesTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final String MARKUP = "<click:run_command:/op x>";

    private final UUID target = UUID.randomUUID();
    private final UUID alias = UUID.randomUUID();

    /** Every ask of the provider under test, in order: key and the arguments it was given. */
    private final List<Map.Entry<String, Map<String, Component>>> asked = new ArrayList<>();
    private AutoCloseable registration;

    @BeforeEach
    void noProvider() {
        asked.clear();
    }

    @AfterEach
    void unregister() throws Exception {
        if (registration != null) {
            registration.close();
        }
    }

    /** Registers a provider that records what it is asked and answers with {@code answer} (empty = not mine). */
    private void provider(BiFunction<String, Map<String, Component>, Optional<Component>> answer) {
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Translate");
        when(owner.isEnabled()).thenReturn(true);
        registration = PlayerTexts.shared().register(owner, (viewer, key, arguments) -> {
            asked.add(Map.entry(key, arguments));
            return answer.apply(key, arguments);
        });
    }

    @Test
    @DisplayName("a text about a linked account is never given a reason nor a duration (AD-091)")
    void linkedAccountTextsNeverReceiveReasonOrDuration() {
        provider((key, arguments) -> Optional.empty());
        Sanction ban = sanction(alias, SanctionType.BAN, "secret staff reason", Optional.of(System.currentTimeMillis() + 3 * DAY));
        Sanction mute = sanction(alias, SanctionType.MUTE, "secret staff reason", Optional.of(System.currentTimeMillis() + 3 * DAY));
        SanctionDuration.Remaining remaining = SanctionDuration.remaining(0, Optional.of(3 * DAY));

        List<Component> shown = List.of(
            SanctionMessages.banMessage(ban, target),
            SanctionMessages.muteNotice(mute, target, remaining),
            SanctionMessages.noticeFor(sanction(alias, SanctionType.KICK, "secret staff reason", Optional.empty()), target, remaining).orElseThrow(),
            SanctionMessages.noticeFor(sanction(alias, SanctionType.WARN, "secret staff reason", Optional.empty()), target, remaining).orElseThrow(),
            SanctionMessages.noticeFor(ban, target, remaining).orElseThrow(),
            SanctionMessages.noticeFor(mute, target, remaining).orElseThrow());

        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Map<String, Component>> ask : asked) {
            keys.add(ask.getKey());
            assertEquals(Map.of(), ask.getValue(), ask.getKey() + " was handed arguments about a linked account");
        }
        assertEquals(Set.of(PlayerText.SANCTION_BANNED_LINKED.key(), PlayerText.SANCTION_MUTED_LINKED.key(),
            PlayerText.SANCTION_KICKED_LINKED.key(), PlayerText.SANCTION_WARNED_LINKED.key()), keys,
            "a linked case must ask for the linked texts only: no duration text, no type text");
        for (Component text : shown) {
            String plain = plain(text);
            assertFalse(plain.contains("secret staff reason") || plain.contains("3d"), plain);
        }
    }

    @Test
    @DisplayName("a reason that looks like markup stays plain text, for the provider and in the built-in template")
    void reasonWithMarkupStaysPlainText() {
        String reason = MARKUP + "<hover:show_text:'x'>cheating";
        Sanction own = sanction(target, SanctionType.KICK, reason, Optional.empty());
        provider((key, arguments) -> Optional.empty());

        Component builtIn = SanctionMessages.noticeFor(own, target, SanctionDuration.remaining(0, Optional.empty())).orElseThrow();

        Component given = asked.get(0).getValue().get("reason");
        assertEquals(Component.text(reason), given, "the provider must receive the reason as a plain text component");
        assertEquals("Kicked: " + reason, plain(builtIn));
        assertFalse(hasInteraction(builtIn), "a click or hover leaked out of the reason");

        Component ban = SanctionMessages.banMessage(sanction(target, SanctionType.BAN, reason, Optional.empty()), target);
        assertTrue(plain(ban).endsWith(reason), plain(ban));
        assertFalse(hasInteraction(ban), "a click or hover leaked out of a ban reason");

        Component lift = SanctionMessages.lifted(null, SanctionType.BAN, MARKUP + "Steve", MARKUP + "ok");
        assertFalse(hasInteraction(lift), "a click leaked out of a name or a lift reason");
    }

    @Test
    @DisplayName("the time left and the sanction type are texts of the provider too")
    void remainingDurationGoesThroughProvider() {
        provider((key, arguments) -> switch (key) {
            case "btccore.duration.days_hours" -> Optional.of(Component.text(
                plain(arguments.get("days")) + "j " + plain(arguments.get("hours")) + "h"));
            case "btccore.sanction.type.ban" -> Optional.of(Component.text("bannissement"));
            case "btccore.sanction.banned" -> Optional.of(Component.textOfChildren(
                Component.text("Banni ("), arguments.get("remaining"), Component.text(") "), arguments.get("reason")));
            case "btccore.sanction.recorded" -> Optional.of(Component.textOfChildren(
                arguments.get("type"), Component.text(" / "), arguments.get("duration")));
            default -> Optional.empty();
        });
        long expiry = System.currentTimeMillis() + 2 * DAY + 3 * HOUR + HOUR / 2;

        Component ban = SanctionMessages.banMessage(sanction(target, SanctionType.BAN, "grief", Optional.of(expiry)), target);
        Component recorded = SanctionMessages.recorded(null, SanctionType.BAN, "Steve",
            SanctionDuration.remaining(0, Optional.of(2 * DAY + 3 * HOUR)), "grief");

        assertEquals("Banni (2j 3h) grief", plain(ban));
        assertEquals("ban recorded for Steve (2d 3h): grief", plain(recorded), "the console is never asked");
        Component recordedForStaff = SanctionMessages.recorded(UUID.randomUUID(), SanctionType.BAN, "Steve",
            SanctionDuration.remaining(0, Optional.of(2 * DAY + 3 * HOUR)), "grief");
        assertEquals("bannissement / 2j 3h", plain(recordedForStaff));
    }

    @Test
    @DisplayName("every text is given exactly the arguments the catalog declares for its key")
    void everyTextIsGivenExactlyItsDeclaredArguments() {
        provider((key, arguments) -> Optional.empty());
        UUID staff = UUID.randomUUID();
        SanctionDuration.Remaining remaining = SanctionDuration.remaining(0, Optional.of(2 * DAY + 3 * HOUR));
        Sanction blocker = sanction(alias, SanctionType.BAN, "r", Optional.empty());

        SanctionMessages.banMessage(sanction(target, SanctionType.BAN, "r", Optional.of(System.currentTimeMillis() + DAY)), target);
        for (SanctionType type : SanctionType.values()) {
            SanctionMessages.noticeFor(sanction(target, type, "r", Optional.empty()), target, remaining);
            SanctionMessages.recorded(staff, type, "Steve", remaining, "r");
        }
        SanctionMessages.muteNotice(sanction(target, SanctionType.MUTE, "r", Optional.empty()), target, remaining);
        SanctionMessages.lifted(staff, SanctionType.BAN, "Steve", "r");
        SanctionMessages.alreadyLifted(staff);
        SanctionMessages.nothingToLift(staff, SanctionType.BAN, "Steve");
        SanctionMessages.liftBlockedByLinked(staff, "Steve", blocker);
        SanctionMessages.liftStillBlockedByLinked(staff, "Steve", blocker);
        SanctionMessages.liftedLinked(staff, SanctionType.BAN, "Steve", Set.of(target, alias), "r");
        SanctionMessages.liftPartial(staff, SanctionType.BAN, "Steve", 1, 2, 1);
        SanctionMessages.liftFailed(staff, SanctionType.BAN, "Steve", "boom");
        SanctionMessages.historyFailed(staff, "Steve", "boom");
        SanctionMessages.historyAliases(staff, Set.of(alias));
        SanctionMessages.historyViaAlias(staff, alias);
        for (long left : new long[] {-1, 5_000, 5 * 60_000, 2 * HOUR, 3 * DAY}) {
            SanctionMessages.duration(staff, SanctionDuration.remaining(0, Optional.of(left)));
        }
        SanctionMessages.duration(staff, SanctionDuration.remaining(0, Optional.empty()));

        Map<String, PlayerText> byKey = new LinkedHashMap<>();
        for (PlayerText text : PlayerText.values()) {
            byKey.put(text.key(), text);
        }
        assertFalse(asked.isEmpty());
        for (Map.Entry<String, Map<String, Component>> ask : asked) {
            PlayerText text = byKey.get(ask.getKey());
            assertEquals(new HashSet<>(text.variables()), ask.getValue().keySet(), ask.getKey());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean hasInteraction(Component component) {
        return component.clickEvent() != null || component.hoverEvent() != null
            || component.children().stream().anyMatch(SanctionMessagesTest::hasInteraction);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Sanction sanction(UUID player, SanctionType type, String reason, Optional<Long> expiresAt) {
        return new Sanction(Optional.of(1L), Optional.of(player), Optional.empty(), type,
            SanctionScope.NETWORK, "btc", Optional.empty(), reason, false,
            Optional.of(UUID.randomUUID()), ActorKind.HUMAN, Optional.empty(),
            1L, expiresAt, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
