package dev.btc.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * BTCCore Public API interface.
 * Provides access to BTC Core features for external plugins.
 *
 * Usage:
 * <pre>
 *     BTCCoreAPI api = BTCCoreAPI.instance();
 *
 *     // Check if a feature is enabled
 *     if (api.isZeroFeatureEnabledFor("recipes", player.getWorld().getName())) {
 *         // Vanilla recipes are disabled in this world
 *     }
 *
 *     // Check combat status
 *     if (api.isInCombat(player)) {
 *         // Player is in combat
 *     }
 * </pre>
 */
public interface BTCCoreAPI {

    // ==================== ZERO FEATURES ====================

    boolean isZeroFeatureEnabledFor(String feature, String worldName);

    boolean isRedstoneCompilerEnabledFor(String worldName);

    // ==================== BLOCK VALUE CACHE ====================

    /**
     * Gets the cached block value for a chunk.
     * The level parameter accepts the Bukkit World object for convenience.
     *
     * @param worldName The world name
     * @param chunkX    The chunk X coordinate
     * @param chunkZ    The chunk Z coordinate
     * @return The cached value, or -1 if not cached
     */
    double getChunkValue(String worldName, int chunkX, int chunkZ);

    void setChunkValue(String worldName, int chunkX, int chunkZ, double value);

    void addToChunkValue(String worldName, int chunkX, int chunkZ, double delta);

    void invalidateChunk(String worldName, int chunkX, int chunkZ);

    // ==================== COMBAT ====================

    boolean isInCombat(Player player);

    int getRemainingCombatTime(Player player);

    void tagCombat(Player player);

    void untagCombat(Player player);

    // ==================== VANISH ====================

    int getVanishLevel(Player player);

    boolean isVanished(Player player);

    // ==================== PERFORMANCE ====================

    boolean shouldCalculateCollision(Entity entity, int nearbyEntityCount);

    boolean shouldSendParticle(Player player, Location particleLocation);

    boolean shouldSendSound(Player player, Location soundLocation);

    /**
     * Distance-culling hint for BetterHud elements.
     *
     * @param player           the recipient player
     * @param hudSourceLocation the world location the HUD element is sourced from
     * @return {@code true} if the HUD element should be sent to the player.
     */
    boolean shouldSendBetterHud(Player player, Location hudSourceLocation);

    // ==================== CROSS-WORLD ENTITY TRANSFER ====================

    List<Entity> transferOwnedEntities(Player player, Location destination);

    List<Entity> findOwnedEntities(Player player, double radius);

    boolean isOwnedBy(Entity entity, UUID ownerUUID);

    // ==================== CPS ====================

    int getPlayerCPS(Player player);

    // ==================== DAB EXEMPTION ====================

    /**
     * Marks an entity to always tick its AI, exempting it from DAB (Dynamic Activation of Brain).
     * Useful for boss mobs or entities that must always have active AI regardless of player proximity.
     *
     * @param entity The entity to exempt from DAB.
     */
    void setEntityAlwaysTick(Entity entity);

    /**
     * Checks if an entity is exempt from DAB.
     *
     * @param entity The entity to check.
     * @return {@code true} if the entity always ticks its AI.
     */
    boolean isEntityAlwaysTick(Entity entity);

    // ==================== MSPT ====================

    /**
     * Gets the current server MSPT (milliseconds per tick).
     * Plugins can use this to throttle async tasks under load.
     *
     * @return The average milliseconds per tick over the last 5 seconds.
     */
    double getCurrentMspt();

    /**
     * Gets the configured MSPT threshold above which plugins should back off.
     * Set by {@code performance.mspt-threshold} in {@code btccore.yml}.
     *
     * <p>Pair with {@link #getCurrentMspt()} to throttle work only when the
     * server is actually under load:
     * <pre>{@code
     * if (api.getCurrentMspt() < api.getMsptThreshold()) {
     *     runExpensiveTask();
     * }
     * }</pre>
     *
     * @return The MSPT threshold in milliseconds.
     */
    int getMsptThreshold();

    // ==================== MAINTENANCE ====================

    boolean isMaintenanceMode();

    // ==================== JOIN QUEUE ====================

    int getQueuePosition(UUID uuid);

    int getQueueSize();

    // ==================== REDSTONE EMISSION ====================

    /**
     * Makes the block at {@code location} emit an analog redstone level, or clears the emission when
     * {@code power <= 0}. This lets a custom block — such as a plugin machine — drive a comparator or
     * a redstone wire, which a plain Bukkit block cannot do.
     *
     * <p>The change refreshes neighbouring redstone immediately, so it must be called on the region
     * that owns the block. Emissions are held in memory and are not persisted across a restart; a
     * plugin re-applies them when it reloads its own blocks.
     *
     * @param location the block that should emit
     * @param power    the analog level, 0–15 (values {@code <= 0} clear the emission, values above 15
     *                 are clamped)
     */
    void setEmittedRedstonePower(Location location, int power);

