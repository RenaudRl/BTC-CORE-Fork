package dev.btc.core.integrity;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.LiftArguments;
import dev.btc.core.integrity.sanction.LiftPlan;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionMessages;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What /unban and /unmute lift for a target with linked accounts, and what staff and players are told (AD-090). */
class LinkedLiftTest {

    private final UUID target = UUID.randomUUID();
    private final UUID alias = UUID.randomUUID();

    @Test
    @DisplayName("by default only the sanction recorded against the target is lifted")
    void defaultLiftsTheTargetOnly() {
        Sanction own = ban(1, target, "own");
        Sanction linked = ban(2, alias, "linked");

        LiftPlan plan = LiftPlan.of(target, SanctionType.BAN, List.of(linked, own), false);

        assertEquals(List.of(own), plan.toLift(), "findFirst() order must not decide which account is lifted");
        assertEquals(List.of(linked), plan.stillBlocking());
    }

    @Test
    @DisplayName("a target blocked only through an alias lifts nothing and is told why")
    void aliasOnlyBlockLiftsNothing() {
        Sanction linked = ban(2, alias, "cheating");

        LiftPlan plan = LiftPlan.of(target, SanctionType.BAN, List.of(linked), false);

        assertTrue(plan.toLift().isEmpty());
        assertEquals(List.of(linked), plan.stillBlocking());

        String told = plain(SanctionMessages.liftBlockedByLinked("Steve", linked));
        assertTrue(told.contains(SanctionMessages.shortId(alias)), told);
        assertTrue(told.contains("ban") && told.contains("cheating") && told.contains("--linked"), told);
        assertFalse(told.contains(alias.toString()), "a full uuid must never be shown: " + told);
    }

    @Test
    @DisplayName("--linked lifts every sanction of the type, on the target and on its aliases")
    void linkedFlagLiftsAliasesToo() {
        Sanction own = ban(1, target, "own");
        Sanction linked = ban(2, alias, "linked");

        LiftPlan plan = LiftPlan.of(target, SanctionType.BAN, List.of(own, linked), true);

        assertEquals(List.of(own, linked), plan.toLift());
        assertTrue(plan.stillBlocking().isEmpty());
    }

    @Test
    @DisplayName("a sanction of another type is neither lifted nor reported")
    void otherTypesAreIgnored() {
        Sanction mute = sanction(3, alias, SanctionType.MUTE, "spam");

        LiftPlan plan = LiftPlan.of(target, SanctionType.BAN, List.of(mute), true);

        assertTrue(plan.toLift().isEmpty());
        assertTrue(plan.stillBlocking().isEmpty());
    }

    @Test
    @DisplayName("a reason written by staff stays text: it cannot inject a MiniMessage tag")
    void reasonIsNotParsed() {
        String told = plain(SanctionMessages.liftBlockedByLinked("Steve", ban(2, alias, "<red>boom</red>")));
        assertTrue(told.contains("<red>boom</red>"), told);
    }

    @Test
    @DisplayName("the player refused over an alias ban is not shown its reason")
    void kickOverAliasHidesTheReason() {
        Sanction linked = ban(2, alias, "secret staff reason");

        // The final text the kick carries, as SanctionEnforcement.banMessage produces it.
        String shown = plain(SanctionMessages.banMessage(linked, target));

        assertEquals(AnticheatConfig.msgBannedViaLinkedAccount.replaceAll("<[^>]+>", ""), shown);
        assertFalse(shown.contains("secret staff reason"), shown);
    }

    @Test
    @DisplayName("the account the ban was issued against still sees the reason")
    void kickOnTheAccountItselfShowsTheReason() {
        String shown = plain(SanctionMessages.banMessage(ban(2, alias, "secret staff reason"), alias));

        assertTrue(shown.contains("secret staff reason"), shown);
    }

    @Test
    @DisplayName("--linked is honoured as the last word only; anywhere else it stays in the reason")
    void linkedFlagMustBeTheLastWord() {
        LiftArguments last = LiftArguments.parse(new String[] {"Steve", "cleared", "--LINKED"});
        assertTrue(last.includeLinked());
        assertEquals(List.of("Steve", "cleared"), last.words());

        LiftArguments inside = LiftArguments.parse(new String[] {"Steve", "not", "--linked", "really"});
        assertFalse(inside.includeLinked());
        assertEquals(List.of("Steve", "not", "--linked", "really"), inside.words());

        assertFalse(LiftArguments.parse(new String[] {}).includeLinked());
    }

