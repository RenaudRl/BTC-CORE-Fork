package com.infernalsuite.asp.plugin;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.security.admin.OpLock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies {@link OpLock} to the running server: at start-up, every {@code sweep-interval-ticks}, and to
 * {@code /op} and {@code /deop} typed by a player. The decisions are in {@code OpLock}; this class only
 * reads the operator list, removes what the decision says, and journals.
 *
 * <p>The lock only ever removes an op. It never sanctions the account that tried, and it never touches
 * the console or RCON, which do not raise {@link PlayerCommandPreprocessEvent}.
 *
 * <p>Folia: the sweep runs on the global region scheduler and reads the operator list, never a world.
 * An online player is de-opped on their own scheduler, because the permission level and the command tree
 * go out on their connection; an offline one on the global thread. The command handler runs on the
 * player's thread and touches only the event.
 */
final class OpLockListener implements Listener {

    /** Journal check type of every line this lock writes. */
    private static final String CHECK_TYPE = "admin:op-lock";

    private final Plugin plugin;
    private final Logger log;
    private final OpLock.Mode mode;
    private final Set<UUID> allowed;
    private final long sweepIntervalTicks;
    private final Component refusal;

    /** Ops already reported in observe mode, so a standing one is one line, not one per sweep. Global thread only. */
    private final Set<UUID> reported = new HashSet<>();

    OpLockListener(final Plugin plugin, final Logger log) {
        this.plugin = plugin;
        this.log = log;
        this.mode = AnticheatConfig.opLockMode;
        this.allowed = AnticheatConfig.opLockAllowed;
        this.sweepIntervalTicks = AnticheatConfig.opLockSweepIntervalTicks;
        this.refusal = MiniMessage.miniMessage().deserialize(AnticheatConfig.opLockRefusedMessage);
    }

    /** Reconciles now, before any connection, then sweeps on a timer. Call from {@code onEnable}. */
    void start() {
        AnticheatConfig.opLockInvalidEntries.forEach(entry -> log.error(
            "[OpLock] admin.op-lock.allowed: '{}' is not a UUID and is ignored", entry));
        log.info("[OpLock] mode={} allowed={} sweep every {} ticks", mode, allowed.size(), sweepIntervalTicks);
        if (mode == OpLock.Mode.ENFORCE && allowed.isEmpty()) {
            log.warn("[OpLock] mode is enforce and admin.op-lock.allowed is empty: NO player may be op, "
                + "every current op will be removed. The console and RCON still work.");
        }
        reconcile("startup");
        plugin.getServer().getGlobalRegionScheduler()
            .runAtFixedRate(plugin, task -> reconcile("sweep"), sweepIntervalTicks, sweepIntervalTicks);
    }

    private void reconcile(final String source) {
        final Set<UUID> ops = Bukkit.getOperators().stream().map(OfflinePlayer::getUniqueId).collect(Collectors.toSet());
        final Set<UUID> outside = OpLock.outsideTheList(ops, allowed, mode);
        reported.retainAll(outside);
        final Set<UUID> revoked = OpLock.revocations(ops, allowed, mode);
        for (final UUID uuid : outside) {
            final boolean remove = revoked.contains(uuid);
            if (!remove && !reported.add(uuid)) {
                continue;
            }
            final String name = nameOf(uuid);
            report(uuid, name, remove
                ? "op removed: not in allowlist (source=" + source + ")"
                : "op outside allowlist, not removed in observe mode (source=" + source + ")");
            if (remove) {
                remove(uuid);
            }
        }
        if (source.equals("startup") && !revoked.isEmpty()) {
            log.warn("[OpLock] {} op(s) removed at start-up. If they are back at the next start, the ops.json of "
                + "the server template still lists them: correct the template, the server never writes it.", revoked.size());
        }
    }

    private void remove(final UUID uuid) {
        final Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.getScheduler().run(plugin, task -> online.setOp(false), null);
        } else {
            Bukkit.getOfflinePlayer(uuid).setOp(false);
        }
    }

    /**
     * First line of defence, at the keyboard: the decision is {@link OpLock#commandVerdict}, applied here.
     * The sweep is what makes the invariant hold for the paths that never pass through a command.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final Player player = event.getPlayer();
        final String line = event.getMessage();
        final OpLock.CommandVerdict verdict =
            OpLock.commandVerdict(mode, player.isOp(), player.getUniqueId(), allowed, line);
        if (!verdict.journals()) {
            return;
        }
        final String what = verdict.reason() == OpLock.Reason.OP_OUTSIDE_LIST ? "command by an op outside the allowlist" : "op command";
        report(player.getUniqueId(), player.getName(), what + (verdict.cancel() ? " cancelled: " : " observed: ")
            + line.strip() + (verdict.revokeOp() ? ", op removed" : "") + " (source=command)");
        if (verdict.revokeOp()) {
            player.setOp(false);
        }
        if (verdict.cancel()) {
            event.setCancelled(true);
            player.sendMessage(refusal);
        }
    }

    private void report(final UUID uuid, final String name, final String detail) {
        log.warn("[OpLock] {} ({}): {}", name, uuid, detail);
        NativeAnticheatDB.reportViolation(uuid.toString(), name, CHECK_TYPE, detail);
    }

    private static String nameOf(final UUID uuid) {
        final String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }
}
