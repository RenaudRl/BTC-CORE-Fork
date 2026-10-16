package dev.btc.core.mining;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The server-side pieces of the server-driven mode that do not belong to the dig state itself: the
 * client view, the packets only the digging player receives, the aim check and the environment.
 *
 * <p>Called from {@code ServerPlayerGameMode}, on the thread that ticks the player, for that player
 * only. Nothing here keeps state: the mode lives in the player's {@link ServerDigClock}, the view on
 * the player's own attribute instance.
 */
public final class ServerDrivenDigging {

    private ServerDrivenDigging() {
    }

    /** The value the client is shown in the mode: it predicts no break and sends no {@code STOP}. */
    static final double ZERO_VIEW = 0.0;

    // ==================== CLIENT VIEW ====================

    /** Shows the client a break speed of 0, at once; the server keeps the real value. */
    public static void showZeroView(final ServerPlayer player) {
        final AttributeInstance instance = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (instance == null) return;
        instance.btcSetClientView(ZERO_VIEW);
        send(player, instance);
    }

    /**
     * Gives the client its real break speed back.
     *
     * @param send {@code false} when the connection is going away and nothing must be sent
     */
    public static void restoreView(final ServerPlayer player, final boolean send) {
        final AttributeInstance instance = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (instance == null || !instance.btcHasClientView()) return;
        instance.btcClearClientView();
        if (send) send(player, instance);
    }

    private static void send(final ServerPlayer player, final AttributeInstance instance) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), List.of(instance)));
    }

    // ==================== PACKETS TO THE DIGGER ====================

    /**
     * The id under which the digger is sent its own crack.
     *
     * <p>Not the player's id — the client erases the entry of its own id at every tick it digs under
     * the zero view — and not CraftEngine's ({@code Integer.MAX_VALUE - id}).
     */
    static int selfCrackId(final int entityId) {
        return -1 - entityId;
    }

    /** Draws (stage 0 to 9) or erases (-1) the crack the digger sees. */
    public static void sendSelfCrack(final ServerPlayer player, final BlockPos pos, final int stage) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundBlockDestructionPacket(selfCrackId(player.getId()), pos, stage));
    }

    /**
     * Plays the break particles and sound to the digger alone.
     *
     * <p>{@code playerWillDestroy} broadcasts them to everyone but the breaker, who is assumed to have
     * predicted the break; under the zero view they predicted nothing.
     */
    public static void sendBreakEffect(final ServerPlayer player, final BlockPos pos, final BlockState state) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundLevelEventPacket(
            LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state), false));
    }

    // ==================== READINGS ====================

    /**
     * The environment factor of a server-driven dig: haste, fatigue, {@code block_break_speed}, water
     * and ground, from the very code vanilla uses.
     *
     * <p>The attribute carries no platform factor during a server-driven dig (the hook wrote 1.0), so
     * its value is the "attribute without {@code btccore:break_speed}" the design asks for.
     */
    public static double environment(final ServerPlayer player) {
        return player.btcApplyDigEnvironment(1.0F, player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED));
    }

    /** Whether this state belongs to the published set (false when nothing is published or live). */
    public static boolean inPublishedSet(final BlockState state) {
        final ServerDrivenRegistry.Published set = ServerDrivenRegistry.live();
        return set != null && set.arms(state);
    }

    /**
     * Whether the server clock may drive and finish a dig of this state: a block of the published set
     * that is not another plugin's (never a CraftEngine block). Any other block keeps the normal dig.
     */
    public static boolean mayFinish(final BlockState state) {
        return BreakSpeedFactor.clockMayDrive(inPublishedSet(state),
            BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** Whether the zero view is wanted this tick: see {@link BreakSpeedFactor#zeroViewWanted}. */
    public static boolean zeroViewWanted(final boolean aimHit, final boolean destroying, final ServerLevel level,
                                         final BlockPos destroyPos, final ServerDrivenRegistry.Published set) {
        return aimHit && BreakSpeedFactor.zeroViewWanted(true, destroying && isOutsideSet(level, destroyPos, set));
    }

    /** Whether the block at {@code pos} is loaded, not air and outside the published set. */
    private static boolean isOutsideSet(final ServerLevel level, final BlockPos pos,
                                       final ServerDrivenRegistry.Published set) {
        final BlockState state = level.getBlockStateIfLoaded(pos);
        return state != null && !state.isAir() && !set.arms(state);
    }

    /**
     * Whether the player aims at a block of the published set, or at one within {@code radius} of the
     * block aimed at — so aiming at the farmland arms the mode before the crop.
     *
     * <p>The ray stops at the block-interaction range, which vanilla already reads from this thread;
     * neighbours in unloaded chunks are skipped, never loaded.
     */
    public static boolean aimsAtArmedBlock(final ServerPlayer player, final ServerLevel level,
                                           final ServerDrivenRegistry.Published set, final int radius) {
        final HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) return false;
        final BlockPos centre = blockHit.getBlockPos();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final BlockState state = level.getBlockStateIfLoaded(
                        cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz));
                    if (state != null && set.arms(state)) return true;
                }
            }
        }
        return false;
    }
}
