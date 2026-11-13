package dev.btc.core.world;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The worlds loaded, as the load and unload events tell them, readable from any thread.
 *
 * <p>A world leaves at its {@code WorldUnloadEvent}, before the server closes its chunk system and
 * drops it from {@code Bukkit.getWorlds()}: from then on nothing should be added to it, and a task
 * that still finds it in Bukkit is running during its unload. {@code Bukkit.getWorld(UUID)} walks a
 * map that is not thread-safe; this set is.
 */
public class LoadedWorlds {

    private static final LoadedWorlds SHARED = new LoadedWorlds();

    private final Set<UUID> ids = ConcurrentHashMap.newKeySet();

    /** Fed by the BTCCore plugin: worlds loaded before it, then its load and unload events. */
    public static LoadedWorlds shared() {
        return SHARED;
    }

    public void loaded(UUID world) {
        this.ids.add(world);
    }

    /** The {@code WorldUnloadEvent} of this world went through: it is leaving. */
    public void unloading(UUID world) {
        this.ids.remove(world);
    }

    /** Overridden by the tests that need an unload to slip in. */
    public boolean contains(UUID world) {
        return this.ids.contains(world);
    }
}
