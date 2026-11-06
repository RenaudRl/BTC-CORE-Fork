package com.infernalsuite.asp.plugin.sanction;

import dev.btc.core.integrity.sanction.Sanction;
import dev.btc.core.integrity.sanction.SanctionDuration;
import dev.btc.core.integrity.sanction.SanctionMessages;
import dev.btc.core.integrity.sanction.SanctionService;
import dev.btc.core.integrity.sanction.SanctionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Applies to a connected player whatever the cache currently says about them.
 *
 * <p>Exists so that there is exactly one answer to "what does a standing sanction do to someone who is
 * already online". Three callers need it — a moderator acting here, another server acting through the
 * bus, and a sanction that arrives while the player is connected — and three separate implementations
 * would drift until a ban issued on one path stopped kicking on another.
 *
 * <p>Only enforces what has an immediate effect. A mute is not enforced here: it is enforced when the
 * player speaks, by the chat listener, which is the only moment it means anything.
 *
 * <p><b>Must run on the player's region thread.</b> Callers coming from a database or network thread
 * hop through the region scheduler first.
 */
public final class SanctionEnforcement {

    private SanctionEnforcement() {}

    /** Applies the standing sanctions of a connected player. Safe to call when there are none. */
    public static void applyNow(Player player) {
        Optional<Sanction> ban =
            SanctionService.activeOfType(player.getUniqueId(), SanctionType.BAN);
        if (ban.isPresent()) {
            player.kick(banMessage(ban.get(), player.getUniqueId()));
            return;
        }

        // Nothing else needs doing right now: a mute acts on the next message, a note and a warning
        // have already been delivered, and a restriction is read by the checks themselves.
    }

    /**
     * The message a banned player sees, whether they are kicked now or refused at the door.
     *
     * <p>A ban issued against another account of the person is not detailed: its reason and duration
     * were written about that account, and showing them would disclose the other account's case.
     *
     * @param viewer the uuid the server knows the player by
     */
    public static Component banMessage(Sanction ban, UUID viewer) {
        if (SanctionMessages.isOnAlias(ban, viewer)) {
            return SanctionMessages.bannedViaLinkedAccount();
        }
        String remaining =
            SanctionDuration.describeRemaining(System.currentTimeMillis(), ban.expiresAtMillis());
        return Component.text("You are banned (" + remaining + ")\n" + ban.reason(), NamedTextColor.RED);
    }
}
