package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.LootPrefabStore;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Forge event handlers that interpret prefab discriminator NBT on vanilla
 * item stacks. Two NBT keys, two flows:
 *
 * <ul>
 *   <li>{@link #NBT_BV_PREFAB_ID} — block-variant prefab. Right-click cancels
 *       vanilla placement and applies the variant snippet to the targeted
 *       cell within an editor plot (same path as {@code VariantClipboardItem}).</li>
 *   <li>{@link #NBT_LOOT_PREFAB_ID} — loot prefab. Vanilla places the block
 *       normally; an EntityPlaceEvent listener rolls the prefab's pool and
 *       writes the resulting Items NBT into the new BlockEntity. Works
 *       anywhere — no editor-plot constraint.</li>
 * </ul>
 *
 * <p>Tooltips on prefab stacks are decorated with the prefab id so the user
 * can tell two stacks of the same source block apart.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PrefabUseHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NBT_BV_PREFAB_ID = "dt_bv_prefab_id";
    public static final String NBT_LOOT_PREFAB_ID = "dt_loot_prefab_id";
    /** Set on creative-tab stacks for prefabs that exist only in user config — drives the slot tint mixin. */
    public static final String NBT_PREFAB_UNCOMMITTED = "dt_prefab_uncommitted";

    private PrefabUseHandler() {}

    /**
     * Block-variant prefab paste — cancel vanilla placement and apply the
     * snippet to the targeted plot cell. Cancels on both sides so the
     * client doesn't predict-and-show a placement that the server won't
     * actually do.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        CompoundTag tag = stack.getTag();
        if (tag == null) return;

        if (tag.contains(NBT_BV_PREFAB_ID, Tag.TAG_STRING)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            if (!event.getLevel().isClientSide && event.getEntity() instanceof ServerPlayer player) {
                handleBlockVariantPaste(player, event, stack, tag.getString(NBT_BV_PREFAB_ID));
            }
        }
        // Loot prefabs: don't cancel — let vanilla place the block, then
        // BlockEvent.EntityPlaceEvent runs and we fill the new BE.
    }

    /**
     * Server-side paste of a block-variant prefab to the targeted cell.
     * Mirrors {@code VariantClipboardItem.useOn} — OP, plot resolution,
     * sidecar write, representative-block placement, save.
     */
    private static void handleBlockVariantPaste(
        ServerPlayer player, PlayerInteractEvent.RightClickBlock event,
        ItemStack stack, String prefabId
    ) {
        if (!player.hasPermissions(2)) {
            actionBar(player, "Block-variant prefab requires OP", ChatFormatting.RED);
            return;
        }
        Optional<List<VariantState>> loaded = BlockVariantPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            actionBar(player, "Unknown prefab '" + prefabId + "'", ChatFormatting.RED);
            return;
        }
        List<VariantState> states = loaded.get();
        if (states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            actionBar(player, "Prefab has fewer than "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " states",
                ChatFormatting.RED);
            return;
        }

        ServerLevel level = (ServerLevel) event.getLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Stand inside an editor plot to paste", ChatFormatting.YELLOW);
            return;
        }

        BlockPos placePos = event.getPos().relative(event.getFace());
        if (!level.getBlockState(placePos).canBeReplaced()) {
            placePos = event.getPos();
        }
        BlockPos localPos = placePos.subtract(plot.origin());
        if (!plot.inBounds(localPos)) {
            actionBar(player, "Target is outside the plot's footprint", ChatFormatting.YELLOW);
            return;
        }

        // Place a representative block at the target so the cell shows the
        // pasted variant immediately (same as VariantClipboardItem).
        VariantState first = states.get(0);
        boolean firstIsSentinel = CarriageVariantBlocks.isEmptyPlaceholder(first.state());
        BlockState placeholderState = firstIsSentinel
            ? Blocks.COMMAND_BLOCK.defaultBlockState()
            : first.state();
        level.setBlock(placePos, placeholderState, 3);
        if (!firstIsSentinel && first.hasBlockEntityData()) {
            BlockEntity be = level.getBlockEntity(placePos);
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
            LOGGER.error("[DungeonTrain] BlockVariantPrefab paste save failed for {}: {}",
                plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(),
                ChatFormatting.RED);
            return;
        }

        actionBar(player, "Pasted prefab '" + prefabId + "' at "
            + localPos.getX() + "," + localPos.getY() + "," + localPos.getZ(),
            ChatFormatting.GREEN);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }

    /**
     * Loot prefab post-placement: after vanilla places the container block,
     * roll the prefab's pool and write the rolled items into the new BE.
     */
    @SubscribeEvent
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Level level = (Level) event.getLevel();
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        ItemStack stack = stackWithLootPrefab(player);
        if (stack.isEmpty()) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) return;

        String prefabId = tag.getString(NBT_LOOT_PREFAB_ID);
        Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            if (player instanceof ServerPlayer sp) {
                actionBar(sp, "Unknown loot prefab '" + prefabId + "'", ChatFormatting.RED);
            }
            return;
        }

        BlockPos pos = event.getPos();
        BlockState placedState = serverLevel.getBlockState(pos);
        BlockEntity be = serverLevel.getBlockEntity(pos);
        if (be == null) {
            // Block didn't end up with a BE (edge case if the placed block
            // type changed mid-flight). Nothing to fill.
            return;
        }

        // Roll the pool deterministically off the world seed + cell pos.
        long worldSeed = serverLevel.getSeed();
        CompoundTag baseNbt = be.saveWithFullMetadata();
        CompoundTag rolled = ContainerContentsRoller.roll(
            loaded.get().pool(), placedState, pos, worldSeed, /* carriageIndex */ 0, baseNbt);
        if (rolled == null) return;
        be.load(rolled);
        be.setChanged();

        if (player instanceof ServerPlayer sp) {
            actionBar(sp, "Placed loot prefab '" + prefabId + "'", ChatFormatting.GREEN);
        }
    }

    /**
     * Locate the player's prefab stack — vanilla {@code EntityPlaceEvent}
     * doesn't tell us which hand placed the block, so we probe both.
     */
    private static ItemStack stackWithLootPrefab(Player player) {
        ItemStack main = player.getMainHandItem();
        if (hasLootPrefabTag(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (hasLootPrefabTag(off)) return off;
        return ItemStack.EMPTY;
    }

    private static boolean hasLootPrefabTag(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING);
    }

    /**
     * Decorate prefab stacks with their id in the tooltip so the user can
     * distinguish two stacks that share an icon (e.g. two oak_planks
     * variant prefabs saved under different names).
     */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (tag.contains(NBT_BV_PREFAB_ID, Tag.TAG_STRING)) {
            event.getToolTip().add(Component.literal(
                "Block Variant Prefab: " + tag.getString(NBT_BV_PREFAB_ID))
                .withStyle(ChatFormatting.AQUA));
        } else if (tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) {
            event.getToolTip().add(Component.literal(
                "Loot Prefab: " + tag.getString(NBT_LOOT_PREFAB_ID))
                .withStyle(ChatFormatting.GOLD));
        }
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