    // ==================== DROPS ====================

    /**
     * Takes over what a block drops, whatever destroys it.
     *
     * <p>This is not an event: the provider is consulted where the loot table would be rolled, so it
     * also answers for the paths no Bukkit event reports — explosions, pistons, fire, {@code /loot},
     * a falling block landing badly. A plugin that only listens to {@code BlockBreakEvent} leaks
     * vanilla items through every one of those.
     *
     * <p>One provider per material; registering again replaces the previous one. The registration
     * stops applying as soon as {@code owner} is disabled.
     *
     * @param owner    the plugin the registration belongs to
     * @param block    the block material to answer for
     * @param provider the provider, which may decline a given roll by returning {@code null}
     */
    void registerBlockDrops(org.bukkit.plugin.Plugin owner, org.bukkit.Material block,
                            dev.btc.core.api.drop.DropProvider provider);

    /**
     * Takes over what an entity type drops on death, shearing or milking.
     *
     * @param owner    the plugin the registration belongs to
     * @param type     the entity type to answer for
     * @param provider the provider, which may decline a given roll by returning {@code null}
     */
    void registerEntityDrops(org.bukkit.plugin.Plugin owner, org.bukkit.entity.EntityType type,
                             dev.btc.core.api.drop.DropProvider provider);

    /**
     * Takes over one exact loot table, by key.
     *
     * <p>The most precise of the three, and the only way to reach a table that belongs to neither a
     * block nor an entity: structure chests, fishing, villager gifts, archaeology. It also wins over
     * a block or entity registration covering the same roll.
     *
     * @param owner     the plugin the registration belongs to
     * @param lootTable the loot table key, for instance {@code minecraft:chests/simple_dungeon}
     * @param provider  the provider, which may decline a given roll by returning {@code null}
     */
    void registerLootTableDrops(org.bukkit.plugin.Plugin owner, org.bukkit.NamespacedKey lootTable,
                                dev.btc.core.api.drop.DropProvider provider);

    /**
     * Rewrites the drops of every roll in the game, after providers and vanilla.
     *
     * <p>Meant for a server that swaps each vanilla item for its own catalogue equivalent. Be aware
     * of the cost: a transformer has to be shown the vanilla result, so registering one forces the
     * vanilla table to be rolled and copied on every drop.
     *
     * @param owner       the plugin the registration belongs to
     * @param transformer the transformer
     */
    void registerDropTransformer(org.bukkit.plugin.Plugin owner,
                                 dev.btc.core.api.drop.DropTransformer transformer);

    /**
     * Drops every drop registration a plugin made.
     *
     * <p>Optional — a disabled plugin's registrations are ignored on their own — but useful to
     * rebuild a set of providers on a config reload.
     *
     * @param owner the plugin whose registrations should go
     */
    void unregisterDrops(org.bukkit.plugin.Plugin owner);

    /**
     * Whether anything is registered against the drop API.
     *
     * @return {@code true} when at least one provider or transformer is registered
     */
    boolean hasDropOverrides();

    // ==================== ISLAND CATCH-UP ====================

    /**
     * Resolves the island that owns a world, by its persisted world name.
     *
     * <p>The world name is the anchor because it is the only identifier that survives an
     * unload/reload: a SlimeWorld is issued a fresh {@code World} UUID every time it loads.
     *
     * @param worldName the world name to resolve
     * @return the island, or empty when the world is not an island world or has no canonical row
     */
    java.util.Optional<dev.btc.core.api.island.IslandKey> islandForWorld(String worldName);

    /**
     * Whether a chunk is inside an island's owned perimeter.
     *
     * <p>Ownership here is the island's unlocked perimeter, not "is the chunk loaded". A chunk
     * outside it never receives a resume callback, however loaded it happens to be.
     *
     * @param island the island
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return {@code true} when the chunk belongs to the island's owned perimeter
     */
    boolean isIslandChunkOwned(dev.btc.core.api.island.IslandKey island, int chunkX, int chunkZ);

    /**
     * Registers a system that advances one island's state when it wakes up.
     *
     * <p>The platform owns the schedule: it decides when a handler runs, on which thread, and how
     * much of the shared operation budget it may spend. Nothing here starts a background tick — a
     * handler runs on activation or chunk resume and at no other time.
     *
     * <p>One handler per {@code systemKey}; registering again replaces the previous one. The
     * registration stops applying as soon as {@code owner} is disabled, and is dropped when the
     * island's world unloads.
     *
     * <p>{@code schemaVersion} is carried into the operation journal. Raise it when the meaning of
     * the handler's own persisted state changes, so an operation written by an older version is not
     * mistaken for one this version could have produced.
     *
     * @param owner         the plugin the registration belongs to
     * @param systemKey     a stable identifier for the system, for instance {@code btcsky:crops}
     * @param schemaVersion the version of this handler's persisted contract, starting at 1
     * @param handler       the handler
     */
    void registerCatchUpHandler(org.bukkit.plugin.Plugin owner, String systemKey, int schemaVersion,
                                dev.btc.core.api.island.CatchUpHandler handler);

