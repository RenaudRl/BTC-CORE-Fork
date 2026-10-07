package com.infernalsuite.asp.plugin;

import dev.btc.core.config.BTCCoreConfig;
import dev.btc.core.world.PortalJournalThrottle;
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
import java.util.concurrent.TimeUnit;

/**
 * Applies {@link PortalPolicy} to portal creation and to the use of an existing Nether portal.
 *
 * <p>Each event has two handlers. The decision, and the cancellation when enforcing, happen at HIGH.
 * The journal line is written at MONITOR, once every other plugin has had its say, so it describes
 * what actually happened: a portal another plugin refused is not reported as one that could exist,
 * and an enforced refusal that someone un-cancelled reads {@code action=none}.
 *
 * <p>In observe mode - the delivered default - the only effect is one journal line, which is what
 * Sentinel 11.4 reads.
 *
 * <p>Folia: both events are emitted on the thread owning what they concern (the portal's region,
 * or the entity). Handlers touch only the event's own data, the immutable policy snapshot and a
 * concurrent, bounded throttle, and never reach for another world, so nothing here needs a scheduler.
 *
 * <p>Interaction with {@code BTCSkyPortalListener} (BTCSky extension, {@code PlayerPortalEvent}, which
 * warps island players): in {@code enforce}, cancelling {@link PortalCreateEvent} already stops a
 * portal being lit in any world not listed in {@code allowed-worlds}, so that listener never sees a
 * new portal there. Island worlds ({@code btc_skyblock_*}) must therefore be listed as soon as the
 * mode is {@code enforce}. Travel gating is a separate, optional layer for portals that already stand.
 */
final class PortalPolicyListener implements Listener {

    private final Logger journal;
    private final PortalJournalThrottle travelThrottle = new PortalJournalThrottle();

    PortalPolicyListener(Logger journal) {
        this.journal = journal;
    }

    /**
     * A portal is about to exist. Cancelling leaves the frame unlit, or the exit portal unbuilt.
     * {@code ignoreCancelled}: another plugin's refusal already settled it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        PortalPolicy.Decision decision = BTCCoreConfig.portals()
                .onCreate(event.getWorld().getName(), event.getReason().name());
        if (decision.cancel()) {
            event.setCancelled(true);
        }
    }

    /** Journals what became of the creation. Not {@code ignoreCancelled}: our own refusals are read here. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPortalCreateJournal(PortalCreateEvent event) {
        PortalPolicy policy = BTCCoreConfig.portals();
        String world = event.getWorld().getName();
        String reason = event.getReason().name();
        PortalPolicy.Decision decision = policy.onCreate(world, reason);
        // Cancelled, yet not by this policy: nobody will ever reach that portal, so nothing to report.
        if (event.isCancelled() && !decision.cancel()) return;

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

        journal.info(policy.logLine("create", actual(decision, event.isCancelled()), fields));
    }

    /**
     * An entity stood in a Nether portal long enough to be teleported. Cancelling resets its
     * readiness, so nothing moves and no {@code PlayerPortalEvent} follows.
     * Paper emits this for Nether portals only today; the type is still checked, so an End portal
     * can never be refused by it the day that changes.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        boolean nether = event.getPortalType() == PortalType.NETHER;
        PortalPolicy.Decision decision = BTCCoreConfig.portals()
                .onTravel(event.getEntity().getWorld().getName(), nether);
        if (decision.cancel()) {
            event.setCancelled(true);
        }
    }

    /**
     * Journals the use of a portal. It fires for every entity, items and mobs included, so: INFO
     * only for a refusal or a player, DEBUG for the rest, and one line per entity per window.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPortalReadyJournal(EntityPortalReadyEvent event) {
        PortalPolicy policy = BTCCoreConfig.portals();
        Entity entity = event.getEntity();
        String world = entity.getWorld().getName();
        PortalPolicy.Decision decision = policy.onTravel(world, event.getPortalType() == PortalType.NETHER);
        if (event.isCancelled() && !decision.cancel()) return;

        PortalPolicy.Level level = PortalPolicy.travelLevel(decision, entity instanceof Player);
        if (level == PortalPolicy.Level.DEBUG && !journal.isDebugEnabled()) return;
        long nowMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        if (!travelThrottle.shouldLog(entity.getUniqueId(), nowMillis)) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("world", world);
        fields.put("portal", event.getPortalType().name());
        World target = event.getTargetWorld();
        fields.put("target", target == null ? "none" : target.getName());
        describe(fields, entity);

        String line = policy.logLine("travel", actual(decision, event.isCancelled()), fields);
        if (level == PortalPolicy.Level.INFO) {
            journal.info(line);
        } else {
            journal.debug(line);
        }
    }

    /** The decision as it ended up: {@code cancel} is what the event really says, not what we asked. */
    private static PortalPolicy.Decision actual(PortalPolicy.Decision decision, boolean cancelled) {
        return new PortalPolicy.Decision(decision.rule(), cancelled);
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
