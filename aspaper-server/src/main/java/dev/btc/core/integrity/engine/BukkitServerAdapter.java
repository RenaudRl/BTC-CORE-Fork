package dev.btc.core.integrity.engine;

import dev.btc.core.BTCCoreBuild;
import dev.btc.core.api.integrity.IntegrityAPI.CheckId;
import dev.btc.core.api.integrity.IntegrityAPI.ClientPlatform;
import dev.btc.core.api.integrity.IntegrityAPI.TeleportKind;
import dev.btc.core.api.integrity.IntegrityAPI.ViolationEvent;
import dev.btc.core.integrity.DeclarationRegistry;
import dev.btc.core.integrity.PlatformRegistry;
import dev.btc.core.integrity.ViolationBus;
import dev.btc.core.integrity.engine.MovementContext.Support;
import dev.btc.core.integrity.engine.ReachCheck.Box;
import dev.btc.core.integrity.engine.ReachCheck.ReachContext;
import dev.btc.core.overlay.PlayerBlockOverlayImpl;
import dev.btc.core.security.NativeAnticheatDB;
import dev.btc.core.security.PlayerSimulationCache;
import dev.btc.core.security.SentinelCommand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
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
    public Optional<MovementContext> movementContext(final UUID player, final double fromX, final double fromZ,
                                                     final double x, final double y, final double z) {
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
        if (!handle.connection.hasClientLoaded()) {
            // Vanilla ignores every movement until the client says its terrain is loaded: before
            // that, the client simulates physics with no blocks and falls into the ground it has
            // not received yet (seen on the bench at every join).
            return Optional.empty();
        }
        final ServerLevel level = handle.level();
        final AABB box = handle.getDimensions(handle.getPose()).makeBoundingBox(x, y, z);
        final AABB fromFeet = underFeet(box.move(fromX - x, 0, fromZ - z));
        // Overlay cells marked solid are ground the client stands on (AD-083). For phase, only a cell the
        // overlay shows without collision over a solid real block is excused: the rest is still judged.
        final boolean supported = !level.noCollision(handle, underFeet(box))
            || !level.noCollision(handle, fromFeet)
            || PlayerBlockOverlayImpl.hasSolidIn(handle, underFeet(box))
            || PlayerBlockOverlayImpl.hasSolidIn(handle, fromFeet);
        final Support support = supported ? Support.SUPPORTED : Support.UNSUPPORTED;
        final AABB phaseBox = box.deflate(PHASE_INSET);
        final boolean insideSolid = !level.noBlockCollision(handle, phaseBox)
            && PlayerBlockOverlayImpl.collidesAsShownIn(handle, phaseBox);
        return Optional.of(new MovementContext(
            limitsOf(handle), support, insideSolid,
            declarations.isClientTerrainDeclared(player, x, y, z),
            declarations.liveMechanics(player),
            platforms.platformOf(player)));
    }

    /** The slab under a player box where something to stand on is looked for. */
    private static AABB underFeet(final AABB box) {
        return new AABB(box.minX, box.minY - SUPPORT_DEPTH, box.minZ, box.maxX, box.minY + PHASE_INSET, box.maxZ);
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
            handle.onClimbable(),
            handle.isSprinting());
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

    // ------------------------------------------------------------------ deterministic checks (16.7)

    @Override
    public int openContainerId(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        return online == null ? 0 : ((CraftPlayer) online).getHandle().containerMenu.containerId;
    }

    @Override
    public long latencyMillis(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        return online == null ? -1 : online.getPing();
    }

    @Override
    public long tickNanos() {
        return MinecraftServer.getServer().tickRateManager().nanosecondsPerTick();
    }

    @Override
    public boolean exemptGameMode(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return false;
        }
        final GameMode mode = online.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    @Override
    public boolean blockedBetween(final UUID player, final double fromX, final double fromY, final double fromZ,
                                  final double toX, final double toY, final double toZ) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null || !readable(online, fromX, fromZ, toX, toZ)) {
            return false;
        }
        return clipBetween(((CraftPlayer) online).getHandle(), fromX, fromY, fromZ, toX, toY, toZ)
            .getType() != HitResult.Type.MISS;
    }

    private static BlockHitResult clipBetween(final ServerPlayer handle, final double fromX, final double fromY,
                                              final double fromZ, final double toX, final double toY,
                                              final double toZ) {
        // COLLIDER: what stops a body. A block that is only selectable (tall grass, a flower) is not a wall.
        return handle.level().clip(new ClipContext(new Vec3(fromX, fromY, fromZ),
            new Vec3(toX, toY, toZ), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, handle));
    }

    @Override
    public String describeWallHit(final UUID player, final int targetEntityId, final double fromX,
                                  final double fromY, final double fromZ, final double toX, final double toY,
                                  final double toZ) {
        final Player online = Bukkit.getPlayer(player);
        if (online == null || !readable(online, fromX, fromZ, toX, toZ)) {
            return "";
        }
        final ServerPlayer handle = ((CraftPlayer) online).getHandle();
        final BlockHitResult hit = clipBetween(handle, fromX, fromY, fromZ, toX, toY, toZ);
        final ServerLevel level = handle.level();
        final Entity target = level.getEntityOrPart(targetEntityId);
        final Vec3 at = hit.getLocation();
        // 14/11 bench: 27 wall hits in an open spawn. What the line hits, what the target is and what block its
        // box sits in tell a wall from a target buried in a block or a line that starts inside one. The details
        // column keeps 255 characters: the block hit and the target come first, positions after.
        return String.format(Locale.ROOT,
            "hit=%s at %d %d %d; target type=%s pose=%s alive=%b; starts-inside=%b; ray-end-in=%s; "
                + "attacker pose=%s alive=%b; hit-at (%.2f %.2f %.2f); target-at (%.2f %.2f %.2f)",
            hit.getType() == HitResult.Type.MISS ? "none" : blockId(level, hit.getBlockPos()),
            hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ(),
            target == null ? "gone" : target.getType().toShortString(),
            target == null ? "-" : target.getPose(), target != null && target.isAlive(),
            hit.isInside(), blockId(level, BlockPos.containing(toX, toY, toZ)),
            handle.getPose(), handle.isAlive(), at.x, at.y, at.z,
            target == null ? 0 : target.getX(), target == null ? 0 : target.getY(),
            target == null ? 0 : target.getZ());
    }

    /** The block's registry id only: its state properties would eat the 255 characters of the details column. */
    private static String blockId(final ServerLevel level, final BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** More chunks than a bounded ray can cross; a longer segment is not judged rather than walked. */
    private static final int MAX_RAY_CHUNKS = 64;

    /**
     * Whether every chunk the box around the segment covers is loaded and owned by the region this thread
     * runs. A diagonal ray may cross a third chunk that neither end is in, and {@code clip} would read it.
     */
    private static boolean readable(final Player online, final double fromX, final double fromZ,
                                    final double toX, final double toZ) {
        final int minChunkX = Math.floorDiv((int) Math.floor(Math.min(fromX, toX)), 16);
        final int maxChunkX = Math.floorDiv((int) Math.floor(Math.max(fromX, toX)), 16);
        final int minChunkZ = Math.floorDiv((int) Math.floor(Math.min(fromZ, toZ)), 16);
        final int maxChunkZ = Math.floorDiv((int) Math.floor(Math.max(fromZ, toZ)), 16);
        if ((long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1) > MAX_RAY_CHUNKS) {
            return false;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!Bukkit.isOwnedByCurrentRegion(online.getWorld(), chunkX, chunkZ)
                    || !online.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public ClientPlatform platformOf(final UUID player) {
        return platforms.platformOf(player);
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
        return Bukkit.getPluginManager().getPlugin(BTCCoreBuild.HOST_PLUGIN);
    }
}
