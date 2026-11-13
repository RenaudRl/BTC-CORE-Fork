package dev.btc.core.zone;

import dev.btc.core.api.zone.Zone;
import dev.btc.core.api.zone.ZoneListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * The zones of {@code BTCCoreAPI#registerZone}: per world, a grid of columns of
 * {@value #CELL_SIDE} by {@value #CELL_SIDE} blocks, each holding the zones that reach it, so a
 * player reaching a block is tested against the zones of one column only.
 *
 * <p>Threads. Zones are registered and removed from any thread, rarely: a write takes the lock of
 * its world and replaces the arrays it changes, and readers - the threads that move players, one
 * per world under parallel world ticking, one per region under Folia - read the grid without a
 * lock. What a player stands in is kept per player and only changed under that player's lock, on
 * the thread that moves the player; the listeners are called after the lock is let go, so a
 * listener that teleports its player re-enters here safely.
 *
 * <p>Inert while no zone is registered: each entry point returns after one volatile read.
 */
public final class ZoneIndex {

    /** Side of a column of the grid, in blocks. */
    static final int CELL_SIDE = 16;
    private static final int CELL_SHIFT = 4;
    /** Past this many columns a zone is not put in the grid but tested on every move in its world. */
    static final long MAX_COLUMNS = 1024;
    private static final ZoneImpl[] NONE = new ZoneImpl[0];

    /** A listener that keeps failing is logged on its first failure, then once every this many. */
    static final int LOG_EVERY_FAILURES = 1000;

    private static final ZoneIndex SHARED = new ZoneIndex(dev.btc.core.world.LoadedWorlds.shared());

    private final ConcurrentHashMap<UUID, WorldZones> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Membership> members = new ConcurrentHashMap<>();
    private final dev.btc.core.world.LoadedWorlds loaded;
    /**
     * Plugins whose disable event went through, with the thread disabling them, until enabled again:
     * the plugin still says it is enabled until its {@code onDisable}. Weak, not to keep an unloaded plugin.
     */
    private final java.util.Map<Plugin, Thread> disabling = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private volatile int registered;

    /**
     * @param loaded the worlds loaded, as their events tell them: a zone is refused in a world not in
     *               it, and a world leaves it at its unload event ({@link #worldUnloaded}), before the
     *               server drops it - a zone registered in between would never be removed
     */
    public ZoneIndex(dev.btc.core.world.LoadedWorlds loaded) {
        this.loaded = loaded;
    }

    public static ZoneIndex shared() {
        return SHARED;
    }

    public boolean isEmpty() {
        return this.registered == 0;
    }

    // ==================== REGISTRATION ====================

    public Zone register(Plugin owner, UUID worldId, BoundingBox box, ZoneListener listener) {
        if (owner == null || worldId == null || box == null || listener == null) throw new NullPointerException("owner, world, box and listener are required");
        if (!Double.isFinite(box.getMinX() + box.getMinY() + box.getMinZ() + box.getMaxX() + box.getMaxY() + box.getMaxZ())) {
            throw new IllegalArgumentException("A zone is a finite box, not " + box);
        }
        ZoneImpl zone = new ZoneImpl(this, owner, worldId, listener,
            firstBlock(box.getMinX()), firstBlock(box.getMinY()), firstBlock(box.getMinZ()),
            firstBlock(box.getMaxX()) - 1, firstBlock(box.getMaxY()) - 1, firstBlock(box.getMaxZ()) - 1);
        if (zone.maxX < zone.minX || zone.maxY < zone.minY || zone.maxZ < zone.minZ) {
            throw new IllegalArgumentException("A zone holds the centre of at least one block, " + box + " holds none");
        }
        if (!this.live(owner)) throw new IllegalStateException(owner.getName() + " is not enabled: its zone would never be removed");
        if (!this.loaded.contains(worldId)) throw new IllegalStateException("World " + worldId + " is not loaded");
        while (!this.add(worldId, zone)) {
            // the zones of the world looked up were dropped (unloaded) or emptied meanwhile: look again
        }
        if (!this.live(owner)) { // disabled meanwhile, its zones may already be gone
            zone.unregister();
            throw new IllegalStateException(owner.getName() + " was disabled");
        }
        return zone;
    }

    /**
     * Enabled, and not past its disable event - except on the thread disabling it: that one runs
     * nothing else until the plugin is disabled, and enables it again on the same thread.
     */
    private boolean live(Plugin owner) {
        Thread disabler = this.disabling.get(owner);
        return owner.isEnabled() && (disabler == null || disabler == Thread.currentThread());
    }

    /** The {@code PluginDisableEvent} of {@code owner} went through: its zones go, and it may register none until enabled again. */
    public void pluginDisabled(Plugin owner) {
        this.disabling.put(owner, Thread.currentThread()); // first: a register that has not checked its owner yet is refused
        this.unregisterAll(owner);
    }

    /** The {@code PluginEnableEvent} of {@code owner}. */
    public void pluginEnabled(Plugin owner) {
        this.disabling.remove(owner);
    }

    /** @return false when the zones of the world looked up are no longer the ones in the map */
    private boolean add(UUID worldId, ZoneImpl zone) {
        WorldZones world = this.worlds.computeIfAbsent(worldId, id -> new WorldZones());
        synchronized (world) {
            if (this.worlds.get(worldId) != world) return false;
            if (!this.loaded.contains(worldId)) { // its unload event went through since: nothing would ever remove the zone
                if (world.all.isEmpty()) this.worlds.remove(worldId, world);
                throw new IllegalStateException("World " + worldId + " was unloaded");
            }
            world.add(zone);
            synchronized (this) {
                this.registered++;
            }
            return true;
        }
    }

    /** The first block whose centre is at or past {@code bound}; a cast saturates past the int range. */
    private static int firstBlock(double bound) {
        return (int) Math.ceil(bound - 0.5);
    }

    void unregister(ZoneImpl zone) {
        WorldZones world = this.worlds.get(zone.worldId);
        if (world == null) return;
        synchronized (world) {
            if (!zone.registered) return;
            world.remove(zone);
            this.dropped(1);
            if (world.all.isEmpty()) this.worlds.remove(zone.worldId, world); // a register waiting on this lock looks again
        }
        this.purge();
    }

    public void unregisterAll(Plugin owner) {
        if (this.isEmpty()) return;
        for (WorldZones world : this.worlds.values()) {
            List<ZoneImpl> owned = new ArrayList<>();
            synchronized (world) {
                for (ZoneImpl zone : world.all) if (zone.owner == owner) owned.add(zone);
            }
            owned.forEach(ZoneImpl::unregister);
        }
    }

    /** The world was loaded (its {@code WorldLoadEvent}, or loaded before the BTCCore plugin). */
    public void worldLoaded(UUID worldId) {
        this.loaded.loaded(worldId);
    }

    /**
     * The {@code WorldUnloadEvent} of the world went through: its zones go with it. Called at the
     * event, while the world is still in Bukkit; from now on a zone in it is refused.
     */
    public void worldUnloaded(UUID worldId) {
        this.loaded.unloading(worldId); // first: a register that has not taken the lock of the world yet is refused
        WorldZones world = this.worlds.remove(worldId);
        if (world == null) return;
        synchronized (world) {
            for (ZoneImpl zone : world.all) zone.registered = false;
            this.dropped(world.all.size());
            world.all.clear();
        }
        this.purge();
    }

    /**
     * Drops the zones no longer registered from what each player stands in, so that a player who stays
     * put does not keep a dead zone - its listener, its plugin and the class loader of that plugin.
     */
    private void purge() {
        for (Membership membership : this.members.values()) {
            synchronized (membership) {
                membership.zones.removeIf(zone -> !zone.registered);
                membership.dueEnter.removeIf(zone -> !zone.registered);
                membership.dueLeave.removeIf(zone -> !zone.registered);
            }
        }
    }

    private synchronized void dropped(int count) {
        this.registered -= count;
    }

    // ==================== PLAYERS ====================

    /** The player now stands at {@code location}: a move, a teleport, a change of world, a join or a respawn. */
    public void at(Player player, Location location) {
        if (this.isEmpty() || location.getWorld() == null) return;
        this.at(player, location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public void at(Player player, UUID worldId, int x, int y, int z) {
        if (this.isEmpty()) return;
        WorldZones world = this.worlds.get(worldId);
        Membership membership = this.members.get(player.getUniqueId());
        if (membership == null) {
            if (world == null) return;
            membership = this.members.computeIfAbsent(player.getUniqueId(), id -> new Membership());
        }
        List<ZoneImpl> left = null;
        List<ZoneImpl> entered = null;
        synchronized (membership) {
            if (membership.gone) return; // a leave told at quit that teleports: the player is no longer followed
            List<ZoneImpl> inside = membership.zones;
            for (int i = 0; i < inside.size(); i++) {
                ZoneImpl zone = inside.get(i);
                if (zone.registered && zone.contains(worldId, x, y, z)) continue;
                inside.remove(i--);
                // an enter not told yet is withdrawn in silence: the player is never told a leave without its enter
                if (!membership.dueEnter.remove(zone) && zone.registered) {
                    membership.dueLeave.add(zone);
                    left = add(left, zone);
                }
            }
            if (world != null) {
                entered = enter(world.column(x, z), membership, worldId, x, y, z, null);
                entered = enter(world.large, membership, worldId, x, y, z, entered);
            }
        }
        this.call(player, membership, left, false);
        this.call(player, membership, entered, true);
    }

    /** The player left the server: every zone it stood in is left. */
    public void quit(Player player) {
        Membership membership = this.members.get(player.getUniqueId());
        if (membership == null) return;
        List<ZoneImpl> left = new ArrayList<>();
        synchronized (membership) {
            membership.gone = true;
            for (ZoneImpl zone : membership.zones) {
                if (membership.dueEnter.contains(zone) || !zone.registered) continue;
                membership.dueLeave.add(zone);
                left.add(zone);
            }
            membership.zones.clear();
            membership.dueEnter.clear();
        }
        this.call(player, membership, left, false);
        this.members.remove(player.getUniqueId(), membership); // after the calls: kept until then, it stops a teleport in onLeave from following the player again
    }

    public Set<Zone> zonesOf(Player player) {
        Membership membership = this.members.get(player.getUniqueId());
        if (membership == null) return Set.of();
        Set<Zone> zones = new HashSet<>();
        synchronized (membership) {
            for (ZoneImpl zone : membership.zones) if (zone.registered) zones.add(zone);
        }
        return Set.copyOf(zones);
    }

    private static List<ZoneImpl> enter(ZoneImpl[] candidates, Membership membership, UUID worldId, int x, int y, int z, List<ZoneImpl> entered) {
        for (ZoneImpl zone : candidates) {
            if (!zone.registered || !zone.contains(worldId, x, y, z) || membership.zones.contains(zone)) continue;
            membership.zones.add(zone);
            // back in before its leave was told: the leave is withdrawn and nothing is told
            if (membership.dueLeave.remove(zone)) continue;
            membership.dueEnter.add(zone);
            entered = add(entered, zone);
        }
        return entered;
    }

    /** Package-private for the tests: how many worlds have zones held. */
    int worldsHeld() {
        return this.worlds.size();
    }

    /** Package-private for the tests: how many players are followed. */
    int followed() {
        return this.members.size();
    }

    /** Package-private for the tests: how many zones, registered or not, are held for this player. */
    int held(Player player) {
        Membership membership = this.members.get(player.getUniqueId());
        if (membership == null) return 0;
        synchronized (membership) {
            return membership.zones.size() + membership.dueEnter.size() + membership.dueLeave.size();
        }
    }

    private static List<ZoneImpl> add(List<ZoneImpl> list, ZoneImpl zone) {
        List<ZoneImpl> to = list == null ? new ArrayList<>(2) : list;
        to.add(zone);
        return to;
    }

    /**
     * Calls the listeners outside the lock. A call is made only if it is still due: a listener
     * before it may have teleported the player, and the calls of that teleport withdrew it.
     */
    private void call(Player player, Membership membership, List<ZoneImpl> zones, boolean enter) {
        if (zones == null) return;
        for (ZoneImpl zone : zones) {
            boolean due;
            synchronized (membership) {
                due = (enter ? membership.dueEnter : membership.dueLeave).remove(zone);
            }
            if (!due || !zone.registered) continue;
            BiConsumer<Player, ZoneImpl> call = enter ? zone.listener::onEnter : zone.listener::onLeave;
            try {
                call.accept(player, zone);
            } catch (Throwable thrown) {
                int failures = zone.failures.getAndIncrement();
                if (failures % LOG_EVERY_FAILURES == 0) {
                    zone.owner.getLogger().log(Level.SEVERE, "Zone listener failed on " + (enter ? "enter" : "leave") + " of " + player.getName()
                        + " (failure " + (failures + 1) + " of this zone; logged once every " + LOG_EVERY_FAILURES + ")", thrown);
                }
            }
        }
    }

    // ==================== STRUCTURES ====================

    private static long columnKey(int columnX, int columnZ) {
        return ((long) columnX << 32) | (columnZ & 0xFFFFFFFFL);
    }

    /** The zones of one world. Written under its lock; the arrays are replaced, never changed, so readers need none. */
    private static final class WorldZones {
        final ConcurrentHashMap<Long, ZoneImpl[]> columns = new ConcurrentHashMap<>();
        final Set<ZoneImpl> all = new HashSet<>();
        volatile ZoneImpl[] large = NONE;

        ZoneImpl[] column(int x, int z) {
            ZoneImpl[] zones = this.columns.get(columnKey(x >> CELL_SHIFT, z >> CELL_SHIFT));
            return zones == null ? NONE : zones;
        }

        void add(ZoneImpl zone) {
            this.all.add(zone);
            if (zone.columns() > MAX_COLUMNS) {
                this.large = with(this.large, zone);
                return;
            }
            for (int cx = zone.minX >> CELL_SHIFT; cx <= zone.maxX >> CELL_SHIFT; cx++) {
                for (int cz = zone.minZ >> CELL_SHIFT; cz <= zone.maxZ >> CELL_SHIFT; cz++) {
                    this.columns.merge(columnKey(cx, cz), new ZoneImpl[] {zone}, (old, one) -> with(old, zone));
                }
            }
        }

        void remove(ZoneImpl zone) {
            zone.registered = false;
            this.all.remove(zone);
            if (zone.columns() > MAX_COLUMNS) {
                this.large = without(this.large, zone);
                return;
            }
            for (int cx = zone.minX >> CELL_SHIFT; cx <= zone.maxX >> CELL_SHIFT; cx++) {
                for (int cz = zone.minZ >> CELL_SHIFT; cz <= zone.maxZ >> CELL_SHIFT; cz++) {
                    this.columns.computeIfPresent(columnKey(cx, cz), (key, old) -> {
                        ZoneImpl[] rest = without(old, zone);
                        return rest.length == 0 ? null : rest;
                    });
                }
            }
        }

        private static ZoneImpl[] with(ZoneImpl[] zones, ZoneImpl zone) {
            ZoneImpl[] more = Arrays.copyOf(zones, zones.length + 1);
            more[zones.length] = zone;
            return more;
        }

        private static ZoneImpl[] without(ZoneImpl[] zones, ZoneImpl zone) {
            return Arrays.stream(zones).filter(other -> other != zone).toArray(ZoneImpl[]::new);
        }
    }

    /** The zones one player stands in, and the calls about them not made yet; changed under its own lock. */
    private static final class Membership {
        final List<ZoneImpl> zones = new ArrayList<>(2);
        final List<ZoneImpl> dueEnter = new ArrayList<>(2);
        final List<ZoneImpl> dueLeave = new ArrayList<>(2);
        boolean gone; // quit is under way: nothing more is entered
    }

    static final class ZoneImpl implements Zone {
        private final ZoneIndex index;
        final Plugin owner;
        final UUID worldId;
        final ZoneListener listener;
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        volatile boolean registered = true;
        final AtomicInteger failures = new AtomicInteger();

        ZoneImpl(ZoneIndex index, Plugin owner, UUID worldId, ZoneListener listener,
                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.index = index;
            this.owner = owner;
            this.worldId = worldId;
            this.listener = listener;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        boolean contains(UUID world, int x, int y, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ
                && y >= this.minY && y <= this.maxY && this.worldId.equals(world);
        }

        long columns() {
            return ((long) (this.maxX >> CELL_SHIFT) - (this.minX >> CELL_SHIFT) + 1)
                * ((long) (this.maxZ >> CELL_SHIFT) - (this.minZ >> CELL_SHIFT) + 1);
        }

        @Override
        public Plugin getOwner() {
            return this.owner;
        }

        @Override
        public UUID getWorldId() {
            return this.worldId;
        }

        @Override
        public BoundingBox getBounds() {
            return new BoundingBox(this.minX, this.minY, this.minZ, this.maxX + 1.0, this.maxY + 1.0, this.maxZ + 1.0);
        }

        @Override
        public boolean isRegistered() {
            return this.registered;
        }

        @Override
        public void unregister() {
            this.index.unregister(this);
        }

        @Override
        public String toString() {
            return "Zone[" + this.owner.getName() + ' ' + this.worldId + ' ' + this.minX + ',' + this.minY + ',' + this.minZ
                + " .. " + this.maxX + ',' + this.maxY + ',' + this.maxZ + ']';
        }
    }
}
