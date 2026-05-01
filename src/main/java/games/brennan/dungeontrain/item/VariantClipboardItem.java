package games.brennan.dungeontrain.item;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom mod item produced by the block-variant menu's Copy button.
 * Visually mimics a vanilla command block (model JSON parents
 * {@code minecraft:block/command_block}). Carries a snapshot of a variant
 * cell's candidate list AND its cell-level lock-id in ItemStack NBT under
 * {@link #NBT_ROOT_KEY} / {@link #NBT_LOCK_ID}.
 *
 * <p>On {@link #useOn} the item:
 * <ol>
 *   <li>Resolves the editor plot the player is standing in via
 *       {@link BlockVariantPlot#resolveAt} (works for any of the four
 *       editor plot types).</li>
 *   <li>Decodes its NBT snapshot to a {@code List<VariantState>} plus
 *       lock-id.</li>
 *   <li>Writes the list to the plot's sidecar at the targeted cell,
 *       persists, and places a vanilla {@link Blocks#COMMAND_BLOCK} as the
 *       editor's empty-placeholder sentinel.</li>
 *   <li>Restores the lock-id on the new cell so it joins the original's
 *       lock group — the only way for two cells to end up with the same
 *       lock-id.</li>
 *   <li>Consumes one item.</li>
 * </ol></p>
 */
public final class VariantClipboardItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Top-level ItemStack NBT key carrying the variant list. */
    public static final String NBT_ROOT_KEY = "dt_variants";

    /** Top-level NBT key carrying the cell-level lock-id (≥1) — absent / 0 means unlocked. */
    public static final String NBT_LOCK_ID = "dt_lockId";

    /** Per-entry sub-keys, kept short for compact NBT. */
    private static final String NBT_STATE = "s";
    private static final String NBT_BENBT = "n";
    private static final String NBT_WEIGHT = "w";
    private static final String NBT_ROT_MODE = "rm";
    private static final String NBT_ROT_DIRS = "rd";

    public VariantClipboardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!player.hasPermissions(2)) {
            sendActionBar(player, "Variant clipboard requires OP", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        BlockPos placePos = ctx.getClickedPos().relative(ctx.getClickedFace());
        BlockState replaceable = serverLevel.getBlockState(placePos);
        if (!replaceable.canBeReplaced()) {
            placePos = ctx.getClickedPos();
        }

        CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            sendActionBar(player, "Stand inside a block-variant editor plot to paste", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        BlockPos localPos = placePos.subtract(plot.origin());
        if (!plot.inBounds(localPos)) {
            sendActionBar(player, "Target is outside the plot's footprint", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }

        ItemStack stack = ctx.getItemInHand();
        CompoundTag tag = readClipboardTag(stack);
        List<VariantState> states = decodeStates(tag);
        int lockId = decodeLockId(tag);
        if (states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            sendActionBar(player, "Clipboard needs at least "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " variants", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }

        // Match the source cell's appearance: place the first variant's
        // BlockState (with NBT if present) so the pasted placeholder reads
        // the same as the original. The empty-placeholder sentinel is kept
        // as a vanilla command block so the "leave empty at spawn" cell
        // type stays visible. Mirrors the Add-on-empty-cell path that
        // captures the player's placed base block as the first variant
        // without replacing the world block.
        VariantState first = states.get(0);
        boolean firstIsSentinel = CarriageVariantBlocks.isEmptyPlaceholder(first.state());
        BlockState placeholderState = firstIsSentinel
            ? Blocks.COMMAND_BLOCK.defaultBlockState()
            : first.state();
        serverLevel.setBlock(placePos, placeholderState, 3);
        if (!firstIsSentinel && first.hasBlockEntityData()) {
            BlockEntity be = serverLevel.getBlockEntity(placePos);
            if (be != null) {
                CompoundTag merged = first.blockEntityNbt().copy();
                merged.putInt("x", placePos.getX());
                merged.putInt("y", placePos.getY());
                merged.putInt("z", placePos.getZ());
                be.loadWithComponents(merged, serverLevel.registryAccess());
                be.setChanged();
            }
        }

        // Write to the sidecar (states first, then lockId so setLockId's
        // "cell must exist" precondition is satisfied).
        plot.put(localPos, states);
        if (lockId > 0) {
            plot.setLockId(localPos, lockId);
        }
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] VariantClipboard save failed for {}: {}", plot.key(), e.toString());
            sendActionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        // Refresh the all-faces lock-id overlay so the new badge shows
        // immediately without waiting for the next overlay tick.
        if (lockId > 0) {
            VariantOverlayRenderer.pushLockIdSnapshot(player);
        }

        String suffix = lockId > 0 ? " (lock-id " + lockId + ")" : "";
        sendActionBar(player, "Pasted " + states.size() + " variants at " + localPos.getX()
            + "," + localPos.getY() + "," + localPos.getZ() + suffix, ChatFormatting.GREEN);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Encode a variant list + cell lock-id into a fresh {@link CompoundTag}
     * ready for {@link ItemStack#setTag}.
     */
    public static CompoundTag encodeStates(List<VariantState> states, int lockId) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        for (VariantState s : states) {
            CompoundTag entry = new CompoundTag();
            entry.putString(NBT_STATE, BlockStateParser.serialize(s.state()));
            if (s.hasBlockEntityData()) {
                entry.put(NBT_BENBT, s.blockEntityNbt());
            }
            if (s.weight() != 1) {
                entry.putInt(NBT_WEIGHT, s.weight());
            }
            VariantRotation rot = s.rotation();
            if (!rot.isDefault()) {
                entry.putByte(NBT_ROT_MODE, (byte) rot.mode().ordinal());
                entry.putByte(NBT_ROT_DIRS, (byte) rot.dirMask());
            }
            list.add(entry);
        }
        root.put(NBT_ROOT_KEY, list);
        if (lockId > 0) {
            root.putInt(NBT_LOCK_ID, lockId);
        }
        return root;
    }

    /** Decode the variant list from an ItemStack's NBT. Empty list when malformed. */
    public static List<VariantState> decodeStates(@Nullable CompoundTag tag) {
        List<VariantState> out = new ArrayList<>();
        if (tag == null || !tag.contains(NBT_ROOT_KEY, Tag.TAG_LIST)) return out;
        ListTag list = tag.getList(NBT_ROOT_KEY, Tag.TAG_COMPOUND);
        HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
            BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String stateStr = entry.getString(NBT_STATE);
            if (stateStr == null || stateStr.isEmpty()) continue;
            BlockState state;
            try {
                state = BlockStateParser.parseForBlock(blocks, stateStr, false).blockState();
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] VariantClipboard: skipping bad state '{}' ({})",
                    stateStr, e.getMessage());
                continue;
            }
            CompoundTag beNbt = entry.contains(NBT_BENBT, Tag.TAG_COMPOUND)
                ? entry.getCompound(NBT_BENBT) : null;
            int weight = entry.contains(NBT_WEIGHT, Tag.TAG_INT) ? entry.getInt(NBT_WEIGHT) : 1;
            VariantRotation rotation = VariantRotation.NONE;
            if (entry.contains(NBT_ROT_MODE, Tag.TAG_BYTE)) {
                int ord = entry.getByte(NBT_ROT_MODE) & 0xFF;
                int mask = entry.contains(NBT_ROT_DIRS, Tag.TAG_BYTE)
                    ? entry.getByte(NBT_ROT_DIRS) & 0xFF
                    : 0;
                VariantRotation.Mode[] modes = VariantRotation.Mode.values();
                if (ord >= 0 && ord < modes.length) {
                    rotation = new VariantRotation(modes[ord], mask);
                }
            }
            out.add(new VariantState(state, beNbt, weight, rotation));
        }
        return out;
    }

    /**
     * Read the clipboard tag from {@link DataComponents#CUSTOM_DATA}. Returns
     * {@code null} for stacks without our component (fresh creative-tab pulls)
     * so the existing decode helpers preserve their "absent → empty list"
     * contract.
     */
    public static @Nullable CompoundTag readClipboardTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return (data == null || data.isEmpty()) ? null : data.copyTag();
    }

    /** Write the clipboard tag into {@link DataComponents#CUSTOM_DATA}. */
    public static void writeClipboardTag(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Decode the cell lock-id from an ItemStack's NBT. Returns 0 when absent. */
    public static int decodeLockId(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_LOCK_ID, Tag.TAG_INT)) return 0;
        int v = tag.getInt(NBT_LOCK_ID);
        return v < 0 ? 0 : v;
    }

    private static void sendActionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(
            Component.literal(text).withStyle(colour),
            true);
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = readClipboardTag(stack);
        List<VariantState> states = decodeStates(tag);
        int lockId = decodeLockId(tag);
        if (states.isEmpty()) return super.getName(stack);
        String suffix = lockId > 0
            ? " (" + states.size() + ", lock " + lockId + ")"
            : " (" + states.size() + ")";
        return Component.literal(super.getName(stack).getString() + suffix);
    }
}
