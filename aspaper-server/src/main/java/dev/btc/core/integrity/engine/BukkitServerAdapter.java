package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.DeclarationRegistry;
import dev.btc.core.integrity.ViolationBus;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.security.SentinelCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The engine's view of the live server.
 *
 * <p>Thin on purpose: each method is one lookup or one hand-off to a component that already owns
 * the behaviour — the journal ({@link NativeAnticheatDB}), the bus ({@link ViolationBus}), the
 * verbose subscription of {@code /sentinel verbose}. Nothing is decided here.
 *
 * <p>Every method runs on the region thread that owns the player, which is what makes
 * {@link Bukkit#getPlayer(UUID)} and {@link Player#getLocation()} legal to call.
 */
public final class BukkitServerAdapter implements ServerAdapter {

    private final DeclarationRegistry declarations;
    private final ViolationBus violations;

    public BukkitServerAdapter(final DeclarationRegistry declarations, final ViolationBus violations) {
        this.declarations = declarations;
        this.violations = violations;
    }

    @Override
    public Optional<String> playerName(final UUID player) {
        return Optional.ofNullable(Bukkit.getPlayer(player)).map(Player::getName);
    }

    @Override
    public Optional<TeleportKind> consumeDeclaredTeleport(final UUID player, final double x,
                                                          final double y, final double z) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return Optional.empty();
        }
        // The declaration is matched by world name and position; the world is the one the player
        // is in once the teleport is confirmed, which is where this is called from.
        return declarations.consumeDeclaredTeleportKind(player, new Location(online.getWorld(), x, y, z));
    }

    @Override
    public void journal(final UUID player, final String playerName, final CheckId check, final String detail) {
        NativeAnticheatDB.reportViolation(player.toString(), playerName, check.toString(), detail);
    }

    @Override
    public void publish(final UUID player, final ViolationEvent event) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return;
        }
        violations.publish(online, event);
    }

    @Override
    public void verbose(final String line) {
        if (SentinelCommand.verboseSubscribers.isEmpty()) {
            return;
        }
        final org.bukkit.plugin.Plugin host = hostPlugin();
        if (host == null) {
            return;
        }
        for (final UUID subscriber : SentinelCommand.verboseSubscribers) {
            final Player staff = Bukkit.getPlayer(subscriber);
            if (staff == null) {
                continue;
            }
            // The subscriber may live on another region: deliver on their own thread.
            staff.getScheduler().run(host, task -> staff.sendMessage(line), null);
        }
    }

    private static org.bukkit.plugin.Plugin hostPlugin() {
        return Bukkit.getPluginManager().getPlugin("ASPaper");
    }
}
