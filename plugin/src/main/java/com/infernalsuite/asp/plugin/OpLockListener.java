package com.infernalsuite.asp.plugin;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.security.admin.ObserveThrottle;
import dev.btc.core.security.admin.OpLock;
import dev.btc.core.text.PlayerText;
import dev.btc.core.text.PlayerTexts;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    /** Ops already reported in observe mode, so a standing one is one line, not one per sweep. Global thread only. */
    private final Set<UUID> reported = new HashSet<>();

    /** At most one observe-mode command line per player per minute; a log cadence, not a gameplay value. */
    private final ObserveThrottle observeThrottle =
        new ObserveThrottle(TimeUnit.MINUTES.toNanos(1), System::nanoTime);

    OpLockListener(final Plugin plugin, final Logger log) {
        this.plugin = plugin;
        this.log = log;
        this.mode = AnticheatConfig.opLockMode;
        this.allowed = AnticheatConfig.opLockAllowed;
        this.sweepIntervalTicks = AnticheatConfig.opLockSweepIntervalTicks;
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
        if (mode == OpLock.Mode.OFF) {
            return;
        }
        final boolean mayRunOpCommands = player.isOp()
            || player.hasPermission("minecraft.command.op") || player.hasPermission("minecraft.command.deop");
        final OpLock.CommandVerdict verdict = OpLock.commandVerdict(mode, player.isOp(), mayRunOpCommands,
            player.getUniqueId(), allowed, reachesOpCommand(player, line));
        if (!verdict.journals()) {
            return;
        }
        // Cancel first: an exception in report() or setOp() must never let the command run.
        if (verdict.cancel()) {
            event.setCancelled(true);
        }
        // Observe: one generic line per player and window, so a standing op outside the list typing all evening
        // does not flood the log and the database. /op and /deop and every enforce refusal are always traced.
        final ObserveThrottle.Decision decision = observeThrottle.onCommand(player.getUniqueId(), verdict);
        if (!decision.report()) {
            return;
        }
        final String silenced = decision.suppressed() > 0
            ? ", " + decision.suppressed() + " more command(s) not logged since the previous line" : "";
        final String what = verdict.reason() == OpLock.Reason.OP_OUTSIDE_LIST ? "command by an op outside the allowlist" : "op command";
        report(player.getUniqueId(), player.getName(), what + (verdict.cancel() ? " cancelled: " : " observed: ")
            + line.strip() + (verdict.revokeOp() ? ", op removed" : "") + silenced + " (source=command)");
        if (verdict.revokeOp()) {
            player.setOp(false);
        }
        if (verdict.cancel()) {
            player.sendMessage(PlayerTexts.shared().render(player.getUniqueId(), PlayerText.ADMIN_OP_REFUSED, Map.of()));
        }
    }

    /**
     * Whether the line reaches {@code /op} or {@code /deop} as the server's command tree reads it ({@code /return run
     * op}, {@code /execute ... run deop}, aliases), else as the text reads it. Parsing only reads the tree and
     * resolves nothing in the world: Paper's asynchronous tab completion parses the same way off the main thread.
     */
    private boolean reachesOpCommand(final Player player, final String line) {
        try {
            return OpLock.resolvedOpCommand(MinecraftServer.getServer().getCommands().getDispatcher(),
                    ((CraftPlayer) player).getHandle().createCommandSourceStack(), line)
                .orElseGet(() -> OpLock.isOpCommand(line));
        } catch (RuntimeException parseFailure) {
            // A plugin's argument type may throw while parsing: the text reading still decides, the check never fails open.
            log.warn("[OpLock] the command tree could not read '{}', judged by its text: {}", line.strip(), parseFailure.toString());
            return OpLock.isOpCommand(line);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        // Commands silenced since the last line are not lost with the player: one closing line carries the count.
        final int silenced = observeThrottle.forget(player.getUniqueId());
        if (silenced > 0) {
            report(player.getUniqueId(), player.getName(), "command by an op outside the allowlist, " + silenced
                + " command(s) not logged since the previous line (source=command, player left)");
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
