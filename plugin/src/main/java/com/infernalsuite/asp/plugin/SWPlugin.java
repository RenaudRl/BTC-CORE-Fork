package com.infernalsuite.asp.plugin;

import com.infernalsuite.asp.plugin.commands.CommandManager;
import com.infernalsuite.asp.plugin.config.ConfigManager;
import com.infernalsuite.asp.plugin.config.WorldData;
import com.infernalsuite.asp.plugin.config.WorldsConfig;
import com.infernalsuite.asp.plugin.loader.LoaderManager;
import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.SlimeNMSBridge;
import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.security.AsyncPacketValidator;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.qol.MaintenanceModeManager;
import dev.btc.core.security.ExploitLogger;
import dev.btc.core.world.BlockValueCache;
import dev.btc.core.visual.BTCCoreVisualAPIImpl;
import com.infernalsuite.asp.plugin.commands.BTCCoreDebugCommand;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.HandlerList;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class SWPlugin extends JavaPlugin {

    private static final AdvancedSlimePaperAPI ASP = AdvancedSlimePaperAPI.instance();
    private static final int BSTATS_ID = 5419;

    private final Map<String, SlimeWorld> worldsToLoad = new HashMap<>();
    private LoaderManager loaderManager;
    // Held so onDisable can unregister it; null when MiniPlaceholders is not installed.
    private io.github.miniplaceholders.api.Expansion btcCoreExpansion;
    // The BTCVelocity backend bridge, formerly the separate BTCBridge plugin. Never null once
    // enabled, but only actually open when this server sits behind a proxy — see BridgeService.
    private dev.btc.core.bridge.BridgeService bridgeService;

    public static SWPlugin getInstance() {
        return SWPlugin.getPlugin(SWPlugin.class);
    }

    public LoaderManager getLoaderManager() {
        return loaderManager;
    }

    @Override
    public void onLoad() {
        // First thing, before any dev.btc.core.* call: a mismatched artifact pair has to be
        // reported here, not as a NoSuchMethodError mid-tick. Deliberately not caught.
        BTCCoreContractCheck.verify(getSLF4JLogger());

        try {
            ConfigManager.initialize();
        } catch (NullPointerException | IOException ex) {
            getSLF4JLogger().error("Failed to load config files", ex);
            return;
        }

        // BTCCore initialization
        try {
            // btccore.yml itself is already read by net.minecraft.server.Main before the world
            // loader runs; this only applies the parts that need a live Bukkit server.
            // anticheat.yml first: it owns the integrity settings, and applyServerBound() reads
            // AnticheatConfig.sentinelEnabled to decide whether to register /sentinel. Reversing
            // these two lines makes that read see a default instead of the operator's value —
            // a command that silently stops registering.
            dev.btc.core.config.AnticheatConfig.init(null);
            BTCCoreConfig.applyServerBound();
            org.purpurmc.purpur.PurpurConfig.init();
            // Generate/load config/BTCCore/slimeworld-config.yml at startup (default GameRules per world/pattern)
            dev.btc.core.config.SlimeWorldConfig.getInstance();
            AsyncPacketValidator.init();
            getSLF4JLogger().info("BTC Core modules initialized");
        } catch (Exception ex) {
            getSLF4JLogger().error("Failed to initialize BTC Core modules", ex);
        }

        this.loaderManager = new LoaderManager();

        List<String> erroredWorlds = loadWorlds();

        // Default world override
        try {
            Properties props = new Properties();

            props.load(new FileInputStream("server.properties"));
            String defaultWorldName = props.getProperty("level-name");

            if (erroredWorlds.contains(defaultWorldName)) {
                getSLF4JLogger().error("Shutting down server, as the default world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowNether() && erroredWorlds.contains(defaultWorldName + "_nether")) {
                getSLF4JLogger().error("Shutting down server, as the default nether world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowEnd() && erroredWorlds.contains(defaultWorldName + "_the_end")) {
                getSLF4JLogger().error("Shutting down server, as the default end world could not be loaded.");
                Bukkit.getServer().shutdown();
            }

            SlimeWorld defaultWorld = worldsToLoad.get(defaultWorldName);
            SlimeWorld netherWorld = getServer().getAllowNether() ? worldsToLoad.get(defaultWorldName + "_nether") : null;
            SlimeWorld endWorld = getServer().getAllowEnd() ? worldsToLoad.get(defaultWorldName + "_the_end") : null;

            SlimeNMSBridge.instance().setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            getSLF4JLogger().error("Failed to retrieve default world name", ex);
        }
    }

    @Override
    public void onEnable() {
        Metrics metrics = new Metrics(this, BSTATS_ID);

        CommandManager commandManager = new CommandManager(this);

        // Register BTC Core event listener
        getServer().getPluginManager().registerEvents(new BTCCoreListener(), this);
        getServer().getPluginManager().registerEvents(new SanctionListener(), this);

        // The default worlds were created before that listener existed, so they never fire a
        // WorldLoadEvent we could hear — apply the per-world distances to them by hand.
        dev.btc.core.performance.PerWorldDistanceManager.applyToLoadedWorlds();

        // Initialize QoL systems
        MaintenanceModeManager.init();
        dev.btc.core.qol.VanishManager.init(this);
        ExploitLogger.init();
        dev.btc.core.world.BlockValueCache.init();

        // Initialize Visual API
        dev.btc.core.visual.BTCCoreVisualAPIImpl.init();

        // Register BTC-CORE MiniPlaceholders expansion (soft dependency — only when MiniPlaceholders is installed)
        if (getServer().getPluginManager().getPlugin("MiniPlaceholders") != null) {
            btcCoreExpansion = dev.btc.core.placeholder.BTCCoreExpansion.create();
            btcCoreExpansion.register();
        }

        // Initialize async thread pools
        dev.btc.core.async.AsyncEntityTracker.init();
        dev.btc.core.async.AsyncPathfindingEngine.init();

        // The worlds come first, before anything the integrity platform does. On 2026-09-15 a
        // paperclip built from this tree read its eleven slime worlds and registered none of them
        // as Bukkit worlds: this block used to sit at the very end of onEnable, behind the
        // integrity start-up, and one Throwable there was enough to leave every world unregistered.
        // A protection that cannot start must never take the worlds with it.
        registerLoadedWorlds();

        // Everything the integrity platform starts is fenced: a failure is logged in full and
        // the server keeps enabling without it. The platform then refuses to act (isReady() is
        // false), which is the safe direction — no sanction is issued, no world is lost.
        try {
            startIntegrityPlatform();
        } catch (Throwable failure) {
            getSLF4JLogger().error("[Sentinel] the integrity platform could not start; the server runs "
                    + "without sanctions and without the violation journal until this is fixed.", failure);
        }

        // Register /btccore debug command via the Paper Brigadier API.
        // Paper plugins cannot declare commands in paper-plugin.yml nor use JavaPlugin#getCommand.
        this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> event.registrar().register(
                        "btccore",
                        "BTC Core debug command (feature status)",
                        new BTCCoreDebugCommand()
                )
        );

        // Moderation commands. Registered in one pass from a single list so that adding a verb cannot
        // leave it declared but unregistered — the failure mode of eight hand-written registrations.
        this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> com.infernalsuite.asp.plugin.commands.SanctionCommands.all().forEach(
                        registration -> event.registrar().register(
                                registration.label(),
                                "BTC moderation: /" + registration.label(),
                                registration.command()
                        )
                )
        );

        // Started after the rest of BTCCore so a health report never describes a half-built server.
        bridgeService = new dev.btc.core.bridge.BridgeService(this);
        bridgeService.start();
    }

    /** Registers as Bukkit worlds every slime world read in onLoad that is not already one. */
    private void registerLoadedWorlds() {
        worldsToLoad.values().stream()
                .filter(slimeWorld -> Objects.isNull(Bukkit.getWorld(slimeWorld.getName())))
                .forEach(slimeWorld -> {
                    try {
                        ASP.loadWorld(slimeWorld, true);
                    } catch (RuntimeException exception) {
                        getSLF4JLogger().error("Failed to load world: {}", slimeWorld.getName(), exception);
                    }
                });

        worldsToLoad.clear(); // Don't unnecessarily hog up memory
    }

    /**
     * Starts persistence, journal, moderation and the network bus of the integrity platform.
     * Called inside a fence in {@link #onEnable()}: nothing here may prevent the server from enabling.
     */
    private void startIntegrityPlatform() {
        // Persistence for the whole integrity platform. configure() only reads anticheat.yml
        // (storage.*) — it opens no connection, so a database that is down cannot delay startup.
        // It must run before the two users below, which both ask it whether it is enabled.
        dev.btc.core.integrity.IntegrityDatabase.configure();

        // Open the violation journal if configured. Settings live in anticheat.yml (storage.*),
        // alongside the checks that produce the violations; init() is a no-op when disabled.
        NativeAnticheatDB.init();

        // Moderation history and enforcement. Schema creation is asynchronous: sanctions become
        // issuable once it completes, and stay refused with a clear message until then.
        dev.btc.core.integrity.sanction.SanctionService.start();

        // Cross-server propagation, when the network has more than one node. Off is a normal state,
        // not a degraded one: a single server has nobody to tell.
        com.infernalsuite.asp.plugin.sanction.ValkeySanctionBus.connect(this)
                .ifPresent(dev.btc.core.integrity.sanction.SanctionBus.Holder::install);

        // Stage 1 of the engine, in observation: installed last so that everything it journals into
        // and publishes on exists. It acts on nothing — its checks are registered with an observing
        // model, and the seam it sits on uninstalls it at the first exception it lets through.
        integrityEngine = dev.btc.core.integrity.engine.SentinelEngine.install(
                this,
                dev.btc.core.integrity.IntegrityAPIImpl.checks(),
                dev.btc.core.integrity.IntegrityAPIImpl.exemptions(),
                new dev.btc.core.integrity.engine.BukkitServerAdapter(
                        dev.btc.core.integrity.IntegrityAPIImpl.declarations(),
                        dev.btc.core.integrity.IntegrityAPIImpl.violations()));
    }

    /** Uninstalls the observing engine and its checks; {@code null} until the platform has started. */
    private AutoCloseable integrityEngine;

    @Override
    public void onDisable() {
        // Closed first: the worlds are about to be unloaded, and a health report sent mid-unload
        // would tell the proxy this backend can still take players.
        if (bridgeService != null) {
            bridgeService.stop();
            bridgeService = null;
        }

        if (btcCoreExpansion != null && btcCoreExpansion.registered()) {
            btcCoreExpansion.unregister();
        }

        // The engine leaves the seam first: a packet handled during shutdown must find nothing there.
        if (integrityEngine != null) {
            try {
                integrityEngine.close();
            } catch (Exception failure) {
                getSLF4JLogger().warn("[Sentinel] the integrity engine did not uninstall cleanly", failure);
            }
            integrityEngine = null;
        }

        // Released before the pools below: it holds network connections, and a subscriber left open
        // across a reload keeps delivering into a server that is no longer there.
        dev.btc.core.integrity.sanction.SanctionBus.Holder.uninstall();
        dev.btc.core.integrity.sanction.RetentionPolicy.stop();

        // Shutdown async thread pools
        dev.btc.core.async.AsyncEntityTracker.shutdown();
        dev.btc.core.async.AsyncPathfindingEngine.shutdown();

        WorldsConfig config = ConfigManager.getWorldConfig();

        for (Map.Entry<String, WorldData> entry : config.getWorlds().entrySet()) {
            SlimeWorld world = ASP.getLoadedWorld(entry.getKey());
            if(world == null) {
                continue;
            }

            if (!world.isReadOnly()) {
                try {
                    ASP.saveWorld(world); //Save the world sync
                } catch (RuntimeException | IOException ex) {
                    getLogger().log(Level.SEVERE, "Failed to save world " + world.getName(), ex);
                }
            }
            Bukkit.unloadWorld(world.getName(), false); //Unload without saving as we have just saved (if not read only)
        }
    }

    private List<String> loadWorlds() {
        List<String> erroredWorlds = new ArrayList<>();
        WorldsConfig config = ConfigManager.getWorldConfig();

        for (Map.Entry<String, WorldData> entry : config.getWorlds().entrySet()) {
            String worldName = entry.getKey();
            WorldData worldData = entry.getValue();

            if (worldData.isLoadOnStartup()) {
                try {
                    SlimeLoader loader = loaderManager.getLoader(worldData.getDataSource());

                    if (loader == null) {
                        throw new IllegalArgumentException("invalid data source " + worldData.getDataSource());
                    }

                    SlimePropertyMap propertyMap = worldData.toPropertyMap();
                    SlimeWorld world = ASP.readWorld(loader, worldName, worldData.isReadOnly(), propertyMap);

                    worldsToLoad.put(worldName, world);
                } catch (IllegalArgumentException | UnknownWorldException | NewerFormatException |
                         CorruptedWorldException | IOException ex) {
                    String message;

                    if (ex instanceof IllegalArgumentException) {
                        message = ex.getMessage();

                        //noinspection CallToPrintStackTrace
                        ex.printStackTrace();
                    } else if (ex instanceof UnknownWorldException) {
                        message = "world does not exist, are you sure you've set the correct data source?";
                    } else if (ex instanceof NewerFormatException) {
                        message = "world is serialized in a newer Slime Format version (" + ex.getMessage() + ") that this version of ASP does not understand.";
                    } else if (ex instanceof CorruptedWorldException) {
                        message = "world seems to be corrupted.";
                    } else {
                        message = "";

                        //noinspection CallToPrintStackTrace
                        ex.printStackTrace();
                    }

                    getSLF4JLogger().error("Failed to load world {}{}", worldName, message.isEmpty() ? "." : ": " + message);
                    erroredWorlds.add(worldName);
                }
            }
        }

        config.save();
        return erroredWorlds;
    }
}
