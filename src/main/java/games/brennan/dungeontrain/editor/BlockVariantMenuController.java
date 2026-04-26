package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.net.BlockVariantEditPacket;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Server-side driver for the block-variant world-space menu. Tap-Z opens
 * the menu on whatever cell the player is looking at — works in any
 * editor plot via {@link BlockVariantPlot#resolveAt}, and opens even when
 * the cell has no variants yet.
 *
 * <p>Lock semantics live at the cell level: each cell has a {@code lockId}
 * (0 = unlocked). Cells with the same non-zero lockId form a "lock group"
 * — they share a single state list (auto-propagated on edit) and roll
 * the same random index at spawn time. The menu's Lock toolbar button
 * cycles {@code 0 ↔ nextFreeLockId} on the targeted cell; the only way
 * cells join an existing group is via Copy / Paste.</p>
 *
 * <p>Anchor orientation: vertical faces use world up; horizontal faces
 * use the player's horizontal forward look so the bottom of the menu
 * always faces the player.</p>
 */
public final class BlockVariantMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    /** Cap on entries per cell — matches the JSON sidecar's practical limit. */
    public static final int MAX_ENTRIES = 32;

    private BlockVariantMenuController() {}

    public static void toggle(ServerPlayer player, boolean open) {
        if (!open) {
            DungeonTrainNet.sendTo(player, BlockVariantSyncPacket.empty());
            return;
        }
        if (!player.hasPermissions(2)) {
            actionBar(player, "Block variant menu requires OP", ChatFormatting.RED);
            return;
        }

        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Not in a block-variant editor plot", ChatFormatting.YELLOW);
            return;
        }

        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhit) || bhit.getType() == HitResult.Type.MISS) {
            actionBar(player, "Look at a block to open the menu", ChatFormatting.YELLOW);
            return;
        }
        BlockPos worldPos = bhit.getBlockPos();
        BlockPos localPos = worldPos.subtract(plot.origin());
        // Tolerant check (1-block cage margin) so clicking a cage wall
        // adjacent to the part still opens the menu — clamp to in-bounds
        // so we look up an actual cell, not the cage itself.
        if (!plot.inBoundsTolerant(localPos)) {
            actionBar(player, "Block is outside the editor plot", ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        sendSync(player, plot, clampedLocal, clampedWorld, bhit.getDirection());
    }

    /** Clamp each axis of {@code localPos} into {@code [0, footprint)} so cell lookup always lands on a real cell. */
    private static BlockPos clampToFootprint(BlockPos localPos, BlockVariantPlot plot) {
        net.minecraft.core.Vec3i f = plot.footprint();
        int x = Math.max(0, Math.min(f.getX() - 1, localPos.getX()));
        int y = Math.max(0, Math.min(f.getY() - 1, localPos.getY()));
        int z = Math.max(0, Math.min(f.getZ() - 1, localPos.getZ()));
        return new BlockPos(x, y, z);
    }

    /** Compose + send the sync packet for the cell at {@code localPos}. */
    private static void sendSync(ServerPlayer player, BlockVariantPlot plot,
                                 BlockPos localPos, BlockPos worldPos, Direction face) {
        List<VariantState> states = plot.statesAt(localPos);
        if (states == null) states = List.of();
        int lockId = plot.lockIdAt(localPos);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, face, states, lockId, player));
    }

    /** Compose the sync packet payload — anchor on the targeted face, billboarded toward the player. */
    private static BlockVariantSyncPacket buildSyncPacket(
        BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos,
        Direction face, List<VariantState> states, int lockId, Player player
    ) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        Vec3 anchor = faceCentre.add(normal.scale(0.02));

        Vec3 up;
        if (face.getAxis() == Direction.Axis.Y) {
            Vec3 look = player.getLookAngle();
            Vec3 horizontal = new Vec3(look.x, 0, look.z);
            if (horizontal.lengthSqr() < 1.0e-6) horizontal = new Vec3(0, 0, 1);
            up = horizontal.normalize();
        } else {
            up = new Vec3(0, 1, 0);
        }
        Vec3 right = up.cross(normal).normalize();

        List<BlockVariantSyncPacket.Entry> entries = new ArrayList<>(states.size());
        for (VariantState s : states) {
            String stateStr = BlockStateParser.serialize(s.state());
            String beNbt = s.hasBlockEntityData() ? s.blockEntityNbt().toString() : null;
            entries.add(new BlockVariantSyncPacket.Entry(stateStr, beNbt, s.weight()));
        }
        return new BlockVariantSyncPacket(plot.key(), localPos, entries, lockId, anchor, right, up);
    }

    /** Apply a {@link BlockVariantEditPacket} mutation, with OP + plot validation. */
    public static void applyEdit(ServerPlayer player, BlockVariantEditPacket packet) {
        if (!player.hasPermissions(2)) {
            LOGGER.warn("[DungeonTrain] BlockVariantMenu edit rejected: player {} not OP",
                player.getName().getString());
            return;
        }
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(packet.variantId())) {
            LOGGER.warn("[DungeonTrain] BlockVariantMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.variantId());
            return;
        }
        BlockPos localPos = packet.localPos();
        if (!plot.inBounds(localPos)) {
            LOGGER.warn("[DungeonTrain] BlockVariantMenu edit rejected: localPos {} out of bounds for plot {}",
                localPos, plot.key());
            return;
        }

        // CYCLE_LOCK_ID has its own short flow — handle before the
        // states-list mutation pipeline since it doesn't touch states.
        if (packet.op() == BlockVariantEditPacket.Op.CYCLE_LOCK_ID) {
            cycleLockId(player, plot, localPos);
            return;
        }
        if (packet.op() == BlockVariantEditPacket.Op.COPY) {
            handleCopy(player, plot, localPos);
            return;
        }

        List<VariantState> current = plot.statesAt(localPos);
        boolean wasEmpty = (current == null);
        List<VariantState> mutated = new ArrayList<>(wasEmpty ? List.of() : current);
        boolean dirty = false;
        boolean dropCell = false;

        switch (packet.op()) {
            case ADD -> {
                ItemStack held = player.getMainHandItem();
                BlockState capturedState;
                CompoundTag itemBeNbt;
                if (held.isEmpty()) {
                    // Empty hand → add the empty-placeholder sentinel.
                    // CarriageVariantBlocks.isEmptyPlaceholder translates this
                    // back to Blocks.AIR at spawn time, so the variant means
                    // "leave this position empty in the rolled carriage".
                    capturedState = net.minecraft.world.level.block.Blocks.COMMAND_BLOCK.defaultBlockState();
                    itemBeNbt = null;
                } else if (!(held.getItem() instanceof BlockItem blockItem)) {
                    actionBar(player, "Hold a block (or empty hand for air) to add a variant",
                        ChatFormatting.YELLOW);
                    return;
                } else {
                    capturedState = blockItem.getBlock().defaultBlockState();
                    itemBeNbt = BlockItem.getBlockEntityData(held);
                }
                VariantState newVariant = new VariantState(capturedState, itemBeNbt, 1);

                if (wasEmpty) {
                    BlockPos worldPos = plot.origin().offset(localPos);
                    BlockState baseState = level.getBlockState(worldPos);
                    if (baseState.isAir()) {
                        actionBar(player, "Place a base block first (target is air)", ChatFormatting.YELLOW);
                        return;
                    }
                    VariantState baseVariant = captureBaseVariant(level, worldPos, baseState);
                    mutated.add(baseVariant);
                }
                if (mutated.size() >= MAX_ENTRIES) {
                    actionBar(player, "Variant cell full (max " + MAX_ENTRIES + ")", ChatFormatting.YELLOW);
                    return;
                }
                mutated.add(newVariant);
                dirty = true;
            }
            case REMOVE -> {
                if (wasEmpty) return;
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                mutated.remove(idx);
                if (mutated.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
                    dropCell = true;
                }
                dirty = true;
            }
            case CLEAR -> {
                if (wasEmpty) return;
                dropCell = true;
                dirty = true;
            }
            case BUMP_WEIGHT -> {
                if (wasEmpty) return;
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                int newWeight = Math.max(1, mutated.get(idx).weight() + packet.delta());
                mutated.set(idx, mutated.get(idx).withWeight(newWeight));
                dirty = true;
            }
            default -> {
                // CYCLE_LOCK_ID and COPY handled above; nothing else expected.
                return;
            }
        }

        if (!dirty) return;

        // Lock-group propagation: if the targeted cell is part of a lock
        // group, apply the same mutation to every sibling cell so the
        // group stays in sync.
        int lockId = plot.lockIdAt(localPos);
        Set<BlockPos> targets = lockId > 0 ? plot.positionsWithLockId(lockId) : Set.of(localPos);
        if (targets.isEmpty()) targets = Set.of(localPos);

        for (BlockPos target : targets) {
            if (dropCell) {
                plot.remove(target);
            } else if (mutated.size() >= CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
                plot.put(target, mutated);
            }
        }
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BlockVariantMenu save failed for {}: {}",
                plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            // Fall through — still re-sync so the client reflects committed state.
        }

        if (dropCell) {
            DungeonTrainNet.sendTo(player, BlockVariantSyncPacket.empty());
            return;
        }

        // Re-sync the targeted cell.
        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        Direction face = Direction.UP;
        BlockPos worldPos = plot.origin().offset(localPos);
        if (hit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS
            && bhit.getBlockPos().equals(worldPos)) {
            face = bhit.getDirection();
        }
        sendSync(player, plot, localPos, worldPos, face);
    }

    /**
     * Cycle the cell's lock-id: 0 → next free positive integer; non-zero
     * → 0. Operates only on the targeted cell — to join an existing
     * group, the player must use Copy / Paste of a clipboard item.
     *
     * <p>If the cell has no entries yet (no states), refuse with a hint —
     * locking only makes sense on populated cells.</p>
     */
    private static void cycleLockId(ServerPlayer player, BlockVariantPlot plot, BlockPos localPos) {
        if (plot.statesAt(localPos) == null) {
            actionBar(player, "Add at least one variant before locking", ChatFormatting.YELLOW);
            return;
        }
        int current = plot.lockIdAt(localPos);
        int next = current > 0 ? 0 : plot.nextFreeLockId();
        plot.setLockId(localPos, next);
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BlockVariantMenu lock save failed for {}: {}",
                plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
        }
        // Re-sync.
        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        Direction face = Direction.UP;
        BlockPos worldPos = plot.origin().offset(localPos);
        if (hit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS
            && bhit.getBlockPos().equals(worldPos)) {
            face = bhit.getDirection();
        }
        sendSync(player, plot, localPos, worldPos, face);
    }

    /** COPY: build a clipboard ItemStack capturing the cell's states + lockId. */
    private static void handleCopy(ServerPlayer player, BlockVariantPlot plot, BlockPos localPos) {
        List<VariantState> current = plot.statesAt(localPos);
        if (current == null || current.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            actionBar(player, "Nothing to copy — cell needs at least "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " variants",
                ChatFormatting.YELLOW);
            return;
        }
        int lockId = plot.lockIdAt(localPos);
        ItemStack stack = new ItemStack(ModItems.VARIANT_CLIPBOARD.get());
        CompoundTag tag = VariantClipboardItem.encodeStates(current, lockId);
        stack.setTag(tag);
        boolean placed = player.getInventory().add(stack);
        if (!placed) player.drop(stack, false);
        String suffix = lockId > 0 ? " (lock-id " + lockId + ")" : "";
        actionBar(player, "Copied " + current.size() + " variants" + suffix, ChatFormatting.GREEN);
    }

    /**
     * Capture the current world block as a {@link VariantState} for ADD-on-empty-cell
     * seeding.
     */
    private static @Nullable VariantState captureBaseVariant(ServerLevel level, BlockPos clicked, BlockState baseState) {
        if (baseState.isAir()) return null;
        CompoundTag beNbt = null;
        if (baseState.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(clicked);
            if (be != null) beNbt = be.saveWithoutMetadata();
        }
        return new VariantState(baseState, beNbt);
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