    @Test
    @DisplayName("after a lift the moderator is not told that nothing was lifted")
    void stillBlockedAfterALiftIsWorded() {
        Sanction linked = ban(2, alias, "cheating");

        String told = plain(SanctionMessages.liftStillBlockedByLinked("Steve", linked));

        assertFalse(told.contains("Nothing was lifted"), told);
        assertTrue(told.contains("still blocked") && told.contains(SanctionMessages.shortId(alias))
            && told.contains("--linked"), told);
    }

    @Test
    @DisplayName("a --linked lift names the lifted accounts by eight characters, never the full uuid")
    void liftedLinkedNamesTheAccounts() {
        String told = plain(SanctionMessages.liftedLinked(SanctionType.BAN, "Steve", Set.of(target, alias), "ok"));

        assertTrue(told.contains(SanctionMessages.shortId(target)) && told.contains(SanctionMessages.shortId(alias)), told);
        assertTrue(told.contains("(2: "), told);
        assertFalse(told.contains(alias.toString()) || told.contains(target.toString()), told);
    }

    @Test
    @DisplayName("a partial or failed lift reports its counts, and an error text stays text")
    void failuresAreReported() {
        String partial = plain(SanctionMessages.liftPartial(SanctionType.BAN, "Steve", 1, 3, 2));
        assertTrue(partial.contains("1 of 3") && partial.contains("2 could NOT"), partial);

        String failed = plain(SanctionMessages.liftFailed(SanctionType.MUTE, "Steve", "<red>db down</red>"));
        assertTrue(failed.contains("<red>db down</red>") && failed.contains("mute"), failed);

        String history = plain(SanctionMessages.historyFailed("Steve", "timeout"));
        assertTrue(history.contains("Steve") && history.contains("timeout"), history);
    }

    @Test
    @DisplayName("a muted alias is told the generic notice: no reason, no duration")
    void muteOverAliasHidesTheReason() {
        Sanction linked = sanction(4, alias, SanctionType.MUTE, "secret staff reason");

        String shown = plain(SanctionMessages.muteNotice(linked, target, "3 days"));

        assertEquals(AnticheatConfig.msgMutedViaLinkedAccount.replaceAll("<[^>]+>", ""), shown);
        assertFalse(shown.contains("secret staff reason") || shown.contains("3 days"), shown);
    }

    @Test
    @DisplayName("the muted account itself still sees the reason, unless the mute is silent")
    void muteOnTheAccountItselfKeepsItsReason() {
        Sanction own = sanction(5, target, SanctionType.MUTE, "spam");

        assertTrue(plain(SanctionMessages.muteNotice(own, target, "3 days")).contains("spam"));
        Sanction quiet = sanction(6, target, SanctionType.MUTE, "spam", true);
        assertFalse(plain(SanctionMessages.muteNotice(quiet, target, "3 days")).contains("spam"));
    }

    @Test
    @DisplayName("a kick or a warning issued against another account is never detailed to the online alias")
    void kickAndWarnOverAliasHideTheReason() {
        for (SanctionType type : new SanctionType[] {SanctionType.KICK, SanctionType.WARN}) {
            Sanction linked = sanction(7, alias, type, "secret staff reason");

            String shown = plain(SanctionMessages.noticeFor(linked, target, "1 day").orElseThrow());

            assertFalse(shown.contains("secret staff reason"), type + ": " + shown);
            assertFalse(shown.isBlank(), type.name());
        }
        assertEquals(plain(MiniMessage.miniMessage().deserialize(AnticheatConfig.msgKickedViaLinkedAccount)),
            plain(SanctionMessages.noticeFor(sanction(7, alias, SanctionType.KICK, "x"), target, "1 day").orElseThrow()));
        assertEquals(plain(MiniMessage.miniMessage().deserialize(AnticheatConfig.msgWarnedViaLinkedAccount)),
            plain(SanctionMessages.noticeFor(sanction(8, alias, SanctionType.WARN, "x"), target, "1 day").orElseThrow()));
    }

