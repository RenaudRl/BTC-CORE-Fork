package dev.btc.core.integrity.engine;

import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.DeclarationRegistry;
import dev.btc.core.integrity.PlatformRegistry;
import dev.btc.core.integrity.ViolationBus;
import dev.btc.core.integrity.engine.MovementContext.Support;
import dev.btc.core.integrity.engine.ReachCheck.Box;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.security.PlayerSimulationCache;
import dev.btc.core.security.SentinelCommand;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The engine's view of the live server.
 *
 * <p>Thin on purpose: each method is one lookup or one hand-off to a component that already owns
 * the behaviour — the journal ({@link NativeAnticheatDB}), the bus ({@link ViolationBus}), the
 * verbose subscription of {@code /sentinel verbose} — or one read of the player and the world into
 * a plain record the pure engine judges. Nothing is decided here.
 *
 * <p>Every method runs on the region thread that owns the player, which is what makes
 * {@link Bukkit#getPlayer(UUID)} and the NMS reads legal to call. The world reads at a <em>claimed</em>
 * position add one guard: the claim may point outside the region that owns the player, and reading
 * there would be the thread violation Folia exists to forbid. Such a claim is not judged; the vanilla
 * handler refuses it on its own ("moved too quickly").
 */
public final class BukkitServerAdapter implements ServerAdapter {

    /**
     * How far below the feet the server looks for something to stand on. A block top the client
     * says it stands on is at the feet exactly; half a block absorbs the fence-and-wall tops the
     * client rounds and the step the server has not applied yet.
     */
    private static final double SUPPORT_DEPTH = 0.5;

    /** Shrink of the player box before asking whether it is inside a block: touching is not phasing. */
    private static final double PHASE_INSET = 0.01;

    /** One sample of the position history: a tick. */
    private static final long HISTORY_SAMPLE_MILLIS = 50;

    private final DeclarationRegistry declarations;
    private final ViolationBus violations;
    private final PlatformRegistry platforms;

    public BukkitServerAdapter(final DeclarationRegistry declarations, final ViolationBus violations,
                               final PlatformRegistry platforms) {
        this.declarations = declarations;
        this.violations = violations;
        this.platforms = platforms;
    }

    @Override
    public Optional<String> playerName(final UUID player) {
        return Optional.ofNullable(Bukkit.getPlayer(player)).map(Player::getName);
    }

    @Override
    public Optional<TeleportKind> consumeDeclaredTeleport(final UUID player, final double x,
                                                          final double y, final double z) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return Optional.empty();
        }
        // The declaration is matched by world name and position; the world is the one the player
        // is in once the teleport is confirmed, which is where this is called from.
        return declarations.consumeDeclaredTeleportKind(player, new Location(online.getWorld(), x, y, z));
    }

    // ------------------------------------------------------------------ movement (3.2, 3.4)

    @Override
    public Optional<MovementContext> movementContext(final UUID player, final double x,
                                                     final double y, final double z) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return Optional.empty();
        }
        final int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        final int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        if (!Bukkit.isOwnedByCurrentRegion(online.getWorld(), chunkX, chunkZ)
            || !online.getWorld().isChunkLoaded(chunkX, chunkZ)) {
            return Optional.empty();
        }
        final ServerPlayer handle = ((CraftPlayer) online).getHandle();
        final ServerLevel level = handle.level();
        final AABB box = handle.getDimensions(handle.getPose()).makeBoundingBox(x, y, z);
        final AABB underFeet = new AABB(box.minX, box.minY - SUPPORT_DEPTH, box.minZ,
            box.maxX, box.minY + PHASE_INSET, box.maxZ);
        final Support support = level.noCollision(handle, underFeet) ? Support.UNSUPPORTED : Support.SUPPORTED;
        final boolean insideSolid = !level.noBlockCollision(handle, box.deflate(PHASE_INSET));
        return Optional.of(new MovementContext(
            limitsOf(handle), support, insideSolid,
            declarations.isClientTerrainDeclared(player, x, y, z),
            declarations.liveMechanics(player),
            platforms.platformOf(player)));
    }

    /** The player's capabilities as the server has them this tick: attributes and effects, live. */
    private static PlayerLimits limitsOf(final ServerPlayer handle) {
        return new PlayerLimits(
            handle.getAttributeValue(Attributes.MOVEMENT_SPEED),
            handle.getAttributeValue(Attributes.JUMP_STRENGTH),
            handle.getJumpBoostPower(),
            handle.getAttributeValue(Attributes.GRAVITY),
            handle.getAttributeValue(Attributes.STEP_HEIGHT),
            handle.hasEffect(MobEffects.SLOW_FALLING),
            handle.hasEffect(MobEffects.LEVITATION),
            handle.getAbilities().flying,
            handle.isFallFlying(),
            handle.isAutoSpinAttack(),
            handle.isPassenger(),
            handle.isInWater() || handle.isInLava(),
            handle.onClimbable());
    }

    // ------------------------------------------------------------------ combat (3.3, 3.4)

    @Override
    public Optional<ReachContext> reachContext(final UUID player, final int targetEntityId,
                                               final long roundTripNanos) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return Optional.empty();
        }
        final ServerPlayer handle = ((CraftPlayer) online).getHandle();
        final Entity target = handle.level().getEntityOrPart(targetEntityId);
        if (target == null) {
            // Nothing on this server under that id. A client-only entity (2.8) has no reach to
            // judge; an unknown id is refused by the handler itself.
            return Optional.empty();
        }
        final AttackRange range = handle.getAttackRangeWith(handle.getMainHandItem());
        final double maxReach = range.effectiveMaxRange(handle) + range.hitboxMargin();
        final Vec3 eye = handle.getEyePosition();
        return Optional.of(new ReachContext(
            eye.x, eye.y, eye.z,
            targetBoxes(target, roundTripNanos),
            maxReach, isTechnical(target), platforms.platformOf(player),
            roundTripNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(roundTripNanos)));
    }

    /**
     * The target's box now, then every box it occupied during the attacker's round trip.
     *
     * <p>Only players have a history ({@link PlayerSimulationCache}, fed by {@code PlayerMoveEvent});
     * for anything else the current box is all the server keeps, and the journal will show how
     * often a hit on a moving mob lands outside it. One extra sample before the window covers
     * the interval between two samples.
     */
    private static List<Box> targetBoxes(final Entity target, final long roundTripNanos) {
        final AABB now = target.getBoundingBox();
        final List<Box> boxes = new ArrayList<>(4);
        boxes.add(box(now));
        if (roundTripNanos <= 0 || !(target instanceof ServerPlayer)) {
            return boxes;
        }
        final Deque<PlayerSimulationCache.GhostState> history = PlayerSimulationCache.getHistory(target.getUUID());
        if (history == null) {
            return boxes;
        }
        final long windowStartMillis = System.currentTimeMillis()
            - TimeUnit.NANOSECONDS.toMillis(roundTripNanos) - HISTORY_SAMPLE_MILLIS;
        for (final PlayerSimulationCache.GhostState state : history) {
            boxes.add(box(state.boundingBox));
            if (state.timestamp < windowStartMillis) {
                break;
            }
        }
        return boxes;
    }

    private static Box box(final AABB aabb) {
        return new Box(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
    }

    @Override
    public void ping(final UUID player, final int id) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return;
        }
        ((CraftPlayer) online).getHandle().connection.send(new ClientboundPingPacket(id));
    }

    /** Whether an extension marked this entity as furniture rather than a participant (1.5). */
    private boolean isTechnical(final Entity target) {
        final PersistentDataContainer container = target.getBukkitEntity().getPersistentDataContainer();
        for (final NamespacedKey marker : declarations.technicalEntityMarkers()) {
            if (container.has(marker)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ outputs

    @Override
    public void journal(final UUID player, final String playerName, final CheckId check, final String detail) {
        NativeAnticheatDB.reportViolation(player.toString(), playerName, check.toString(), detail);
    }

    @Override
    public void publish(final UUID player, final ViolationEvent event) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return;
        }
        violations.publish(online, event);
    }

    @Override
    public void verbose(final String line) {
        if (SentinelCommand.verboseSubscribers.isEmpty()) {
            return;
        }
        final org.bukkit.plugin.Plugin host = hostPlugin();
        if (host == null) {
            return;
        }
        for (final UUID subscriber : SentinelCommand.verboseSubscribers) {
            final Player staff = Bukkit.getPlayer(subscriber);
            if (staff == null) {
                continue;
            }
            // The subscriber may live on another region: deliver on their own thread.
            staff.getScheduler().run(host, task -> staff.sendMessage(line), null);
        }
    }

    private static org.bukkit.plugin.Plugin hostPlugin() {
        return Bukkit.getPluginManager().getPlugin("ASPaper");
    }
}