    /**
     * Drops every catch-up registration a plugin made.
     *
     * @param owner the plugin whose registrations should go
     */
    void unregisterCatchUpHandlers(org.bukkit.plugin.Plugin owner);

    /**
     * Applies one vanilla random tick to a block, exactly as the world would have done itself.
     *
     * <p>Exists for offline catch-up. A system that has to advance growth for time the world was not
     * ticking has two options: reimplement the growth rules, or replay them. Reimplementing means
     * copying vanilla's per-block probabilities into plugin code, where they drift silently from the
     * game at every update. This method is the second option — vanilla decides what a random tick
     * does, and the caller only decides how many to apply.
     *
     * <p>It applies <em>one</em> tick and reports whether the block was eligible; it does not loop,
     * does not know about elapsed time, and grants nothing by itself. Blocks that do not randomly
     * tick are refused rather than silently ignored, so a caller aiming at the wrong block finds out.
     *
     * <p><b>Threading.</b> Must be called on the thread that owns the block's region — inside a
     * chunk-scoped {@link dev.btc.core.api.island.CatchUpContext}, that is already the case. Calling
     * from any other thread is refused.
     *
     * @param block the block to tick
     * @return {@code true} when the block randomly ticks and the tick was applied, {@code false} when
     *         the block is not a randomly ticking one
     * @throws IllegalStateException when called off the block's region thread
     */
    boolean applyRandomTick(org.bukkit.block.Block block);

    /**
     * Collects the blocks of a chunk that vanilla would consider for a random tick.
     *
     * <p>The companion to {@link #applyRandomTick(org.bukkit.block.Block)}: catch-up needs to know
     * <em>which</em> blocks are worth ticking without walking the ninety-odd thousand positions of a
     * chunk from plugin code. Sections that hold nothing but air are skipped outright, which on a
     * skyblock island is nearly all of them.
     *
     * <p>The result is capped at {@code limit} and is not ordered in any meaningful way. A caller
     * that hits the cap has not seen the whole chunk and should come back for the rest rather than
     * assume it is done.
     *
     * <p><b>Threading.</b> Same rule as {@link #applyRandomTick(org.bukkit.block.Block)}: the
     * chunk's region thread.
     *
     * @param chunk the chunk to scan
     * @param limit the largest number of blocks to return, must be positive
     * @return the randomly ticking blocks, capped at {@code limit}, never {@code null}
     * @throws IllegalStateException when called off the chunk's region thread
     */
    java.util.List<org.bukkit.block.Block> collectRandomlyTickingBlocks(org.bukkit.Chunk chunk, int limit);

    /**
     * Binds the store that answers who owns an island and whether this backend may advance it.
     *
     * <p>Until one is bound every catch-up is refused, which is the safe direction: refusing costs a
     * player some offline progression, guessing costs duplicated production across two backends.
     *
     * <p>The platform consults it where blocking is allowed, but an implementation that goes to the
     * network on every world load will be felt — keep the hot answers in memory.
     *
     * @param source the store, or {@code null} to unbind
     */
    void bindIslandOwnershipSource(dev.btc.core.api.island.IslandOwnershipSource source);

    /**
     * Binds the journal that records what each catch-up operation did.
     *
     * <p>Optional: without one, catch-up still runs, but a crash mid-activation can no longer be told
     * apart from an activation that simply found nothing to do.
     *
     * @param journal the journal, or {@code null} to unbind
     */
    void bindCatchUpJournal(dev.btc.core.api.island.CatchUpJournal journal);

    /**
     * Sets the identifier this backend claims island leases under.
     *
     * <p>Must be stable across restarts and unique among the backends sharing the canonical store: it
     * is what tells "this backend is resuming its own work" from "another backend took over".
     *
     * @param backendId the identifier
     */
    void setIslandBackendId(String backendId);

    /**
     * Signals that an island's world has just loaded, and runs the catch-up it is owed.
     *
     * <p>Everything that decides whether anything happens stays on this side: resolving the island,
     * claiming the lease, validating and clamping the window, budgeting the operations and
     * committing the result. A caller cannot advance an island it does not own, cannot widen a
     * window, and gains nothing by calling twice — the second call finds no elapsed time.
     *
     * <p>Call from the world-load path, on the thread that owns the world.
     *
     * @param world the freshly loaded world
     * @return {@code true} when an activation was accepted and its handlers ran
     */
    boolean activateIsland(org.bukkit.World world);

    /**
     * Gets the instance of the BTCCore API.
     *
     * @return the instance of the BTCCore API
     */
    static BTCCoreAPI instance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final BTCCoreAPI INSTANCE = net.kyori.adventure.util.Services.service(BTCCoreAPI.class)
            .orElseThrow(() -> new IllegalStateException("BTCCoreAPI implementation not found — server must be running BTC-CORE"));
    }
}
