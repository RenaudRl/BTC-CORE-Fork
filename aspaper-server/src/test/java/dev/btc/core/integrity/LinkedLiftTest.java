package dev.btc.core.integrity;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.sanction.ActorKind;
import dev.btc.core.integrity.sanction.LiftPlan;
import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionMessages;
import dev.btc.core.integrity.sanction.SanctionScope;
import dev.btc.core.integrity.sanction.SanctionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertTrue(SanctionMessages.isOnAlias(linked, target));
        assertFalse(SanctionMessages.isOnAlias(linked, alias));
        String shown = plain(SanctionMessages.bannedViaLinkedAccount());
        assertFalse(shown.contains("secret staff reason"));
        assertEquals(AnticheatConfig.msgBannedViaLinkedAccount.replaceAll("<[^>]+>", ""), shown);
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

    // ---------------------------------------------------------------- helpers

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Sanction ban(long id, UUID player, String reason) {
        return sanction(id, player, SanctionType.BAN, reason);
    }

    private static Sanction sanction(long id, UUID player, SanctionType type, String reason) {
        return new Sanction(Optional.of(id), Optional.of(player), Optional.empty(), type,
            SanctionScope.NETWORK, "btc", Optional.empty(), reason, false,
            Optional.of(UUID.randomUUID()), ActorKind.HUMAN, Optional.empty(),
            1L, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
