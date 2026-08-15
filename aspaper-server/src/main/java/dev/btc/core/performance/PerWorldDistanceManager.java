package dev.btc.core.performance;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.config.BTCCoreConfig.WorldDistanceRule;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Applies {@code performance.per-world-distances} to worlds as they load.
 *
 * <p>Why this lives here at all: in Paper 26.2 the per-world distance keys of {@code spigot.yml}
 * are gone — {@code SpigotWorldConfig} no longer exists and {@code server.properties} only carries
 * a single server-wide value. The only remaining per-world lever is the Bukkit API
 * ({@link World#setViewDistance(int)} / {@link World#setSimulationDistance(int)}), which nothing
 * calls on its own. Re-implementing the removed {@code spigot.yml} option would just be undone by
 * the next {@code paperRef} bump, so the config surface lives in {@code btccore.yml} instead.
 *
 * <p>It matters for skyblock: a player standing on their island already makes a square of
 * {@code (2 * simulation-distance + 1)^2} chunks tick — 625 chunks at the default 12 — while an
 * island is at most 225 chunks and mostly smaller. Lowering the simulation distance on island
 * worlds lets an explicit island ticket cover the island instead of *adding* to a player radius
 * that is largely empty space.
 */
public final class PerWorldDistanceManager {

    private static final Logger LOGGER = LogManager.getLogger("BTCCore");

    private PerWorldDistanceManager() {
    }

    /**
     * Applies the first matching rule to a world. No-op when the feature is off or no rule matches,
     * which leaves the world on the server-wide {@code server.properties} values.
     */
    public static void apply(World world) {
        if (world == null) return;
        WorldDistanceRule rule = BTCCoreConfig.distancesFor(world.getName());
        if (rule == null) return;

        try {
            if (rule.viewDistance() != BTCCoreConfig.DISTANCE_UNSET) {
                world.setViewDistance(rule.viewDistance());
            }
            if (rule.simulationDistance() != BTCCoreConfig.DISTANCE_UNSET) {
                world.setSimulationDistance(rule.simulationDistance());
            }
        } catch (RuntimeException e) {
            // A rejected distance must be loud: a world silently left on the global value would
            // invalidate every load measurement taken afterwards.
            LOGGER.warn("per-world-distances: could not apply {} to world '{}': {}",
                    rule, world.getName(), e.toString());
            return;
        }

        LOGGER.info("per-world-distances: world '{}' -> view={} simulation={}",
                world.getName(),
                rule.viewDistance() == BTCCoreConfig.DISTANCE_UNSET ? "unchanged" : rule.viewDistance(),
                rule.simulationDistance() == BTCCoreConfig.DISTANCE_UNSET ? "unchanged" : rule.simulationDistance());
    }

    /**
     * Applies the rules to every world already loaded.
     *
     * <p>Needed because the default worlds are created before the bundled plugin registers its
     * listener, so they never see a {@code WorldLoadEvent} the plugin could hear.
     */
    public static void applyToLoadedWorlds() {
        if (!BTCCoreConfig.perWorldDistancesEnabled) return;
        for (World world : Bukkit.getWorlds()) {
            apply(world);
        }
    }
}
