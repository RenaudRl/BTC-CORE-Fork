package dev.btc.core.overlay;

import dev.btc.core.api.overlay.PlayerBlockOverlay;
import dev.btc.core.api.overlay.PlayerOverlayBlockInteractEvent;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.Collection;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BlockVector;
import org.jspecify.annotations.Nullable;

/**
 * One player's overlay (AD-083): the only writer of the cells, and the code the server patch calls
 * from the packet, interaction and movement paths. See {@code dev.btc.core.api.overlay} for the
 * public contract.
 *
 * <p>Packets are never mutated: a covered cell is answered with a new packet for this player only,
 * so a chunk or block-update packet shared between players stays intact.
 */
public final class PlayerBlockOverlayImpl implements PlayerBlockOverlay {

    private final ServerPlayer player;
    private final OverlayIndex<BlockState> index = new OverlayIndex<>();
    /** The level the cells belong to; null while empty. Written on the owning thread only. */
    private volatile @Nullable ServerLevel level;

    private PlayerBlockOverlayImpl(final ServerPlayer player) {
        this.player = player;
    }

    /** The player's overlay, created on first use and kept on the player for the whole connection. */
    public static PlayerBlockOverlayImpl of(final ServerPlayer player) {
        PlayerBlockOverlayImpl overlay = player.btcBlockOverlay;
        if (overlay == null) {
            synchronized (player) {
                overlay = player.btcBlockOverlay;
                if (overlay == null) {
                    overlay = new PlayerBlockOverlayImpl(player);
                    player.btcBlockOverlay = overlay;
                }
            }
        }
        return overlay;
    }

    // ================================================================== hooks (server patch)

    /**
     * {@code ServerCommonPacketListenerImpl#send}: the packet this player receives instead.
     *
     * @return {@code packet} itself when nothing it carries is covered
     */
    public Packet<?> rewrite(final Packet<?> packet) {
        if (!this.isActiveIn(this.player.level())) {
            return packet;
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            final BlockPos pos = update.getPos();
            final BlockState shown = this.index.get(
                OverlayIndex.sectionKeyOfBlock(pos.getX(), pos.getY(), pos.getZ()),
                OverlayIndex.local(pos.getX(), pos.getY(), pos.getZ()));
            return shown == null || shown == update.getBlockState() ? packet : new ClientboundBlockUpdatePacket(pos, shown);
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            return this.rewriteSection(update);
        }
        return packet;
    }

    private Packet<?> rewriteSection(final ClientboundSectionBlocksUpdatePacket update) {
        final SectionPos sectionPos = update.btcSectionPos();
        final OverlayIndex.Section<BlockState> section =
            this.index.section(OverlayIndex.sectionKey(sectionPos.x(), sectionPos.y(), sectionPos.z()));
        if (section == null) {
            return update;
        }
        final short[] positions = update.btcPositions();
        final BlockState[] states = update.btcStates();
        BlockState[] seen = null;
        for (int i = 0; i < positions.length; i++) {
            final BlockState shown = section.get(positions[i]);
            if (shown != null && shown != states[i]) {
                if (seen == null) {
                    seen = states.clone();
                }
                seen[i] = shown;
            }
        }
        return seen == null ? update : new ClientboundSectionBlocksUpdatePacket(sectionPos, positions, seen);
    }

