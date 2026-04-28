package games.brennan.dungeontrain.item;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.LootPrefabStore;
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
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Optional;

/**
 * Creative-tab clipboard for a named loot-pool prefab. Stack NBT carries
 * just the prefab id ({@code dt_prefab_id}); the actual pool is loaded
 * from {@link LootPrefabStore} on the server at paste time.
 *
 * <p>{@code useOn}: target block must be a container BlockEntity (chest,
 * barrel, dispenser, etc.) inside an editor plot. The pool is written to
 * the plot's {@link ContainerContentsStore} for that local position.</p>
 */
public final class LootPrefabItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NBT_PREFAB_ID = "dt_prefab_id";

    public LootPrefabItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        if (!player.hasPermissions(2)) {
            sendActionBar(player, "Loot prefab requires OP", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        ItemStack stack = ctx.getItemInHand();
        String prefabId = decodePrefabId(stack.getTag());
        if (prefabId == null) {
            sendActionBar(player, "Prefab item missing id tag", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        Optional<ContainerContentsPool> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            sendActionBar(player, "Unknown prefab '" + prefabId + "'", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        ContainerContentsPool pool = loaded.get();

        BlockPos worldPos = ctx.getClickedPos();
        BlockState targetState = serverLevel.getBlockState(worldPos);
        if (!ContainerContentsRoller.isContainerState(targetState)) {
            sendActionBar(player, "Right-click a chest, barrel, or other container",
                ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }

        CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            sendActionBar(player, "Stand inside an editor plot to paste", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        BlockPos localPos = worldPos.subtract(plot.origin());
        if (!plot.inBoundsTolerant(localPos)) {
            sendActionBar(player, "Container is outside the plot", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }

        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        store.putPool(localPos, pool);
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] LootPrefab save failed for {}: {}", plot.key(), e.toString());
            sendActionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        sendActionBar(player, "Applied loot prefab '" + prefabId + "' to "
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
