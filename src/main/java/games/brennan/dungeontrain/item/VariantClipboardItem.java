package games.brennan.dungeontrain.item;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom mod item produced by the block-variant menu's Copy button.
 * Visually mimics a vanilla command block (model JSON parents
 * {@code minecraft:block/command_block} so it reuses the vanilla atlas
 * without shipping textures). Carries a snapshot of a variant cell's
 * candidate list in its ItemStack NBT under the {@link #NBT_ROOT_KEY} tag.
 *
 * <p>On {@link #useOn} the item:
 * <ol>
 *   <li>Resolves the carriage variant + local pos under the targeted
 *       block face via {@link CarriageEditor#plotContaining}.</li>
 *   <li>Decodes its NBT snapshot to a {@code List<VariantState>} (skipping
 *       silently on parse errors so a stale clipboard from an older mod
 *       version doesn't crash the placement).</li>
 *   <li>Writes the list to {@link CarriageVariantBlocks} for that
 *       variant, persists, and places a vanilla {@link Blocks#COMMAND_BLOCK}
 *       at the targeted face — the existing variant-empty sentinel that
 *       the apply path translates to air at spawn time.</li>
 *   <li>Consumes one item.</li>
 * </ol></p>
 *
 * <p>If used outside any editor plot the item fails with an action-bar
 * message and is not consumed — same UX as the {@code /dungeontrain editor
 * variant} commands when they refuse to mutate non-editor blocks.</p>
 */
public final class VariantClipboardItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Top-level ItemStack NBT key carrying the variant list. */
    public static final String NBT_ROOT_KEY = "dt_variants";

    /** Per-entry sub-keys, kept short for compact NBT. */
    private static final String NBT_STATE = "s";
    private static final String NBT_BENBT = "n";
    private static final String NBT_WEIGHT = "w";
    private static final String NBT_LOCKED = "l";

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

        // Targeted face: place at the block adjacent to the clicked face,
        // matching vanilla block-place UX.
        BlockPos placePos = ctx.getClickedPos().relative(ctx.getClickedFace());
        BlockState replaceable = serverLevel.getBlockState(placePos);
        if (!replaceable.canBeReplaced()) {
            // Allow placement onto the clicked block itself when the player
            // shift-right-clicks (so the clipboard can overwrite an existing
            // air-placeholder in the carriage).
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
        List<VariantState> states = decodeStates(stack.getTag());
        if (states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            sendActionBar(player, "Clipboard needs at least "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " variants", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }

        // Place the empty-placeholder sentinel (vanilla command block) so
        // the cell renders as a placeholder in the editor view.
        serverLevel.setBlock(placePos, Blocks.COMMAND_BLOCK.defaultBlockState(), 3);

        // Write to the sidecar.
        plot.put(localPos, states);
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] VariantClipboard save failed for {}: {}", plot.key(), e.toString());
            sendActionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        sendActionBar(player, "Pasted " + states.size() + " variants at " + localPos.getX()
            + "," + localPos.getY() + "," + localPos.getZ(), ChatFormatting.GREEN);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Encode a variant list into a fresh {@link CompoundTag} ready for
     * {@link ItemStack#setTag}. Schema mirrors the on-disk JSON v3 format
     * (state-string, optional NBT, optional weight, optional locked) but
     * uses NBT primitives instead of JSON for compact in-stack storage.
     */
    public static CompoundTag encodeStates(List<VariantState> states) {
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
            if (s.locked()) {
                entry.putBoolean(NBT_LOCKED, true);
            }
            list.add(entry);
        }
        root.put(NBT_ROOT_KEY, list);
        return root;
    }

    /**
     * Decode a variant list from an ItemStack's NBT (or null if the stack
     * has no tag). Returns an empty list when the tag is missing or
     * malformed — the caller is expected to bounce the placement with a
     * user-visible error rather than crash.
     */
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
            boolean locked = entry.contains(NBT_LOCKED, Tag.TAG_BYTE) && entry.getBoolean(NBT_LOCKED);
            out.add(new VariantState(state, beNbt, weight, locked));
        }
        return out;
    }

    private static void sendActionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(
            Component.literal(text).withStyle(colour),
            true);
    }

    @Override
    public Component getName(ItemStack stack) {
        List<VariantState> states = decodeStates(stack.getTag());
        if (states.isEmpty()) return super.getName(stack);
        return Component.literal(super.getName(stack).getString() + " (" + states.size() + ")");
    }
}
