package dev.btc.core.security;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SentinelCommand extends Command {

    // Cache of players who explicitly enabled/disabled notifications overriding the default
    public static final ConcurrentHashMap<UUID, Boolean> notifyOverrides = new ConcurrentHashMap<>();

    /**
     * Staff receiving every violation, not just the ones that crossed a threshold.
     *
     * <p>Opt-in and per-session. Verbose output is unreadable for anyone not actively investigating,
     * and a staff member left subscribed after a debugging session stops reading alerts entirely —
     * which is worse than not having the feature.
     */
    public static final java.util.Set<UUID> verboseSubscribers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether this player asked to see every violation. */
    public static boolean isVerboseSubscriber(UUID player) {
        return verboseSubscribers.contains(player);
    }

    public SentinelCommand() {
        super("sentinel");
        this.setDescription("Native Sentinel Anticheat Admin Tools");
        this.setUsage("/sentinel <check|notify|verbose|profile|exempt|status|reload|panic>");
        this.setPermission("sentinel.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Usage: /sentinel <check|notify|verbose|profile|exempt|status|reload|panic> [args]");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("status")) {
            sendStatus(sender);
            return true;
        }

        if (subCommand.equals("verbose")) {
            if (!requirePermission(sender, "sentinel.verbose")) return true;
            handleVerbose(sender);
            return true;
        }

        if (subCommand.equals("profile")) {
            if (!requirePermission(sender, "sentinel.profile")) return true;
            handleProfile(sender, args);
            return true;
        }

        if (subCommand.equals("exempt")) {
            if (!requirePermission(sender, "sentinel.exempt")) return true;
            handleExempt(sender, args);
            return true;
        }

        if (subCommand.equals("reload")) {
            if (!requirePermission(sender, "sentinel.reload")) return true;
            handleReload(sender);
            return true;
        }

        if (subCommand.equals("panic")) {
            // Guarded by its own node: suspending every automatic response is a bigger decision than
            // reading a violation log, and the two should not be held by the same people by default.
            if (!sender.hasPermission("sentinel.panic")) {
                sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                        + "You do not have permission to engage or release panic.");
                return true;
            }
            handlePanic(sender, args);
            return true;
        }

        if (subCommand.equals("notify")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Consoles always receive standard warnings.");
                return true;
            }
            
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            
            boolean currentPref = shouldReceiveAlerts(player);
            boolean newPref = !currentPref;
            notifyOverrides.put(uuid, newPref);
            
            if (newPref) {
                player.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GREEN + "Real-time alerts ENABLED.");
            } else {
                player.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Real-time alerts DISABLED.");
            }
            return true;
        } 
        else if (subCommand.equals("check")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Usage: /sentinel check <player>");
                return true;
            }
            
            String targetName = args[1];
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.YELLOW + "Fetching async logs for " + targetName + " from Database...");
            
            // Asynchronous query so we don't freeze the main thread
            NativeAnticheatDB.fetchRecentViolationsAsync(targetName, (logs) -> {
                if (logs.isEmpty()) {
                    sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GREEN + targetName + " has no logged violations.");
                } else {
                    sender.sendMessage(ChatColor.DARK_RED + "--- Sentinel Violations for " + ChatColor.GOLD + targetName + ChatColor.DARK_RED + " ---");
                    for (String log : logs) {
                        sender.sendMessage(ChatColor.GRAY + log);
                    }
                }
            });
            return true;
        }

        sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Unknown argument.");
        return true;
    }

    /**
     * Checks a per-subcommand node.
     *
     * <p>{@code sentinel.admin} guards the command as a whole; each subcommand carries its own node on
     * top, so that reading a violation log and suspending the entire engine are not the same
     * authority by default.
     */
    private static boolean requirePermission(CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                + "You do not have permission (" + node + ").");
        return false;
    }

    /** Toggles detailed per-violation output for one staff member. */
    private void handleVerbose(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The console already receives every violation.");
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean enabled = !verboseSubscribers.contains(uuid);
        if (enabled) {
            verboseSubscribers.add(uuid);
        } else {
            verboseSubscribers.remove(uuid);
        }
        player.sendMessage(ChatColor.DARK_RED + "[Sentinel] "
                + (enabled ? ChatColor.GREEN + "Verbose output ENABLED." : ChatColor.RED + "Verbose output DISABLED."));
    }

    /** Everything the platform currently believes about one player. */
    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Usage: /sentinel profile <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                    + args[1] + " is not connected. A profile describes live state, so there is nothing to show.");
            return;
        }
        UUID uuid = target.getUniqueId();

        sender.sendMessage(ChatColor.DARK_RED + "── Profile: " + ChatColor.GOLD + target.getName() + ChatColor.DARK_RED + " ──");

        // Violation levels, only for checks that actually carry one: a wall of zeroes hides the
        // one line that matters.
        var checks = dev.btc.core.integrity.IntegrityAPIImpl.checks();
        boolean anyLevel = false;
        for (var checkId : checks.registered()) {
            double level = checks.violationLevel(uuid, checkId);
            if (level > 0) {
                anyLevel = true;
                sender.sendMessage(ChatColor.GRAY + "  " + checkId + ": " + ChatColor.YELLOW + String.format("%.2f", level));
            }
        }
        if (!anyLevel) {
            sender.sendMessage(ChatColor.GRAY + "  no violation level on any of the "
                    + checks.registered().size() + " registered check(s)");
        }

        // Active exemptions, per group, with the reason — the first thing to look at when a check
        // "does nothing" on a player.
        var exemptions = dev.btc.core.integrity.IntegrityAPIImpl.exemptions();
        boolean anyExemption = false;
        for (var group : dev.btc.core.api.integrity.IntegrityAPI.CheckGroup.values()) {
            var reason = exemptions.activeReason(uuid, group);
            if (reason != null) {
                anyExemption = true;
                sender.sendMessage(ChatColor.GRAY + "  exempt " + group + ": " + ChatColor.AQUA + reason);
            }
        }
        if (!anyExemption) {
            sender.sendMessage(ChatColor.GRAY + "  no active exemption");
        }

        // Standing sanctions, read from the cache so this never blocks on the database.
        var sanctions = dev.btc.core.integrity.sanction.SanctionService.active(uuid);
        if (sanctions.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  no standing sanction");
        } else {
            long now = System.currentTimeMillis();
            for (var sanction : sanctions) {
                sender.sendMessage(ChatColor.GRAY + "  " + sanction.type() + " ("
                        + dev.btc.core.integrity.sanction.SanctionDuration.describeRemaining(now, sanction.expiresAtMillis())
                        + "): " + ChatColor.WHITE + sanction.reason());
            }
        }
    }

    /**
     * Grants a temporary exemption by hand.
     *
     * <p>A diagnostic tool, not a moderation one: it is how staff answer "is this check the reason
     * this player cannot play" in a few seconds rather than by editing a config and restarting. The
     * registry's own TTL ceiling still applies, so this cannot leave a permanent hole.
     */
    private void handleExempt(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                    + "Usage: /sentinel exempt <player> <" + groupNames() + "> <seconds>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + args[1] + " is not connected.");
            return;
        }

        dev.btc.core.api.integrity.IntegrityAPI.CheckGroup group;
        try {
            group = dev.btc.core.api.integrity.IntegrityAPI.CheckGroup.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException unknown) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                    + "Unknown group '" + args[2] + "'. Use one of: " + groupNames());
            return;
        }

        long seconds;
        try {
            seconds = Long.parseLong(args[3]);
        } catch (NumberFormatException notANumber) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "'" + args[3] + "' is not a number of seconds.");
            return;
        }

        org.bukkit.plugin.Plugin host = Bukkit.getPluginManager().getPlugin("ASPaper");
        if (host == null) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Host plugin absent; cannot own an exemption.");
            return;
        }

        try {
            dev.btc.core.integrity.IntegrityAPIImpl.exemptions().grant(
                    host, target.getUniqueId(),
                    new dev.btc.core.api.integrity.IntegrityAPI.ExemptionScope(
                            group, dev.btc.core.api.integrity.IntegrityAPI.ExemptionReason.ADMIN_OVERRIDE),
                    java.time.Duration.ofSeconds(seconds));
        } catch (IllegalArgumentException refused) {
            // The registry refuses a TTL beyond its ceiling. Report why rather than swallowing it:
            // a staff member who thinks they granted an hour and got nothing is worse off than one
            // who is told the ceiling.
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + refused.getMessage());
            return;
        }

        sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GREEN
                + group + " exempted for " + target.getName() + " for " + seconds + "s.");
        broadcastToStaff(ChatColor.YELLOW + sender.getName() + " exempted " + target.getName()
                + " from " + group + " for " + seconds + "s (diagnostic)");
    }

    private static String groupNames() {
        return java.util.Arrays.stream(dev.btc.core.api.integrity.IntegrityAPI.CheckGroup.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    /**
     * Re-reads {@code anticheat.yml}.
     *
     * <p>Reloads settings only. It deliberately does not rebuild the database connection, the bus or
     * the retention schedule: those hold live resources, and silently recreating them on a reload is
     * how a server ends up with two subscribers and one leaked connection.
     */
    private void handleReload(CommandSender sender) {
        try {
            dev.btc.core.config.AnticheatConfig.init(null);
        } catch (RuntimeException failure) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                    + "Reload FAILED, the previous settings are still in force: " + failure.getMessage());
            return;
        }
        sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.GREEN + "anticheat.yml reloaded.");
        sender.sendMessage(ChatColor.GRAY + "Storage, network bus and retention schedule are NOT rebuilt by a "
                + "reload; changing those requires a restart.");
        broadcastToStaff(ChatColor.YELLOW + sender.getName() + " reloaded anticheat.yml");
    }

    /**
     * Engages or releases the panic switch.
     *
     * <p>Engaging requires a reason. Staff arriving later need to know why nothing is reacting, and a
     * panic with no stated cause is one nobody dares lift.
     */
    private void handlePanic(CommandSender sender, String[] args) {
        String who = sender.getName();

        if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            if (!dev.btc.core.integrity.PanicSwitch.engaged()) {
                sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.YELLOW
                        + "Panic is not engaged.");
                return;
            }
            dev.btc.core.integrity.PanicSwitch.release(who);
            broadcastToStaff(ChatColor.GREEN + "Panic released by " + who + "; responses are armed again.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED
                    + "Usage: /sentinel panic <reason...>  |  /sentinel panic off");
            return;
        }

        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        dev.btc.core.integrity.PanicSwitch.engage(who, reason);
        broadcastToStaff(ChatColor.RED + "PANIC engaged by " + who + ": " + reason
                + ChatColor.GRAY + " (detection and logging continue; nothing acts)");
    }

    /** A short state summary: what is armed, what is suspended. */
    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_RED + "── Sentinel status ──");
        sender.sendMessage(ChatColor.GRAY + "Engine: " + state(dev.btc.core.config.AnticheatConfig.sentinelEnabled));
        sender.sendMessage(ChatColor.GRAY + "Reach check: " + state(dev.btc.core.config.AnticheatConfig.reachCheckEnabled));
        sender.sendMessage(ChatColor.GRAY + "Velocity check: " + state(dev.btc.core.config.AnticheatConfig.velocityCheckEnabled));
        sender.sendMessage(ChatColor.GRAY + "Violation journal: " + state(NativeAnticheatDB.isEnabled()));
        sender.sendMessage(ChatColor.GRAY + "Sanction store: "
                + state(dev.btc.core.integrity.sanction.SanctionService.isReady()));
        // The seam and what sits on it. "observing" means the stage-1 engine sees packets; the
        // checks listed are the ones it registered, all in observation until phase 4 arms them.
        sender.sendMessage(ChatColor.GRAY + "Engine seam: "
                + (dev.btc.core.integrity.SentinelHooks.observing()
                        ? ChatColor.GREEN + "observing" : ChatColor.RED + "nothing installed"));
        sender.sendMessage(ChatColor.GRAY + "Registered checks: " + ChatColor.WHITE
                + dev.btc.core.integrity.IntegrityAPIImpl.checks().registered().stream()
                        .map(Object::toString).sorted().collect(java.util.stream.Collectors.joining(", ")));

        String panic = dev.btc.core.integrity.PanicSwitch.describe()
                .map(description -> ChatColor.RED + description)
                .orElse(ChatColor.GREEN + "responses armed");
        sender.sendMessage(ChatColor.GRAY + "Panic: " + panic);
    }

    private static String state(boolean on) {
        return on ? ChatColor.GREEN + "on" : ChatColor.RED + "off";
    }

    /** Tells every staff member who can see alerts. A panic that only its author knows about is a trap. */
    private static void broadcastToStaff(String message) {
        String line = ChatColor.DARK_RED + "[Sentinel] " + message;
        Bukkit.getConsoleSender().sendMessage(line);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("sentinel.admin")) {
                player.sendMessage(line);
            }
        }
    }

    public static boolean shouldReceiveAlerts(Player player) {
        if (!player.hasPermission("sentinel.admin")) return false;
        if (notifyOverrides.containsKey(player.getUniqueId())) {
            return notifyOverrides.get(player.getUniqueId());
        }
        return dev.btc.core.config.AnticheatConfig.autoNotifyAdmins;
    }
}

