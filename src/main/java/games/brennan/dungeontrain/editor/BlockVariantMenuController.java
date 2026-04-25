package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.item.VariantClipboardItem;
import games.brennan.dungeontrain.net.BlockVariantEditPacket;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side driver for the block-variant world-space menu.
 *
 * <p>Unlike {@link PartPositionMenuController} (which auto-opens on hover),
 * this menu is tap-Z-to-open: the client sends a
 * {@link games.brennan.dungeontrain.net.BlockVariantMenuTogglePacket} on
 * Z-tap; this controller raycasts the player's eye, finds the targeted
 * variant cell, and replies with a {@link BlockVariantSyncPacket}. There
 * is no per-tick polling and no enable/disable flag.</p>
 *
 * <p>Edits ({@link BlockVariantEditPacket}) are validated for OP +
 * plot-membership before mutating the {@link CarriageVariantBlocks}
 * sidecar; the {@link BlockVariantEditPacket.Op#COPY} op is special — it
 * builds a {@link VariantClipboardItem} stack with the current entries
 * encoded as NBT and gives it to the player.</p>
 *
 * <p>Authorisation matches the existing slash-command policy: OP-only,
 * and the player must be inside the variant's editor plot bounds.</p>
 */
public final class BlockVariantMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Eye-pick reach for the toggle-open raycast. */
    private static final double TOGGLE_REACH = 8.0;

    /** Cap on entries per cell — matches the JSON sidecar's practical limit. */
    public static final int MAX_ENTRIES = 32;

    private BlockVariantMenuController() {}

    /**
     * Handle a {@code BlockVariantMenuTogglePacket}. On open: raycast eye,
     * find a flagged cell, send a sync packet. On close: send an empty
     * sync packet (closes the client menu).
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

        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        if (!(hit instanceof BlockHitResult bhit) || bhit.getType() == HitResult.Type.MISS) {
            actionBar(player, "Look at a variant block to open the menu", ChatFormatting.YELLOW);
            return;
        }
        BlockPos worldPos = bhit.getBlockPos();
        CarriageVariant variant = CarriageEditor.plotContaining(worldPos, dims);
        if (variant == null) {
            actionBar(player, "Not in a carriage variant editor plot", ChatFormatting.YELLOW);
            return;
        }
        BlockPos plotOrigin = CarriageEditor.plotOrigin(variant, dims);
        if (plotOrigin == null) {
            actionBar(player, "Editor plot origin missing", ChatFormatting.RED);
            return;
        }
        BlockPos localPos = worldPos.subtract(plotOrigin);
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
        List<VariantState> states = sidecar.statesAt(localPos);
        if (states == null || states.isEmpty()) {
            actionBar(player, "No variants on this block", ChatFormatting.YELLOW);
            return;
        }

        DungeonTrainNet.sendTo(player,
            buildSyncPacket(variant, localPos, worldPos, bhit.getDirection(), states));
    }

    /** Compose the sync packet payload — anchor on the targeted face, billboarded toward the player. */
    private static BlockVariantSyncPacket buildSyncPacket(
        CarriageVariant variant, BlockPos localPos, BlockPos worldPos,
        Direction face, List<VariantState> states
    ) {
        // Anchor: centre of the targeted face, pushed out 0.02 along the
        // face normal to avoid z-fighting.
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        Vec3 anchor = faceCentre.add(normal.scale(0.02));
        // Up axis: world up for vertical faces, +Z for horizontal faces so
        // text reads upright when looking down.
        Vec3 up = (face.getAxis() == Direction.Axis.Y)
            ? new Vec3(0, 0, 1)
            : new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();

        List<BlockVariantSyncPacket.Entry> entries = new ArrayList<>(states.size());
        for (VariantState s : states) {
            String stateStr = BlockStateParser.serialize(s.state());
            String beNbt = s.hasBlockEntityData() ? s.blockEntityNbt().toString() : null;
            entries.add(new BlockVariantSyncPacket.Entry(stateStr, beNbt, s.weight(), s.locked()));
        }
        return new BlockVariantSyncPacket(variant.id(), localPos, entries, anchor, right, up);
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
        CarriageVariant standingIn = CarriageEditor.plotContaining(player.blockPosition(), dims);
        if (standingIn == null || !standingIn.id().equals(packet.variantId())) {
            LOGGER.warn("[DungeonTrain] BlockVariantMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.variantId());
            return;
        }
        BlockPos plotOrigin = CarriageEditor.plotOrigin(standingIn, dims);
        if (plotOrigin == null) return;

        CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(standingIn, dims);
        BlockPos localPos = packet.localPos();
        List<VariantState> current = sidecar.statesAt(localPos);
        if (current == null) {
            // Cell vanished between sync and edit (rare). Silently ignore.
            return;
        }
        List<VariantState> mutated = new ArrayList<>(current);
        boolean dirty = false;
        boolean dropCell = false;

        switch (packet.op()) {
            case ADD -> {
                if (mutated.size() >= MAX_ENTRIES) {
                    actionBar(player, "Variant cell full (max " + MAX_ENTRIES + ")", ChatFormatting.YELLOW);
                    return;
                }
                BlockState parsed = parseStateString(packet.stateString());
                if (parsed == null) {
                    actionBar(player, "Bad block id: " + packet.stateString(), ChatFormatting.RED);
                    return;
                }
                mutated.add(new VariantState(parsed, null, 1, false));
                dirty = true;
            }
            case REMOVE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                mutated.remove(idx);
                if (mutated.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
                    dropCell = true;
                }
                dirty = true;
            }
            case CLEAR -> {
                dropCell = true;
                dirty = true;
            }
            case BUMP_WEIGHT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                int newWeight = Math.max(1, mutated.get(idx).weight() + packet.delta());
                mutated.set(idx, mutated.get(idx).withWeight(newWeight));
                dirty = true;
            }
            case TOGGLE_LOCK -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= mutated.size()) return;
                VariantState entry = mutated.get(idx);
                mutated.set(idx, entry.withLocked(!entry.locked()));
                dirty = true;
            }
            case COPY -> {
                grantClipboard(player, current);
                return;
            }
        }

        if (!dirty) return;

        if (dropCell) {
            sidecar.remove(localPos);
        } else {
            sidecar.put(localPos, mutated);
        }
        try {
            sidecar.save(standingIn);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BlockVariantMenu save failed for '{}': {}",
                standingIn.id(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            // Fall through — still re-sync so the client reflects the
            // committed-in-memory state (matches PartPositionMenuController behaviour).
        }

        if (dropCell) {
            // The cell is gone — close the menu client-side by sending the empty packet.
            DungeonTrainNet.sendTo(player, BlockVariantSyncPacket.empty());
            return;
        }

        // Re-sync. Use the player's current crosshair to refresh the
        // anchor face, falling back to the previous face direction (UP)
        // if the player has looked away.
        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        Direction face = Direction.UP;
        BlockPos worldPos = plotOrigin.offset(localPos);
        if (hit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS
            && bhit.getBlockPos().equals(worldPos)) {
            face = bhit.getDirection();
        }
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(standingIn, localPos, worldPos, face, mutated));
    }

    /** Encode {@code states} into a fresh {@link VariantClipboardItem} and place it in the player's hotbar. */
    private static void grantClipboard(ServerPlayer player, List<VariantState> states) {
        ItemStack stack = new ItemStack(ModItems.VARIANT_CLIPBOARD.get());
        CompoundTag tag = VariantClipboardItem.encodeStates(states);
        stack.setTag(tag);
        boolean placed = player.getInventory().add(stack);
        if (!placed) {
            // Drop at the player's feet if inventory is full — better
            // than silently dropping the data on the floor.
            player.drop(stack, false);
        }
        actionBar(player, "Copied " + states.size() + " variants to clipboard", ChatFormatting.GREEN);
    }

    private static BlockState parseStateString(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
            BuiltInRegistries.BLOCK.asLookup();
        try {
            return BlockStateParser.parseForBlock(blocks, raw, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
