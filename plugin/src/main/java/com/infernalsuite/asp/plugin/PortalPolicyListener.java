package com.infernalsuite.asp.plugin;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.world.PortalPolicy;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link PortalPolicy} to portal creation and to the use of an existing Nether portal.
 *
 * <p>Nothing here decides anything: every event is read, handed to the policy, and the policy's
 * verdict is journaled and, in enforce mode only, applied. In observe mode - the delivered default -
 * the only effect is one journal line, which is what Sentinel 11.4 reads.
 *
 * <p>Folia: both events are emitted on the thread owning what they concern (the portal's region,
 * or the entity). Handlers touch only the event's own data and the immutable policy snapshot, and
 * never reach for another world, so nothing here needs a scheduler or a lock.
 *
 * <p>{@code BTCSkyPortalListener} (BTCSky extension) listens to {@code PlayerPortalEvent}, which
 * comes after {@link EntityPortalReadyEvent}; the two never overlap unless travel gating is on.
 */
final class PortalPolicyListener implements Listener {

    private final Logger journal;

    PortalPolicyListener(Logger journal) {
        this.journal = journal;
    }

    /**
     * A portal is about to exist. Cancelling leaves the frame unlit, or the exit portal unbuilt.
     * {@code ignoreCancelled}: a portal another plugin already refused was never going to exist,
     * and journaling it would make the constat count portals nobody can reach.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        PortalPolicy policy = BTCCoreConfig.portals();
        String world = event.getWorld().getName();
        String reason = event.getReason().name();
        PortalPolicy.Decision decision = policy.onCreate(world, reason);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("world", world);
        fields.put("reason", reason);
        List<BlockState> blocks = event.getBlocks();
        fields.put("blocks", blocks.size());
        if (!blocks.isEmpty()) {
            BlockState first = blocks.getFirst();
            fields.put("x", first.getX());
            fields.put("y", first.getY());
            fields.put("z", first.getZ());
        }
        describe(fields, event.getEntity());

        journal.info(policy.logLine("create", decision, fields));
        if (decision.cancel()) {
            event.setCancelled(true);
        }
    }

    /**
     * An entity stood in a Nether portal long enough to be teleported. Cancelling resets its
     * readiness, so nothing moves and no {@code PlayerPortalEvent} follows.
     * Paper emits this for Nether portals only today; the type is still checked, so an End portal
     * can never be refused by it the day that changes.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        PortalPolicy policy = BTCCoreConfig.portals();
        Entity entity = event.getEntity();
        String world = entity.getWorld().getName();
        boolean nether = event.getPortalType() == PortalType.NETHER;
        PortalPolicy.Decision decision = policy.onTravel(world, nether);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("world", world);
        fields.put("portal", event.getPortalType().name());
        World target = event.getTargetWorld();
        fields.put("target", target == null ? "none" : target.getName());
        describe(fields, entity);

        journal.info(policy.logLine("travel", decision, fields));
        if (decision.cancel()) {
            event.setCancelled(true);
        }
    }

    /** Who or what did it, when known: type and id are immutable, the name is the profile name. */
    private static void describe(Map<String, Object> fields, Entity entity) {
        if (entity == null) return;
        fields.put("entity", entity.getType().name());
        fields.put("entity_id", entity.getUniqueId());
        if (entity instanceof Player player) {
            fields.put("player", player.getName());
        }
    }
}
