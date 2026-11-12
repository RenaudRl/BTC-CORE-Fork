package dev.btc.core.performance;

import com.mojang.logging.LogUtils;
import dev.btc.core.config.BTCCoreConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The entity types whose velocity is always sent
 * ({@code performance.network.filter-entity-motion.always-send}).
 *
 * <p>Resolved on first use, the registries not being there when {@code btccore.yml} is read. The set
 * is immutable and the same whichever thread resolves it, so two worlds may race for it.
 */
public final class EntityMotionTypes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Set<EntityType<?>> alwaysSent;

    private EntityMotionTypes() {}

    public static boolean alwaysSent(EntityType<?> type) {
        Set<EntityType<?>> resolved = alwaysSent;
        if (resolved == null) {
            resolved = resolve("performance.network.filter-entity-motion.always-send", BTCCoreConfig.networkFilterEntityMotionAlwaysSend);
            alwaysSent = resolved;
        }
        return resolved.contains(type);
    }

    /** The types a list of the file names, for {@code option}; an unknown id is reported and left out. */
    static Set<EntityType<?>> resolve(String option, List<String> ids) {
        Set<EntityType<?>> types = new HashSet<>();
        for (String id : ids) {
            Identifier key = Identifier.tryParse(id);
            Optional<EntityType<?>> type = key == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(key);
            if (type.isPresent()) {
                types.add(type.get());
            } else {
                LOGGER.warn("[BTCCore] {}: unknown entity type '{}', ignored", option, id);
            }
        }
        return Set.copyOf(types);
    }
}
