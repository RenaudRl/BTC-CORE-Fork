package dev.btc.core.world;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * How many entities one world holds, by category and by type, kept as they come and go instead of
 * being counted on demand ({@code world.entity-census}).
 *
 * <p>One per world. The thread of the world writes the counts, when an entity starts and stops
 * being tracked by its world: spawned, loaded with its chunk, arrived from another world; removed,
 * unloaded, gone to another world. Any thread reads them, and sets the caps. A count is that of the
 * entities loaded in the world, players included (category of their type).
 *
 * <p>A cap is held per category. Once the count has reached it, the spawns that bring a new
 * creature into the game are refused where a cancelled spawn event would refuse them
 * ({@link #CAPPED}); an entity that already existed or that someone asked for - a plugin, a
 * command, a conversion, a bucket, a pet leaving a shoulder - always comes in.
 */
public final class EntityCensus {

    /** No cap on a category. */
    public static final int NO_CAP = -1;

    /** The reasons a cap refuses: creatures the game makes on its own, or that a player farms. */
    public static final Set<SpawnReason> CAPPED = Collections.unmodifiableSet(EnumSet.of(
        SpawnReason.NATURAL, SpawnReason.SPAWNER, SpawnReason.TRIAL_SPAWNER, SpawnReason.BREEDING,
        SpawnReason.EGG, SpawnReason.DISPENSE_EGG, SpawnReason.SPAWNER_EGG, SpawnReason.SLIME_SPLIT,
        SpawnReason.REINFORCEMENTS, SpawnReason.NETHER_PORTAL, SpawnReason.JOCKEY, SpawnReason.PATROL,
        SpawnReason.RAID, SpawnReason.VILLAGE_INVASION, SpawnReason.VILLAGE_DEFENSE, SpawnReason.TRAP));

    private final AtomicIntegerArray byCategory;
    private final AtomicIntegerArray byType;
    private final AtomicIntegerArray caps;

    public EntityCensus(int categories, int types) {
        this.byCategory = new AtomicIntegerArray(categories);
        this.byType = new AtomicIntegerArray(types);
        this.caps = new AtomicIntegerArray(categories);
        for (int category = 0; category < categories; category++) {
            this.caps.set(category, NO_CAP);
        }
    }

    /** Thread of the world: an entity is now tracked by it. */
    public void added(int category, int type) {
        this.byCategory.incrementAndGet(category);
        this.byType.incrementAndGet(type);
    }

    /** Thread of the world: an entity is not tracked by it any more. */
    public void removed(int category, int type) {
        this.byCategory.decrementAndGet(category);
        this.byType.decrementAndGet(type);
    }

    /** Any thread. */
    public int count(int category) {
        return this.byCategory.get(category);
    }

    /** Any thread. */
    public int countOfType(int type) {
        return this.byType.get(type);
    }

    /** Any thread: the cap of a category, {@link #NO_CAP} when it has none. */
    public int cap(int category) {
        return this.caps.get(category);
    }

    /**
     * Any thread. Entities already there stay: a cap under the count only stops the next ones.
     *
     * @param cap the most entities of the category the world may hold; below 0 clears the cap
     * @return the cap now held, {@link #NO_CAP} when cleared
     */
    public int cap(int category, int cap) {
        int held = cap < 0 ? NO_CAP : cap;
        this.caps.set(category, held);
        return held;
    }

    /** Thread of the world: is this spawn refused because its category is full? No reason (a journey between worlds): never. */
    public boolean refuses(int category, SpawnReason reason) {
        int cap = this.caps.get(category);
        return cap != NO_CAP && this.byCategory.get(category) >= cap && CAPPED.contains(reason);
    }

    /**
     * Whether the mount of a refused spawn was made for it, and goes with it as it does when the
     * spawn event is cancelled: not in the world yet, or added to it this tick. A mount that lived
     * in the world before the spawn - the chicken a baby zombie climbs on - stays.
     *
     * @param inWorld the mount is tracked by a world
     * @param ticksLived the ticks it has lived, kept across its unloads
     */
    public static boolean madeForSpawn(boolean inWorld, int ticksLived) {
        return !inWorld || ticksLived == 0;
    }
}
