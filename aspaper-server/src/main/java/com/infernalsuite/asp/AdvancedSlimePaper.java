package com.infernalsuite.asp;

import com.infernalsuite.asp.api.SlimeNMSBridge;
import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.events.LoadSlimeWorldEvent;
import com.infernalsuite.asp.api.exceptions.*;
import com.infernalsuite.asp.api.loaders.LockableSlimeLoader;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.loaders.SlimeSerializationAdapter;
import com.infernalsuite.asp.lock.WorldLeaseKeeper;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import com.infernalsuite.asp.level.SlimeLevelInstance;
import com.infernalsuite.asp.serialization.SlimeSerializationAdapterImpl;
import com.infernalsuite.asp.serialization.anvil.AnvilImportData;
import com.infernalsuite.asp.serialization.anvil.AnvilWorldReader;
import com.infernalsuite.asp.serialization.slime.SlimeSerializer;
import com.infernalsuite.asp.serialization.slime.reader.SlimeWorldReaderRegistry;
import com.infernalsuite.asp.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.asp.util.NmsUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spigotmc.AsyncCatcher;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AdvancedSlimePaper implements AdvancedSlimePaperAPI {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedSlimePaper.class);
    private static final SlimeNMSBridge BRIDGE_INSTANCE = SlimeNMSBridge.instance();

    private final Map<String, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();

    /** BTC-CORE (AD-096): the longest the release of an unloaded world waits for its last save. */
    private static final Duration LAST_SAVE_TIMEOUT = Duration.ofSeconds(60);
    /** BTC-CORE (AD-096): the longest the exit safety net waits for the server to finish stopping. */
    private static final Duration SERVER_STOP_WAIT = Duration.ofSeconds(60);
    /** BTC-CORE (AD-096): rounds of teleports out of a world that lost its lease, before players are kicked. */
    private static final int MAX_EVACUATION_ATTEMPTS = 3;
    /** BTC-CORE (AD-096): rounds of kicks, after the teleports, before the unload is given up. */
    private static final int MAX_KICK_ATTEMPTS = 2;
    /** BTC-CORE (AD-096): ticks between two evacuation rounds (a kicked player leaves the world a tick later). */
    private static final long EVACUATION_RETRY_TICKS = 5L;
    /**
     * BTC-CORE (AD-096): default kick message (MiniMessage) for a player who could not be moved out
     * of a world that lost its lease; {@code sources.yml} overrides it.
     */
    public static final String DEFAULT_LEASE_LOST_KICK_MESSAGE =
            "<red>Ce monde n'est plus disponible sur ce serveur. Reconnecte-toi pour le retrouver.</red>";

    /** BTC-CORE (AD-096): loaded (or loading) worlds that lost their lease, until they are unloaded. */
    private final Set<String> lostWorlds = ConcurrentHashMap.newKeySet();
    private volatile Component leaseLostKickMessage = MiniMessage.miniMessage().deserialize(DEFAULT_LEASE_LOST_KICK_MESSAGE);

    private final WorldLeaseKeeper leases = new WorldLeaseKeeper(
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ASP world lease renewal").factory()),
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ASP world lease release").factory()),
            loadedWorlds::containsKey,
            this::onLeaseLost,
            LAST_SAVE_TIMEOUT);

    {
        // Safety net only: the stop sequence releases the leases through stopLeases(), and this
        // does nothing once it has. Otherwise it waits for the server thread (whose final saves
        // are synchronous) before releasing, so it cannot fence out a final save.
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> leases.shutdown(AdvancedSlimePaper::awaitServerStop), "ASP world lease release on exit"));
    }

    static {
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
    }

    private final SlimeSerializationAdapter serializationAdapter = new SlimeSerializationAdapterImpl();
    private final AnvilWorldReader reader = new AnvilWorldReader(BRIDGE_INSTANCE.getSlimeDataConverter());

    public static AdvancedSlimePaper instance() {
        return (AdvancedSlimePaper) AdvancedSlimePaperAPI.instance();
    }

    @Override
    public SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException, CorruptedWorldException, NewerFormatException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        long start = System.currentTimeMillis();

        // BTC-CORE (AD-096): the lease comes before the read, so what is read is what the previous
        // holder last saved. A world this server has loaded keeps the lease its instance writes under.
        LockableSlimeLoader lockable = !readOnly && loader instanceof LockableSlimeLoader l ? l : null;
        Long kept = lockable != null && loadedWorlds.containsKey(worldName) ? lockable.heldLocks().get(worldName) : null;
        boolean newLease = lockable != null && kept == null;
        long generation = kept != null ? kept : newLease ? leases.acquire(lockable, worldName) : 0L; // 0: no lease involved
        try {
            LOGGER.debug("Reading world {}.", worldName);
            byte[] serializedWorld = loader.readWorld(worldName);

            SlimeWorld slimeWorld = SlimeWorldReaderRegistry.readWorld(loader, worldName, serializedWorld, propertyMap, readOnly);
            LOGGER.debug("Applying datafixers for {}.", worldName);
            SlimeWorld dataFixed = SlimeNMSBridge.instance().getSlimeDataConverter().applyDataFixers(slimeWorld);

            // If the dataFixed and slimeWorld are same, then no datafixers were applied
            if (!readOnly && dataFixed != slimeWorld) {
                byte[] fixed = SlimeSerializer.serialize(dataFixed); // Write dataFixed world back to loader
                if (lockable != null) lockable.saveWorld(worldName, fixed, generation);
                else loader.saveWorld(worldName, fixed);
            }

            LOGGER.info("World {} read in {}ms.", worldName, System.currentTimeMillis() - start);

            return dataFixed;
        } catch (Throwable failure) {
            if (newLease) leases.releaseNow(lockable, worldName, generation);
            throw failure;
        }
    }

    @Override
    public SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) throws IllegalArgumentException {
        AsyncCatcher.catchOp("SWM world load");
        Objects.requireNonNull(world, "SlimeWorld cannot be null");

        if (Bukkit.getWorld(world.getName()) != null) {
            throw new IllegalArgumentException("World " + world.getName() + " is already loaded");
        }
        // BTC-CORE (AD-096): marked as loading before the lease is checked, so the renewal never gives
        // it back as unused in between; checked again once registered, in case it went anyway.
        String name = world.getName();
        LockableSlimeLoader lockable = !world.isReadOnly() && world.getLoader() instanceof LockableSlimeLoader l ? l : null;
        if (lockable != null) leases.loadStarted(name);
        SlimeWorldInstance instance = null;
        boolean registered = false;
        try {
            // A world of a shared source that this server does not lease could never be saved;
            // refusing it here beats letting players build in a world that silently loses it all.
            if (lockable != null && !lockable.heldLocks().containsKey(name)) {
                throw new IllegalArgumentException("World " + name + " is not leased by this server: read it "
                        + "with readWorld, or create it with createWorld if it is new, before loading it");
            }

            LOGGER.debug("Loading world {}...", name);
            long start = System.currentTimeMillis();

            instance = BRIDGE_INSTANCE.loadInstance(world);

            Bukkit.getPluginManager().callEvent(new LoadSlimeWorldEvent(instance));
            if (callWorldLoadEvent) {
                Bukkit.getPluginManager().callEvent(new WorldLoadEvent(instance.getBukkitWorld()));
            }

            registerWorld(instance);
            registered = true;
            if (lockable != null) leases.track(lockable);

            LOGGER.info("World {} loaded in {}ms.", name, System.currentTimeMillis() - start);
            return instance;
        } finally {
            if (lockable != null) {
                leases.loadFinished(lockable, name, registered ? lockGenerationOf(instance) : null, registered);
                if (!registered) lostWorlds.remove(name);
            }
        }
    }

    private static Long lockGenerationOf(SlimeWorldInstance instance) {
        return ((CraftWorld) instance.getBukkitWorld()).getHandle() instanceof SlimeLevelInstance level
                ? level.lockGeneration() : null;
    }

    @Override
    public boolean worldLoaded(SlimeWorld world) {
        return loadedWorlds.containsKey(world.getName());
    }

    @Override
    public void saveWorld(SlimeWorld world) throws IOException {
        Objects.requireNonNull(world, "SlimeWorld cannot be null");
        if (worldLoaded(world)) {
            Future<?>[] future = new Future[1];

            // This is not pretty, but we really need to hop onto the main thread
            NmsUtil.runSyncAndWait(() -> {
                World bukkitWorld = Bukkit.getWorld(world.getName());

                ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
                if (level instanceof SlimeLevelInstance slimeLevel) {
                    future[0] = slimeLevel.save();
                } else {
                    // Shouldn't happen
                    LOGGER.warn("ServerLevel based off of SlimeWorld is not an instance of SlimeLevelInstance. Falling back to default save method.");
                    bukkitWorld.save();
                }
            });

            if (future[0] != null) {
                // BTC-CORE (AD-096): bounded, so a source that stopped answering cannot hang the caller.
                SlimeWorldInstance live = loadedWorlds.get(world.getName());
                Duration timeout = live != null && live.getLoader() instanceof LockableSlimeLoader lockable
                        ? lockable.lockLease() : LAST_SAVE_TIMEOUT;
                try {
                    future[0].get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    throw new RuntimeException(exception);
                } catch (TimeoutException timedOut) {
                    throw new IOException("World " + world.getName() + " was not saved within " + timeout, timedOut);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException ioException) {
                        throw ioException;
                    } else {
                        throw new RuntimeException(e.getCause());
                    }
                }
            }
        } else {
            // BTC-CORE (AD-096): a copy that is not the loaded world could land over a newer version,
            // or bring a deleted world back; on a shared source, only createWorld writes a new one.
            if (world.getLoader() instanceof LockableSlimeLoader) {
                throw new WorldLockedException(world.getName(), "World " + world.getName() + " is not loaded on this "
                        + "server: saving a copy of a world of a shared source is refused. Save the loaded world, or "
                        + "create a new one with createWorld.");
            }
            LOGGER.info("Saving unloaded world {}...", world.getName());
            Objects.requireNonNull(world.getLoader(), "World loader cannot be null");
            long start = System.currentTimeMillis();

            byte[] serializedWorld = SlimeSerializer.serialize(world);

            long saveStart = System.currentTimeMillis();
            world.getLoader().saveWorld(world.getName(), serializedWorld);

            LOGGER.info("World {} serialized in {}ms and saved in {}ms.", world.getName(), saveStart - start, System.currentTimeMillis() - saveStart);
        }

    }

    @Override
    public void createWorld(SlimeLoader loader, SlimeWorld world) throws WorldAlreadyExistsException, IOException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(world, "SlimeWorld cannot be null");
        if (world.getLoader() != loader) {
            throw new IllegalArgumentException("World " + world.getName() + " must use the loader it is created in, "
                    + "or the loaded world would save elsewhere");
        }
        byte[] serializedWorld = SlimeSerializer.serialize(world);
        if (loader instanceof LockableSlimeLoader lockable) {
            leases.create(lockable, world.getName(), serializedWorld);
            return;
        }
        if (loader.worldExists(world.getName())) {
            throw new WorldAlreadyExistsException(world.getName());
        }
        loader.saveWorld(world.getName(), serializedWorld);
    }

    @Override
    public boolean isLeaseLost(String worldName) {
        return lostWorlds.contains(worldName);
    }

    @Override
    public SlimeWorldInstance getLoadedWorld(String worldName) {
        return loadedWorlds.get(worldName);
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return List.copyOf(loadedWorlds.values());
    }

    @Override
    public SlimeWorld createEmptyWorld(String worldName, boolean readOnly, SlimePropertyMap propertyMap, SlimeLoader loader) {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        return new SkeletonSlimeWorld(worldName, loader, readOnly, new Long2ObjectOpenHashMap<>(0), new ConcurrentHashMap<>(), propertyMap, BRIDGE_INSTANCE.getCurrentVersion());
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader) throws IOException, WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        byte[] serializedWorld = currentLoader.readWorld(worldName);
        if (newLoader instanceof LockableSlimeLoader lockable) {
            // BTC-CORE (AD-096): created leased, then given back at once: nothing loads it here.
            leases.releaseNow(lockable, worldName, leases.create(lockable, worldName, serializedWorld));
        } else {
            newLoader.saveWorld(worldName, serializedWorld);
        }
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeWorld readVanillaWorld(File worldDir, String worldName, SlimeLoader loader) throws InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException, WorldAlreadyExistsException {
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");

        if (loader != null && loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldName);

        if (bukkitWorld != null && BRIDGE_INSTANCE.getInstance(bukkitWorld) == null) {
            throw new WorldLoadedException(worldDir.getName());
        }

        SlimeWorld world;

        try {
            world = this.reader.readFromData(AnvilImportData.legacy(worldDir, worldName, loader));
        } catch (RuntimeException e) {
            if (e.getCause() == null) {
                throw e;
            }
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            } else if (e.getCause() instanceof InvalidWorldException invalidWorldException) {
                throw invalidWorldException;
            } else {
                throw e;
            }
        }

        // A sanity check to make sure the world is not too big to be serialized
        try {
            SlimeSerializer.serialize(world);
        } catch (IndexOutOfBoundsException ex) {
            throw new WorldTooBigException(worldDir.getName());
        }

        return world;
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        return this.serializationAdapter;
    }

    /**
     * Utility method to register a <b>loaded</b> {@link SlimeWorld} with the internal map (for {@link #getLoadedWorld} calls)
     *
     * @param world the world to register
     */
    private void registerWorld(SlimeWorldInstance world) {
        this.loadedWorlds.put(world.getName(), world);
    }

    public void onWorldUnload(String name) {
        this.loadedWorlds.remove(name);
        this.lostWorlds.remove(name); // BTC-CORE (AD-096): lost until unloaded
    }

    /**
     * BTC-CORE (AD-096): the kick message (MiniMessage) for players who could not be moved out of a
     * world that lost its lease. Set from {@code sources.yml}; a blank value keeps the default.
     */
    public void setLeaseLostKickMessage(String miniMessage) {
        if (miniMessage == null || miniMessage.isBlank()) return;
        this.leaseLostKickMessage = MiniMessage.miniMessage().deserialize(miniMessage);
    }

    /**
     * BTC-CORE (AD-096): a Slime level was closed by {@code CraftServer.unloadWorld}, after its
     * unload save (if any) was submitted. Its lease is released once that last save has completed.
     */
    public void onWorldClosed(SlimeLevelInstance level) {
        String name = level.getSlimeInstance().getName();
        Long generation = level.lockGeneration();
        if (generation == null || !(level.getSlimeInstance().getLoader() instanceof LockableSlimeLoader lockable)) return;
        if (!generation.equals(lockable.heldLocks().get(name))) return; // lost, or already released
        leases.releaseAfter(lockable, name, generation, level.pendingSave());
    }

    /** Whether the leases were released at shutdown: later saves of leased worlds are skipped. */
    public boolean leasesStopped() {
        return leases.isStopped();
    }

    /**
     * BTC-CORE (AD-096): ends the leases at a stop — renewals first, then a final save of every
     * leased world still loaded, then one release per source. Called from the plugin's
     * {@code onDisable}, on the main thread; a {@code /reload} also disables plugins, but keeps the
     * worlds, so it does nothing unless the server is stopping.
     */
    public void stopLeases() {
        if (!Bukkit.isStopping()) return;
        leases.shutdown(this::saveLeasedWorlds);
    }

    private void saveLeasedWorlds() {
        for (SlimeWorldInstance world : loadedWorlds.values()) {
            if (world.isReadOnly() || !(world.getLoader() instanceof LockableSlimeLoader)) continue;
            try {
                saveWorld(world);
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("World {} could not be saved before its lease is released at shutdown", world.getName(), failure);
            }
        }
    }

    private static void awaitServerStop() {
        MinecraftServer server = MinecraftServer.getServer();
        Thread serverThread = server == null ? null : server.getRunningThread();
        if (serverThread == null || serverThread == Thread.currentThread()) return;
        try {
            serverThread.join(SERVER_STOP_WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * BTC-CORE (AD-096): runs on the renewal thread (or the main thread, from a load that ends
     * without its lease). The level refuses to save from now on; players leave for a safe world's
     * spawn, or are kicked, and the world is unloaded without saving. Idempotent: a world already
     * being evacuated, or neither loaded nor loading, is left alone.
     */
    private void onLeaseLost(String worldName) {
        if (!loadedWorlds.containsKey(worldName) && !leases.isLoading(worldName)) return; // nothing to evacuate
        if (!lostWorlds.add(worldName)) return;
        LOGGER.warn("World {} lost its lease: another server may hold it now. Its saves are refused; players are "
                + "moved out (or kicked) and it is unloaded without saving.", worldName);
        Bukkit.getGlobalRegionScheduler().execute(new InternalPlugin(), () -> evacuateAndUnload(worldName, 1));
    }

    /**
     * One evacuation round, on the global region thread. Rounds 1 to {@value #MAX_EVACUATION_ATTEMPTS}
     * teleport the remaining players (each on its own entity scheduler) to a safe world, or kick
     * them when there is none; the next {@value #MAX_KICK_ATTEMPTS} rounds kick whoever is left. The
     * world is unloaded without saving as soon as it is empty; past the last round, it is given up.
     */
    private void evacuateAndUnload(String worldName, int round) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            // Not created yet (the loss came while it was loading): the load reports it again once registered.
            if (!loadedWorlds.containsKey(worldName)) lostWorlds.remove(worldName);
            return;
        }
        List<Player> players = world.getPlayers();
        if (players.isEmpty() && Bukkit.unloadWorld(worldName, false)) return;
        if (round > MAX_EVACUATION_ATTEMPTS + MAX_KICK_ATTEMPTS) {
            LOGGER.warn("World {} could not be unloaded after losing its lease ({} players left); its saves stay "
                    + "refused.", worldName, players.size());
            return;
        }

        InternalPlugin plugin = new InternalPlugin();
        Location fallback = round <= MAX_EVACUATION_ATTEMPTS ? fallbackLocation() : null;
        Component kickMessage = this.leaseLostKickMessage;
        List<CompletableFuture<?>> moves = new ArrayList<>();
        for (Player player : players) {
            CompletableFuture<Void> moved = new CompletableFuture<>();
            Runnable move = fallback != null
                    ? () -> player.teleportAsync(fallback).whenComplete((done, failure) -> moved.complete(null))
                    : () -> {
                        player.kick(kickMessage);
                        moved.complete(null);
                    };
            if (!player.getScheduler().execute(plugin, move, () -> moved.complete(null), 1L)) moved.complete(null);
            moves.add(moved);
        }
        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                        task -> evacuateAndUnload(worldName, round + 1), EVACUATION_RETRY_TICKS));
    }

    /**
     * Where players of a world that lost its lease go: the main world's spawn, unless the main world
     * is itself a world of a shared source or a lost one — then the first loaded world that is not a
     * Slime world. {@code null} when there is none: players are kicked.
     */
    private Location fallbackLocation() {
        List<World> worlds = Bukkit.getWorlds();
        World main = worlds.getFirst();
        SlimeWorldInstance mainSlime = loadedWorlds.get(main.getName());
        if (!lostWorlds.contains(main.getName()) && (mainSlime == null || !(mainSlime.getLoader() instanceof LockableSlimeLoader))) {
            return main.getSpawnLocation();
        }
        for (World candidate : worlds) {
            if (!loadedWorlds.containsKey(candidate.getName())) return candidate.getSpawnLocation();
        }
        return null;
    }
}
