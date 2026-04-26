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

/**
 * Server-side driver for the block-variant world-space menu. Tap-Z opens
 * the menu on whatever cell the player is looking at — works in any
 * editor plot (carriage variant, contents, carriage part, or track-side)
 * via {@link BlockVariantPlot#resolveAt}, and opens even when the cell
 * has no variants yet (an empty list; the user can populate it via Add).
 *
 * <p>Edit ops ({@link BlockVariantEditPacket}) are validated for OP and
 * plot-membership before mutating the underlying sidecar; the
 * {@link BlockVariantEditPacket.Op#COPY} op produces a
 * {@link VariantClipboardItem} stack with the current entries encoded as
 * NBT and gives it to the player.</p>
 *
 * <p>Anchor orientation: for vertical faces (walls/doors) the panel's up
 * axis is world up. For horizontal faces (floor/ceiling) the up axis is
 * the player's horizontal forward look so the bottom edge of the menu
 * always faces the player — text reads correctly when looking down at a
 * floor or up at a ceiling.</p>
 */
public final class BlockVariantMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    /** Cap on entries per cell — matches the JSON sidecar's practical limit. */
    public static final int MAX_ENTRIES = 32;

    private BlockVariantMenuController() {}

    /**
     * Handle a {@code BlockVariantMenuTogglePacket}. On open: raycast eye,
     * find the cell under the crosshair, send a sync packet (with the
     * cell's current entries — possibly empty). On close: send empty
     * sync (closes client menu).
     */
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
        if (!plot.inBounds(localPos)) {
            actionBar(player, "Block is outside the editor plot", ChatFormatting.YELLOW);
            return;
        }

        List<VariantState> states = plot.statesAt(localPos);
        if (states == null) states = List.of();

        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, bhit.getDirection(), states, player));
    }

    /** Compose the sync packet payload — anchor on the targeted face, billboarded toward the player. */
    private static BlockVariantSyncPacket buildSyncPacket(
        BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos,
        Direction face, List<VariantState> states, Player player
    ) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        Vec3 anchor = faceCentre.add(normal.scale(0.02));

        // Up axis:
        // - Vertical face (walls/doors) → world up so text reads upright.
        // - Horizontal face (floor/ceiling) → player's horizontal forward
        //   so the bottom of the panel faces the player. When looking
        //   down at the floor, the text reads correctly oriented.
        Vec3 up;
        if (face.getAxis() == Direction.Axis.Y) {
            Vec3 look = player.getLookAngle();
            Vec3 horizontal = new Vec3(look.x, 0, look.z);
            if (horizontal.lengthSqr() < 1.0e-6) {
                horizontal = new Vec3(0, 0, 1);
            }
            up = horizontal.normalize();
        } else {
            up = new Vec3(0, 1, 0);
        }
        Vec3 right = up.cross(normal).normalize();

        List<BlockVariantSyncPacket.Entry> entries = new ArrayList<>(states.size());
        for (VariantState s : states) {
            String stateStr = BlockStateParser.serialize(s.state());
            String beNbt = s.hasBlockEntityData() ? s.blockEntityNbt().toString() : null;
            entries.add(new BlockVariantSyncPacket.Entry(stateStr, beNbt, s.weight(), s.locked()));
        }
        return new BlockVariantSyncPacket(plot.key(), localPos, entries, anchor, right, up);
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

        List<VariantState> current = plot.statesAt(localPos);
        // null current = no entry yet for this cell. ADD is the only op
        // that can seed; all other ops bail out of an empty cell.
        boolean wasEmpty = (current == null);
        List<VariantState> mutated = new ArrayList<>(wasEmpty ? List.of() : current);
        boolean dirty = false;
        boolean dropCell = false;

        switch (packet.op()) {
            case ADD -> {
                // Capture whatever the player is holding in their main hand.
                // Empty stateString from the client signals "use held item"
                // (the search-screen path was removed for block variants —
                // mirrors the hold-Z + right-click capture flow).
                ItemStack held = player.getMainHandItem();
                if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
                    actionBar(player, "Hold a block in your main hand to add it as a variant",
                        ChatFormatting.YELLOW);
                    return;
                }
                BlockState capturedState = blockItem.getBlock().defaultBlockState();
                CompoundTag itemBeNbt = BlockItem.getBlockEntityData(held);
                VariantState newVariant = new VariantState(capturedState, itemBeNbt, 1, false);

                if (wasEmpty) {
                    // Seed with the current world block as entry #0 so the
                    // first ADD also captures the "default" state — mirrors
                    // the right-click-to-add flow in
                    // VariantBlockInteractions.buildUpdatedList.
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
            case TOGGLE_LOCK -> {
                if (wasEmpty) return;
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                VariantState entry = mutated.get(idx);
                mutated.set(idx, entry.withLocked(!entry.locked()));
                dirty = true;
            }
            case COPY -> {
                if (wasEmpty || current.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
                    actionBar(player, "Nothing to copy — cell needs at least "
                        + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " variants",
                        ChatFormatting.YELLOW);
                    return;
                }
                grantClipboard(player, current);
                return;
            }
        }

        if (!dirty) return;

        if (dropCell) {
            plot.remove(localPos);
        } else if (mutated.size() >= CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            plot.put(localPos, mutated);
        } else {
            // Fewer than MIN entries — sidecar can't accept the put. This
            // happens after ADD if the seed flow somehow produced only one
            // entry (shouldn't, since ADD seeds base + new = 2). Safe to skip.
            return;
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

        // Re-sync. Use the player's current crosshair to refresh the
        // anchor face, falling back to UP if the player has looked away.
        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        Direction face = Direction.UP;
        BlockPos worldPos = plot.origin().offset(localPos);
        if (hit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS
            && bhit.getBlockPos().equals(worldPos)) {
            face = bhit.getDirection();
        }
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, face, mutated, player));
    }

    /** Encode {@code states} into a fresh {@link VariantClipboardItem} and place it in the player's hotbar. */
    private static void grantClipboard(ServerPlayer player, List<VariantState> states) {
        ItemStack stack = new ItemStack(ModItems.VARIANT_CLIPBOARD.get());
        CompoundTag tag = VariantClipboardItem.encodeStates(states);
        stack.setTag(tag);
        boolean placed = player.getInventory().add(stack);
        if (!placed) {
            player.drop(stack, false);
        }
        actionBar(player, "Copied " + states.size() + " variants to clipboard", ChatFormatting.GREEN);
    }

    /**
     * Capture the current world block as a {@link VariantState} for ADD-on-empty-cell
     * seeding. Mirrors {@code VariantBlockInteractions.captureBaseVariant} —
     * pulls block-entity NBT off the world so chests / signs / banners
     * round-trip into the variant list.
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