    @Test
    @DisplayName("the default texts read exactly as the hard-coded ones did, for the account itself")
    void defaultTextsAreUnchanged() {
        assertEquals("Kicked: rude",
            plain(SanctionMessages.noticeFor(sanction(9, target, SanctionType.KICK, "rude"), target, "-").orElseThrow()));
        assertEquals("Warning: rude",
            plain(SanctionMessages.noticeFor(sanction(9, target, SanctionType.WARN, "rude"), target, "-").orElseThrow()));
        assertEquals("You are muted for 2 days: rude",
            plain(SanctionMessages.noticeFor(sanction(9, target, SanctionType.MUTE, "rude"), target, "2 days").orElseThrow()));
        assertEquals("You are banned (permanent)\nrude",
            plain(SanctionMessages.banMessage(sanction(9, target, SanctionType.BAN, "rude"), target)));
        assertEquals("ban recorded for Steve (3 days): rude",
            plain(SanctionMessages.recorded(SanctionType.BAN, "Steve", "3 days", "rude")));
        assertEquals("ban lifted for Steve: ok", plain(SanctionMessages.lifted(SanctionType.BAN, "Steve", "ok")));
        assertEquals("That sanction was already lifted.", plain(SanctionMessages.alreadyLifted()));
        assertEquals("Steve has no standing mute to lift.",
            plain(SanctionMessages.nothingToLift(SanctionType.MUTE, "Steve")));
    }

    @Test
    @DisplayName("nothing is told for a note or a silent mute; a reason stays text")
    void silentSanctionsSayNothing() {
        assertTrue(SanctionMessages.noticeFor(sanction(1, target, SanctionType.NOTE, "n"), target, "-").isEmpty());
        assertTrue(SanctionMessages.noticeFor(sanction(2, target, SanctionType.MUTE, "m", true), target, "-").isEmpty());
        assertTrue(plain(SanctionMessages.noticeFor(sanction(3, target, SanctionType.WARN, "<red>x</red>"), target, "-")
            .orElseThrow()).contains("<red>x</red>"));
    }

    @Test
    @DisplayName("history marks an alias sanction and lists aliases by their first eight characters")
    void historyShowsAliasesTruncated() {
        String aliases = plain(SanctionMessages.historyAliases(Set.of(alias)));
        assertTrue(aliases.contains(SanctionMessages.shortId(alias)), aliases);
        assertFalse(aliases.contains(alias.toString()), aliases);
        assertEquals(8, SanctionMessages.shortId(alias).length());

        String marker = plain(SanctionMessages.historyViaAlias(alias));
        assertTrue(marker.contains(SanctionMessages.shortId(alias)), marker);
    }

    @Test
    @DisplayName("storage.table-prefix is empty or a letter then [a-z0-9_], 32 characters at most")
    void tablePrefixIsValidated() {
        for (String valid : new String[] {"", "btc_", "a1_b2", "x".repeat(32)}) {
            assertEquals(valid, AnticheatConfig.validateTablePrefix(valid));
        }
        for (String invalid : new String[] {"BTC_", "btc-", "btc.x", "a b", "x; DROP TABLE t;--", "é", "x".repeat(33),
                "1btc", "_btc", null}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AnticheatConfig.validateTablePrefix(invalid), "should refuse: " + invalid);
            assertTrue(refused.getMessage().contains("storage.table-prefix"), refused.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Sanction ban(long id, UUID player, String reason) {
        return sanction(id, player, SanctionType.BAN, reason);
    }

    private static Sanction sanction(long id, UUID player, SanctionType type, String reason) {
        return sanction(id, player, type, reason, false);
    }

    private static Sanction sanction(long id, UUID player, SanctionType type, String reason, boolean silent) {
        return new Sanction(Optional.of(id), Optional.of(player), Optional.empty(), type,
            SanctionScope.NETWORK, "btc", Optional.empty(), reason, silent,
            Optional.of(UUID.randomUUID()), ActorKind.HUMAN, Optional.empty(),
            1L, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
