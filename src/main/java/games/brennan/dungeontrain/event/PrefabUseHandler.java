package games.brennan.dungeontrain.event;
import games.brennan.dungeontrain.platform.event.DtRightClickBlock;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;

import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.editor.BlockVariantPrefabStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ChiseledBookshelfSync;
import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.EntityVariantApplicator;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import games.brennan.dungeontrain.platform.DtAttachments;

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
public final class PrefabUseHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NBT_BV_PREFAB_ID = "dt_bv_prefab_id";
    public static final String NBT_LOOT_PREFAB_ID = "dt_loot_prefab_id";
    /** Set on creative-tab stacks for prefabs that exist only in user config — drives the slot tint mixin. */
    public static final String NBT_PREFAB_UNCOMMITTED = "dt_prefab_uncommitted";

    private PrefabUseHandler() {}

    private static CompoundTag customDataTag(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd == null ? null : cd.copyTag();
    }

    /**
     * Block-variant prefab paste — cancel vanilla placement and apply the
     * snippet to the targeted plot cell. Cancels on both sides so the
     * client doesn't predict-and-show a placement that the server won't
     * actually do.
     */
    public static void onRightClickBlock(DtRightClickBlock event) {
        ItemStack stack = event.itemStack();
        CompoundTag tag = customDataTag(stack);
        if (tag == null) return;

        if (tag.contains(NBT_BV_PREFAB_ID, Tag.TAG_STRING)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            if (!event.level().isClientSide && event.player() instanceof ServerPlayer player) {
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
        ServerPlayer player, DtRightClickBlock event,
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

        ServerLevel level = (ServerLevel) event.level();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Stand inside an editor plot to paste", ChatFormatting.YELLOW);
            return;
        }

        BlockPos placePos = event.pos().relative(event.face());
        if (!level.getBlockState(placePos).canBeReplaced()) {
            placePos = event.pos();
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
                be.loadCustomOnly(merged, level.registryAccess());
                be.setChanged();
                ChiseledBookshelfSync.syncIfNeeded(level, placePos);
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
        public static void onEntityPlace(net.minecraft.world.entity.Entity placeEntity, net.minecraft.world.level.LevelAccessor placeLevel, net.minecraft.world.level.block.state.BlockState placedBlock, net.minecraft.core.BlockPos placePos, boolean placeCanceled) {
        if (!(placeEntity instanceof Player player)) return;
        Level level = (Level) placeLevel;
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        ItemStack stack = stackWithLootPrefab(player);
        if (stack.isEmpty()) return;
        CompoundTag tag = customDataTag(stack);
        if (tag == null || !tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) return;

        String prefabId = tag.getString(NBT_LOOT_PREFAB_ID);
        Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            if (player instanceof ServerPlayer sp) {
                actionBar(sp, "Unknown loot prefab '" + prefabId + "'", ChatFormatting.RED);
            }
            return;
        }

        BlockPos pos = placePos;
        BlockState placedState = serverLevel.getBlockState(pos);
        BlockEntity be = serverLevel.getBlockEntity(pos);
        if (be == null) {
            // Block didn't end up with a BE (edge case if the placed block
            // type changed mid-flight). Nothing to fill.
            return;
        }

        // Roll the pool deterministically off the world seed + cell pos. A
        // hand-placed loot container scales with how far the placing player has
        // travelled (their signed travelledCarriageIndex), so placed-container
        // loot — including the per-50-carriage "Uncraftable" arrow effect tier
        // — matches what the player would find in a naturally-generated carriage
        // at that distance, instead of always rolling as start-of-run.
        long worldSeed = serverLevel.getSeed();
        // Effective (offset-inclusive) progress so a placed container rolls at the
        // player's admin-set difficulty too, matching the naturally-generated carriages.
        int placedCarriageIndex = DifficultyProgression.effectiveTravelled(
            DtAttachments.PLAYER_RUN_STATE.get(player).travelledCarriageIndex());
        CompoundTag baseNbt = be.saveWithFullMetadata(serverLevel.registryAccess());
        CompoundTag rolled = ContainerContentsRoller.roll(
            loaded.get().pool(), placedState, pos, worldSeed, placedCarriageIndex, baseNbt,
            serverLevel.registryAccess(), serverLevel);
        if (rolled == null) return;
        be.loadCustomOnly(rolled, serverLevel.registryAccess());
        be.setChanged();
        ChiseledBookshelfSync.syncIfNeeded(serverLevel, pos);

        // Editor-plot integration: if the player is standing in an editor plot,
        // record the link to the prefab. The pool itself is fetched from
        // LootPrefabStore on every read, so we don't duplicate pool data into
        // the store — the link alone makes the menu, save-over, and template
        // propagation all work.
        boolean linkedInEditor = false;
        if (player instanceof ServerPlayer sp) {
            CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(sp, dims);
            if (plot != null) {
                BlockPos localPos = pos.subtract(plot.origin());
                if (plot.inBoundsTolerant(localPos)) {
                    ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
                    store.setLink(localPos, prefabId);
                    linkedInEditor = true;
                    ContainerContentsMenuController.resyncIfOpen(sp, plot.key(), localPos);
                }
            }
        }

        if (player instanceof ServerPlayer sp) {
            actionBar(sp,
                "Placed loot prefab '" + prefabId + "'" + (linkedInEditor ? " (linked)" : ""),
                ChatFormatting.GREEN);
        }
    }

    /**
     * Entity prefab placement — after vanilla spawns an armor stand or item
     * frame from a held item, attach the prefab's rolled equipment to the
     * fresh entity. Parallel to {@link #onEntityPlace} but for entities
     * rather than block entities — vanilla's
     * {@code BlockEvent.EntityPlaceEvent} only fires for block placements,
     * so armor stand / item frame placement needs its own hook.
     *
     * <p>Player association is by proximity + item-type match: vanilla
     * places the entity within reach of the placer, so the nearest player
     * holding a matching prefab stack is the placer. Falls through silently
     * for entities spawned by other means (mob spawn, chunk load, etc.).</p>
     */
        public static void onEntityJoinLevel(net.minecraft.world.entity.Entity joiningEntity, net.minecraft.world.level.Level joinLevel, boolean loadedFromDisk) {
        if (joinLevel.isClientSide) return;
        if (!(joinLevel instanceof ServerLevel serverLevel)) return;
        Entity entity = joiningEntity;
        net.minecraft.world.item.Item placerItem = placerItemFor(entity);
        if (placerItem == null) return;

        Player placer = findNearbyPlacerHolding(serverLevel, entity, placerItem);
        if (placer == null) {
            // Most entity joins are natural spawns / chunk loads — only log
            // when there IS a nearby player holding the matching item type
            // (regardless of prefab tag), so we surface the case where a
            // player tried to place with the right item but the prefab tag
            // wasn't found in any of their hands.
            Player nearbyAnyItem = nearestPlayerHoldingItem(serverLevel, entity, placerItem);
            if (nearbyAnyItem != null) {
                LOGGER.info("[DungeonTrain] PrefabUseHandler(entity): {} spawned near a player holding {} but no NBT_LOOT_PREFAB_ID match — was the prefab item dropped/swapped?",
                    entity.getType().getDescriptionId(), placerItem);
            }
            return;
        }
        ItemStack stack = stackWithLootPrefab(placer);
        if (stack.isEmpty()) return;
        CompoundTag tag = customDataTag(stack);
        if (tag == null || !tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) return;

        String prefabId = tag.getString(NBT_LOOT_PREFAB_ID);
        Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) {
            if (placer instanceof ServerPlayer sp) {
                actionBar(sp, "Unknown loot prefab '" + prefabId + "'", ChatFormatting.RED);
            }
            LOGGER.info("[DungeonTrain] PrefabUseHandler(entity): prefab '{}' missing for {} at {}",
                prefabId, entity.getType().getDescriptionId(), entity.blockPosition());
            return;
        }
        ContainerContentsPool pool = loaded.get().pool();
        if (pool == null || pool.isEmpty()) {
            LOGGER.info("[DungeonTrain] PrefabUseHandler(entity): prefab '{}' pool empty for {} at {}",
                prefabId, entity.getType().getDescriptionId(), entity.blockPosition());
            return;
        }

        // Deterministic seed reuse: world seed + entity block pos, same as
        // the carriage spawn path. carriageIndex=0 — there's no carriage
        // context for ad-hoc creative placements.
        long worldSeed = serverLevel.getSeed();
        boolean applied = EntityVariantApplicator.applyPoolToLiveEntity(
            entity, pool, worldSeed, /*carriageIndex*/ 0, serverLevel.registryAccess());
        if (!applied) {
            LOGGER.info("[DungeonTrain] PrefabUseHandler(entity): applyPoolToLiveEntity returned false for {} prefab='{}' at {}",
                entity.getType().getDescriptionId(), prefabId, entity.blockPosition());
            return;
        }

        // Editor-plot link write — mirrors the chest path so a stand placed
        // inside an editor plot persists its prefab choice for future spawns.
        boolean linkedInEditor = false;
        if (placer instanceof ServerPlayer sp) {
            CarriageDims dims = DungeonTrainWorldData.get(serverLevel).dims();
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(sp, dims);
            if (plot != null) {
                BlockPos localPos = entity.blockPosition().subtract(plot.origin());
                if (plot.inBoundsTolerant(localPos)) {
                    ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
                    store.setLink(localPos, prefabId);
                    linkedInEditor = true;
                    ContainerContentsMenuController.resyncIfOpen(sp, plot.key(), localPos);
                }
            }
            actionBar(sp,
                "Placed loot prefab '" + prefabId + "'" + (linkedInEditor ? " (linked)" : ""),
                ChatFormatting.GREEN);
            LOGGER.info("[DungeonTrain] PrefabUseHandler(entity): equipped {} from prefab '{}' at world={} linked={}",
                entity.getType().getDescriptionId(), prefabId, entity.blockPosition(), linkedInEditor);
        }
    }

    /**
     * The item used to place this entity, or {@code null} for entity types
     * the loot-prefab flow doesn't support. Used to disambiguate which prefab
     * stack in the placer's hand to consume — e.g. a player holding both a
     * regular armor stand and a glow item frame prefab should only match the
     * one whose item type spawns this entity.
     *
     * <p>{@link GlowItemFrame} extends {@link ItemFrame}, so the glow-frame
     * branch must come first.</p>
     */
    @javax.annotation.Nullable
    private static net.minecraft.world.item.Item placerItemFor(Entity entity) {
        if (entity instanceof ArmorStand) return Items.ARMOR_STAND;
        if (entity instanceof GlowItemFrame) return Items.GLOW_ITEM_FRAME;
        if (entity instanceof ItemFrame) return Items.ITEM_FRAME;
        return null;
    }

    /**
     * Find the closest player within player-reach radius of {@code entity}
     * who holds a loot-prefab stack matching {@code entityItemType}. Returns
     * null if no candidate is found — handler bails out cleanly for
     * non-placement entity joins (chunk load, mob spawn, etc.).
     */
    @javax.annotation.Nullable
    private static Player findNearbyPlacerHolding(ServerLevel level, Entity entity,
                                                   net.minecraft.world.item.Item entityItemType) {
        // 6-block radius — covers player reach (4.5b) plus a slack for the
        // entity's bounding-box centre offset from the click point.
        final double maxDist2 = 6.0 * 6.0;
        Player nearest = null;
        double bestDist2 = maxDist2;
        for (Player p : level.players()) {
            double d2 = p.distanceToSqr(entity);
            if (d2 >= bestDist2) continue;
            if (!holdsPrefabOf(p, entityItemType)) continue;
            nearest = p;
            bestDist2 = d2;
        }
        return nearest;
    }

    private static boolean holdsPrefabOf(Player player, net.minecraft.world.item.Item item) {
        return prefabStackOfType(player.getMainHandItem(), item)
            || prefabStackOfType(player.getOffhandItem(), item);
    }

    private static boolean prefabStackOfType(ItemStack stack, net.minecraft.world.item.Item item) {
        if (stack.isEmpty() || !stack.is(item)) return false;
        CompoundTag tag = customDataTag(stack);
        return tag != null && tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING);
    }

    /**
     * Diagnostic helper — find the nearest player holding {@code item} in
     * either hand, ignoring the prefab tag. Used to surface "vanilla placed
     * the entity but my handler bailed" scenarios so we can tell apart
     * "didn't try" from "tried with the wrong stack".
     */
    @javax.annotation.Nullable
    private static Player nearestPlayerHoldingItem(ServerLevel level, Entity entity,
                                                    net.minecraft.world.item.Item item) {
        final double maxDist2 = 6.0 * 6.0;
        Player nearest = null;
        double bestDist2 = maxDist2;
        for (Player p : level.players()) {
            double d2 = p.distanceToSqr(entity);
            if (d2 >= bestDist2) continue;
            if (p.getMainHandItem().is(item) || p.getOffhandItem().is(item)) {
                nearest = p;
                bestDist2 = d2;
            }
        }
        return nearest;
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
        CompoundTag tag = customDataTag(stack);
        return tag != null && tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING);
    }


    /**
     * Decorate prefab stacks with their id in the tooltip so the user can
     * distinguish two stacks that share an icon (e.g. two oak_planks
     * variant prefabs saved under different names).
     */
    public static void onTooltip(net.minecraft.world.item.ItemStack stack, java.util.List<net.minecraft.network.chat.Component> tooltip) {
        CompoundTag tag = customDataTag(stack);
        if (tag == null) return;
        if (tag.contains(NBT_BV_PREFAB_ID, Tag.TAG_STRING)) {
            tooltip.add(Component.literal(
                "Block Variant Prefab: " + tag.getString(NBT_BV_PREFAB_ID))
                .withStyle(ChatFormatting.AQUA));
        } else if (tag.contains(NBT_LOOT_PREFAB_ID, Tag.TAG_STRING)) {
            tooltip.add(Component.literal(
                "Loot Prefab: " + tag.getString(NBT_LOOT_PREFAB_ID))
                .withStyle(ChatFormatting.GOLD));
        }
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }
}