    /**
     * Called after any packet went out. After a chunk column, sends the covered sections of that
     * column on the same connection: the client applies them right after the column, before drawing it.
     */
    public void afterSend(final Packet<?> packet) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunk)) {
            return;
        }
        final ServerLevel current = this.player.level();
        if (!this.isActiveIn(current)) {
            return;
        }
        for (int sectionY = current.getMinSectionY(); sectionY <= current.getMaxSectionY(); sectionY++) {
            final OverlayIndex.Section<BlockState> section =
                this.index.section(OverlayIndex.sectionKey(chunk.x(), sectionY, chunk.z()));
            if (section != null) {
                this.player.connection.send(sectionPacket(SectionPos.of(chunk.x(), sectionY, chunk.z()), section.states()));
            }
        }
    }

    /**
     * {@code handlePlayerAction}, dig actions: answers a dig on a covered cell without touching the world.
     *
     * @return {@code true} when the cell is covered and the packet has been fully handled
     */
    public static boolean onDig(final ServerPlayer player, final BlockPos pos,
                                final ServerboundPlayerActionPacket.Action action, final Direction face,
                                final int sequence) {
        final PlayerBlockOverlayImpl overlay = player.btcBlockOverlay;
        final ServerLevel level = player.level();
        final BlockState shown = overlay == null ? null : overlay.shownAt(level, pos);
        // Out of reach: no event; vanilla refuses it as too far without touching the world.
        if (shown == null || !player.isWithinBlockInteractionRange(pos, 1.0)) {
            return false;
        }
        final PlayerOverlayBlockInteractEvent.Action eventAction = switch (action) {
            case START_DESTROY_BLOCK -> PlayerOverlayBlockInteractEvent.Action.START_BREAK;
            case ABORT_DESTROY_BLOCK -> PlayerOverlayBlockInteractEvent.Action.ABORT_BREAK;
            case STOP_DESTROY_BLOCK -> PlayerOverlayBlockInteractEvent.Action.FINISH_BREAK;
            default -> null; // CHANGE_DESTROY_DIRECTION: nothing new to report, still answered below
        };
        if (eventAction != null) {
            overlay.fire(eventAction, level, pos, face, null, shown);
        }
        resend(player, level, pos);
        player.connection.ackBlockChangesUpTo(sequence);
        return true;
    }

    /**
     * {@code handleUseItemOn}, after the sequence ack: answers a right click on a covered cell (use or
     * place against it) without touching the world.
     *
     * @return {@code true} when the clicked cell is covered and the packet has been fully handled
     */
    public static boolean onUseItemOn(final ServerPlayer player, final BlockHitResult hit, final InteractionHand hand) {
        final PlayerBlockOverlayImpl overlay = player.btcBlockOverlay;
        if (overlay == null || !hit.getLocation().isFinite()) {
            return false;
        }
        final BlockPos pos = hit.getBlockPos();
        final ServerLevel level = player.level();
        final BlockState shown = overlay.shownAt(level, pos);
        if (shown == null || !player.isWithinBlockInteractionRange(pos, 1.0)) {
            return false;
        }
        overlay.fire(PlayerOverlayBlockInteractEvent.Action.INTERACT, level, pos, hit.getDirection(), hand, shown);
        resend(player, level, pos);
        resend(player, level, pos.relative(hit.getDirection()));
        if (!player.getItemInHand(hand).isEmpty()) {
            player.containerMenu.sendAllDataToRemote(); // the client predicted a use or a placement
        }
        return true;
    }

    /** Flight checks: whether a cell marked solid intersects {@code box} (block grid, inclusive). */
    public static boolean hasSolidIn(final ServerPlayer player, final AABB box) {
        final PlayerBlockOverlayImpl overlay = player.btcBlockOverlay;
        return overlay != null && overlay.isActiveIn(player.level()) && overlay.anySolidIn(box);
    }

    /**
     * Phase checks, once real collision was found in {@code box}: whether the client, which sees the
     * overlay, collides there too. Only a cell the overlay shows without collision over a solid real
     * block is excused, so covering one cell does not switch the check off for the whole box.
     */
    public static boolean collidesAsShownIn(final ServerPlayer player, final AABB box) {
        final PlayerBlockOverlayImpl overlay = player.btcBlockOverlay;
        final ServerLevel level = player.level();
        if (overlay == null || !overlay.isActiveIn(level)) {
            return true;
        }
        final CollisionContext context = CollisionContext.of(player);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
                    cursor.set(x, y, z);
                    final BlockState real = level.getBlockStateIfLoaded(cursor);
                    if (real == null || !intersects(real.getCollisionShape(level, cursor, context), cursor, box)) {
                        continue;
                    }
                    final BlockState shown = overlay.index.get(OverlayIndex.sectionKeyOfBlock(x, y, z), OverlayIndex.local(x, y, z));
                    if (shown == null || intersects(shown.getCollisionShape(level, cursor, context), cursor, box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean intersects(final VoxelShape shape, final BlockPos at, final AABB box) {
        return !shape.isEmpty()
            && Shapes.joinIsNotEmpty(shape.move(at.getX(), at.getY(), at.getZ()), Shapes.create(box), BooleanOp.AND);
    }

    /**
     * {@code ServerPlayer#setServerLevel} and {@code #disconnect}: drops the cells, without packets
     * (the client discards its world on a level change), when the player leaves the level they belong to.
     */
    public void onLevelChange(final @Nullable ServerLevel newLevel) {
        if (this.level != null && this.level != newLevel) {
            this.index.clear();
            this.level = null;
        }
    }

    // ================================================================== API

    @Override
    public Player getPlayer() {
        return this.player.getBukkitEntity();
    }

    @Override
    public @Nullable World getWorld() {
        final ServerLevel bound = this.level;
        return bound == null ? null : bound.getWorld();
    }

    @Override
    public int setBlocks(final World world, final Map<BlockVector, BlockData> blocks, final boolean solid) {
        final ServerLevel target = this.writableLevel(world);
        final Long2ObjectMap<Short2ObjectMap<BlockState>> batch = new Long2ObjectOpenHashMap<>();
        for (final Map.Entry<BlockVector, BlockData> entry : blocks.entrySet()) {
            final BlockVector at = entry.getKey();
            if (target.isOutsideBuildHeight(at.getBlockY())) {
                continue;
            }
            OverlayIndex.addCell(batch, at.getBlockX(), at.getBlockY(), at.getBlockZ(), ((CraftBlockData) entry.getValue()).getState());
        }
        if (batch.isEmpty()) {
            return 0;
        }
        this.level = target;
        final Long2ObjectMap<Short2ObjectMap<BlockState>> changed = this.index.put(batch, solid);
        int count = 0;
        for (final Long2ObjectMap.Entry<Short2ObjectMap<BlockState>> entry : changed.long2ObjectEntrySet()) {
            count += entry.getValue().size();
            this.sendToClient(sectionPacket(SectionPos.of(entry.getLongKey()), entry.getValue()));
        }
        return count;
    }

    @Override
    public int removeBlocks(final World world, final Collection<BlockVector> positions) {
        this.writableLevel(world);
        final Long2ObjectMap<ShortSet> batch = new Long2ObjectOpenHashMap<>();
        for (final BlockVector at : positions) {
            OverlayIndex.addPosition(batch, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        }
        return this.uncover(this.index.remove(batch));
    }

    @Override
    public int clear() {
        this.checkOwner();
        return this.uncover(this.index.clear());
    }

    @Override
    public @Nullable BlockData getBlock(final int x, final int y, final int z) {
        final BlockState shown = this.index.get(OverlayIndex.sectionKeyOfBlock(x, y, z), OverlayIndex.local(x, y, z));
        return shown == null ? null : CraftBlockData.createData(shown);
    }

    @Override
    public boolean isCovered(final int x, final int y, final int z) {
        return this.index.get(OverlayIndex.sectionKeyOfBlock(x, y, z), OverlayIndex.local(x, y, z)) != null;
    }

    @Override
    public boolean isSolid(final int x, final int y, final int z) {
        return this.index.isSolid(OverlayIndex.sectionKeyOfBlock(x, y, z), OverlayIndex.local(x, y, z));
    }

    @Override
    public int size() {
        return this.index.size();
    }

    // ================================================================== internals

    private boolean isActiveIn(final ServerLevel current) {
        return !this.index.isEmpty() && this.level == current;
    }

    private @Nullable BlockState shownAt(final ServerLevel current, final BlockPos pos) {
        if (!this.isActiveIn(current)) {
            return null;
        }
        return this.index.get(OverlayIndex.sectionKeyOfBlock(pos.getX(), pos.getY(), pos.getZ()),
            OverlayIndex.local(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean anySolidIn(final AABB box) {
        final int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        final int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
        final int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (this.isSolid(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void fire(final PlayerOverlayBlockInteractEvent.Action action, final ServerLevel at, final BlockPos pos,
                      final Direction face, final @Nullable InteractionHand hand, final BlockState shown) {
        final EquipmentSlot slot = hand == null ? null : CraftEquipmentSlot.getHand(hand);
        new PlayerOverlayBlockInteractEvent(this.player.getBukkitEntity(), action, CraftBlock.at(at, pos),
            CraftBlock.notchToBlockFace(face), slot, CraftBlockData.createData(shown)).callEvent();
    }

    /** Sends the cell as the player should see it now; the send path puts the overlay state back in. */
    private static void resend(final ServerPlayer player, final ServerLevel at, final BlockPos pos) {
        if (player.level() != at) {
            return; // a listener moved the player to another world
        }
        final BlockState real = at.getBlockStateIfLoaded(pos);
        if (real != null) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, real));
        }
    }

    /** Sends the real blocks of uncovered cells; cells in unloaded chunks come back with the next chunk send. */
    private int uncover(final Long2ObjectMap<ShortSet> removed) {
        final ServerLevel bound = this.level;
        if (this.index.isEmpty()) {
            this.level = null;
        }
        int count = 0;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final Long2ObjectMap.Entry<ShortSet> entry : removed.long2ObjectEntrySet()) {
            count += entry.getValue().size();
            if (bound == null || bound != this.player.level()) {
                continue;
            }
            final long key = entry.getLongKey();
            final Short2ObjectOpenHashMap<BlockState> real = new Short2ObjectOpenHashMap<>();
            for (final short local : entry.getValue()) {
                cursor.set(OverlayIndex.blockX(key, local), OverlayIndex.blockY(key, local), OverlayIndex.blockZ(key, local));
                final BlockState state = bound.getBlockStateIfLoaded(cursor);
                if (state != null) {
                    real.put(local, state);
                }
            }
            if (!real.isEmpty()) {
                this.sendToClient(sectionPacket(SectionPos.of(key), real));
            }
        }
        return count;
    }

    private void sendToClient(final Packet<?> packet) {
        if (this.player.connection != null && !this.player.hasDisconnected()) {
            this.player.connection.send(packet);
        }
    }

    private static ClientboundSectionBlocksUpdatePacket sectionPacket(final SectionPos at, final Short2ObjectMap<BlockState> cells) {
        final short[] positions = new short[cells.size()];
        final BlockState[] states = new BlockState[cells.size()];
        int i = 0;
        for (final Short2ObjectMap.Entry<BlockState> cell : cells.short2ObjectEntrySet()) {
            positions[i] = cell.getShortKey();
            states[i] = cell.getValue();
            i++;
        }
        return new ClientboundSectionBlocksUpdatePacket(at, positions, states);
    }

    private ServerLevel writableLevel(final World world) {
        this.checkOwner();
        final ServerLevel target = ((CraftWorld) world).getHandle();
        if (target != this.player.level()) {
            throw new IllegalArgumentException("An overlay only covers the player's current world ("
                + this.player.level().getWorld().getName() + "), not " + world.getName());
        }
        return target;
    }

    private void checkOwner() {
        if (!Bukkit.isOwnedByCurrentRegion(this.player.getBukkitEntity())) {
            throw new IllegalStateException("A player's block overlay is written on the thread that owns the player");
        }
    }
}
