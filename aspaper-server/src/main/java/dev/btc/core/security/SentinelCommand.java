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

    public SentinelCommand() {
        super("sentinel");
        this.setDescription("Native Sentinel Anticheat Admin Tools");
        this.setUsage("/sentinel <check|notify>");
        this.setPermission("sentinel.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.DARK_RED + "[Sentinel] " + ChatColor.RED + "Usage: /sentinel <check|notify> [player]");
            return true;
        }

        String subCommand = args[0].toLowerCase();
        
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

    public static boolean shouldReceiveAlerts(Player player) {
        if (!player.hasPermission("sentinel.admin")) return false;
        if (notifyOverrides.containsKey(player.getUniqueId())) {
            return notifyOverrides.get(player.getUniqueId());
        }
        return dev.btc.core.config.BTCCoreConfig.sentinelAutoNotifyAdmins;
    }
}

