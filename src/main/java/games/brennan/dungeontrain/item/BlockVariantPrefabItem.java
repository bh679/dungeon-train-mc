package games.brennan.dungeontrain.item;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Creative-tab clipboard for a named block-variant prefab. Stack NBT carries
 * just the prefab id ({@code dt_prefab_id}, ~30 bytes); the actual states
 * are loaded from {@link BlockVariantPrefabStore} on the server at paste
 * time.
 *
 * <p>{@code useOn} mirrors {@link VariantClipboardItem}'s plot-and-cell
 * write path — OP-only, must be inside an editor plot, target cell must be
 * in the plot's footprint. The variant list is written to the plot's
 * sidecar via {@link BlockVariantPlot#put}.</p>
 */
public final class BlockVariantPrefabItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NBT_PREFAB_ID = "dt_prefab_id";

    public BlockVariantPrefabItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!player.hasPermissions(2)) {
            sendActionBar(player, "Block-variant prefab requires OP", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        ItemStack stack = ctx.getItemInHand();
        String prefabId = decodePrefabId(stack.getTag());
        if (prefabId == null) {
            sendActionBar(player, "Prefab item missing id tag", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        Optional<List<VariantState>> loaded = BlockVariantPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            sendActionBar(player, "Unknown prefab '" + prefabId + "'", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        List<VariantState> states = loaded.get();

        BlockPos placePos = ctx.getClickedPos().relative(ctx.getClickedFace());
        if (!serverLevel.getBlockState(placePos).canBeReplaced()) {
            placePos = ctx.getClickedPos();
        }

        CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            sendActionBar(player, "Stand inside an editor plot to paste", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        BlockPos localPos = placePos.subtract(plot.origin());
        if (!plot.inBounds(localPos)) {
            sendActionBar(player, "Target is outside the plot's footprint", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        if (states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            sendActionBar(player, "Prefab has fewer than "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " states", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        // Place a representative block at the target so the cell visually
        // shows the pasted variant (matches VariantClipboardItem behaviour).
        VariantState first = states.get(0);
        boolean firstIsSentinel = CarriageVariantBlocks.isEmptyPlaceholder(first.state());
        var placeholderState = firstIsSentinel
            ? net.minecraft.world.level.block.Blocks.COMMAND_BLOCK.defaultBlockState()
            : first.state();
        serverLevel.setBlock(placePos, placeholderState, 3);
        if (!firstIsSentinel && first.hasBlockEntityData()) {
            var be = serverLevel.getBlockEntity(placePos);
            if (be != null) {
                CompoundTag merged = first.blockEntityNbt().copy();
                merged.putInt("x", placePos.getX());
                merged.putInt("y", placePos.getY());
                merged.putInt("z", placePos.getZ());
                be.load(merged);
                be.setChanged();
            }
        }

        plot.put(localPos, states);
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BlockVariantPrefab save failed for {}: {}", plot.key(), e.toString());
            sendActionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        sendActionBar(player, "Pasted prefab '" + prefabId + "' at "
            + localPos.getX() + "," + localPos.getY() + "," + localPos.getZ(),
            ChatFormatting.GREEN);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    public static ItemStack stackForPrefab(Item item, String prefabId) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putString(NBT_PREFAB_ID, prefabId);
        stack.setTag(tag);
        return stack;
    }

    @Nullable
    public static String decodePrefabId(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_PREFAB_ID, Tag.TAG_STRING)) return null;
        String id = tag.getString(NBT_PREFAB_ID);
        return id.isEmpty() ? null : id;
    }

    private static void sendActionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(
            Component.literal(text).withStyle(colour),
            true);
    }

    @Override
    public Component getName(ItemStack stack) {
        String id = decodePrefabId(stack.getTag());
        if (id == null) return super.getName(stack);
        return Component.literal(super.getName(stack).getString() + ": " + id);
    }
}
