package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.ContainerContentsEditPacket;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side driver for the container-contents world-space menu (C key).
 *
 * <p>Mirrors {@link BlockVariantMenuController} with these differences:
 * <ul>
 *   <li>Targets a {@link net.minecraft.world.Container} BlockEntity, not
 *       any block-variant cell.</li>
 *   <li>Pool entries are weighted Items, not BlockStates.</li>
 *   <li>No rotation, no lock-IDs, no clipboard copy.</li>
 *   <li>Storage is a separate per-plot file (see
 *       {@link ContainerContentsStore}) so existing variant sidecars stay
 *       untouched.</li>
 * </ul>
 */
public final class ContainerContentsMenuController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    /**
     * Per-player open-menu state. {@code category} reflects what the player
     * was looking at when the menu opened — {@link LootPrefabStore#CATEGORY_LOOT}
     * for a container block, {@link LootPrefabStore#CATEGORY_ARMOR_STAND} or
     * {@link LootPrefabStore#CATEGORY_ITEM_FRAME} for the corresponding entity.
     * The save flow reads this to route prefabs to the right creative tab.
     */
    private record OpenMenu(String plotKey, BlockPos localPos, Direction face, Vec3 up, String category) {}

    private static final Map<UUID, OpenMenu> OPEN = new ConcurrentHashMap<>();

    private ContainerContentsMenuController() {}

    public static void forget(ServerPlayer player) {
        OPEN.remove(player.getUUID());
    }

    public static void clearAll() {
        OPEN.clear();
    }

    public static void toggle(ServerPlayer player, boolean open) {
        if (!open) {
            OPEN.remove(player.getUUID());
            DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
            return;
        }
        if (!player.hasPermissions(2)) {
            actionBar(player, "Container contents menu requires OP", ChatFormatting.RED);
            return;
        }

        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, "Not in an editor plot", ChatFormatting.YELLOW);
            return;
        }

        // Block raycast first — chest/barrel/etc.
        HitResult blockHit = player.pick(TOGGLE_REACH, 1.0f, false);
        BlockHitResult validBlockHit = null;
        if (blockHit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS) {
            BlockState targetState = level.getBlockState(bhit.getBlockPos());
            if (ContainerContentsRoller.isContainerState(targetState)) {
                validBlockHit = bhit;
            }
        }
        // Entity raycast in parallel — armor stand / item frame can host the
        // same menu via EntityVariantApplicator's contentsStore fallback.
        EntityHitResult entityHit = pickSupportedEntity(player, TOGGLE_REACH);

        // Both hit: pick the closer one (vanilla interaction precedence).
        Vec3 eye = player.getEyePosition(1.0f);
        double blockDist2 = validBlockHit == null ? Double.POSITIVE_INFINITY
            : Vec3.atCenterOf(validBlockHit.getBlockPos()).distanceToSqr(eye);
        double entityDist2 = entityHit == null ? Double.POSITIVE_INFINITY
            : entityHit.getLocation().distanceToSqr(eye);

        if (validBlockHit != null && blockDist2 <= entityDist2) {
            openAtBlock(player, plot, level, validBlockHit);
            return;
        }
        if (entityHit != null) {
            openAtEntity(player, plot, level, entityHit);
            return;
        }
        actionBar(player, "Look at a chest, barrel, armor stand, or item frame",
            ChatFormatting.YELLOW);
    }

    /** Open the menu for a container block hit — the original code path. */
    private static void openAtBlock(ServerPlayer player, BlockVariantPlot plot,
                                     ServerLevel level, BlockHitResult bhit) {
        BlockPos worldPos = bhit.getBlockPos();
        BlockPos localPos = worldPos.subtract(plot.origin());
        if (!plot.inBoundsTolerant(localPos)) {
            actionBar(player, "Container is outside the editor plot", ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        Direction face = bhit.getDirection();
        Vec3 up = computeUp(face, player);
        OPEN.put(player.getUUID(), new OpenMenu(plot.key(), clampedLocal, face, up,
            LootPrefabStore.CATEGORY_LOOT));
        sendSync(player, plot, clampedLocal, clampedWorld, face, up);
    }

    /**
     * Open the menu for a supported entity hit (armor stand / item frame).
     * Uses the entity's floored block position as {@code localPos} — same
     * key {@link EntityVariantApplicator} reads at spawn time — and orients
     * the menu cardinally toward the player so it materialises in a readable
     * orientation regardless of the entity's facing.
     */
    private static void openAtEntity(ServerPlayer player, BlockVariantPlot plot,
                                      ServerLevel level, EntityHitResult ehit) {
        Entity entity = ehit.getEntity();
        BlockPos worldPos = entity.blockPosition();
        BlockPos localPos = worldPos.subtract(plot.origin());
        if (!plot.inBoundsTolerant(localPos)) {
            actionBar(player, "Entity is outside the editor plot", ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        // Face the player horizontally — vertical Y faces would force the
        // existing computeUp branch into its degenerate path. fromYRot returns
        // a horizontal cardinal direction; flipping by 180° points it at the
        // player, so the menu's front faces the author.
        Direction face = Direction.fromYRot(player.getYRot() + 180);
        Vec3 up = computeUp(face, player);
        String category = entity instanceof ArmorStand
            ? LootPrefabStore.CATEGORY_ARMOR_STAND
            : LootPrefabStore.CATEGORY_ITEM_FRAME;
        OPEN.put(player.getUUID(), new OpenMenu(plot.key(), clampedLocal, face, up, category));
        sendSync(player, plot, clampedLocal, clampedWorld, face, up);
    }

    /**
     * Category of the menu the player currently has open, or
     * {@link LootPrefabStore#CATEGORY_LOOT} when no menu is open (safe
     * fallback for the save flow). Read by
     * {@link games.brennan.dungeontrain.net.SaveLootPrefabPacket} to route
     * the saved prefab to the right creative tab.
     */
    public static String categoryFor(ServerPlayer player) {
        OpenMenu open = OPEN.get(player.getUUID());
        return open == null ? LootPrefabStore.CATEGORY_LOOT : open.category();
    }

    /**
     * Entity raycast for the {@link #toggle} menu. Mirrors the vanilla
     * {@code Entity#pick}-like pattern using
     * {@link ProjectileUtil#getEntityHitResult}, restricted to entity types
     * the C menu can meaningfully edit — currently
     * {@link ArmorStand}, {@link ItemFrame}, {@link GlowItemFrame}. Kept in
     * sync with {@link EntityVariantApplicator}'s supported-id list so the
     * menu never opens on an entity the applicator would ignore at spawn.
     */
    @javax.annotation.Nullable
    private static EntityHitResult pickSupportedEntity(ServerPlayer player, double reach) {
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 viewVec = player.getViewVector(1.0f);
        Vec3 endVec = eyePos.add(viewVec.scale(reach));
        AABB search = player.getBoundingBox()
            .expandTowards(viewVec.scale(reach))
            .inflate(1.0);
        return ProjectileUtil.getEntityHitResult(
            player.level(), player, eyePos, endVec, search,
            ContainerContentsMenuController::isSupportedEditEntity);
    }

    /** Entity types the C menu accepts as edit targets. */
    private static boolean isSupportedEditEntity(Entity entity) {
        return entity instanceof ArmorStand
            || entity instanceof ItemFrame
            || entity instanceof GlowItemFrame;
    }

    private static Vec3 computeUp(Direction face, Player player) {
        if (face.getAxis() != Direction.Axis.Y) return new Vec3(0, 1, 0);
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() < 1.0e-6) horizontal = new Vec3(0, 0, 1);
        return horizontal.normalize();
    }

    private static BlockPos clampToFootprint(BlockPos localPos, BlockVariantPlot plot) {
        net.minecraft.core.Vec3i f = plot.footprint();
        int x = Math.max(0, Math.min(f.getX() - 1, localPos.getX()));
        int y = Math.max(0, Math.min(f.getY() - 1, localPos.getY()));
        int z = Math.max(0, Math.min(f.getZ() - 1, localPos.getZ()));
        return new BlockPos(x, y, z);
    }

    private static void sendSync(ServerPlayer player, BlockVariantPlot plot,
                                 BlockPos localPos, BlockPos worldPos,
                                 Direction face, Vec3 up) {
        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool pool = store.poolAt(localPos);
        String link = store.linkAt(localPos);
        BlockState state = player.serverLevel().getBlockState(worldPos);
        int containerSize = ContainerContentsRoller.slotsForContainer(state);
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, face, up, pool, containerSize, link));
    }

    private static ContainerContentsSyncPacket buildSyncPacket(
        BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos,
        Direction face, Vec3 up, ContainerContentsPool pool, int containerSize,
        String link
    ) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        Vec3 anchor = faceCentre.add(normal.scale(0.02));
        Vec3 right = up.cross(normal).normalize();

        List<ContainerContentsSyncPacket.Entry> entries = new ArrayList<>(pool.entries().size());
        for (ContainerContentsEntry e : pool.entries()) {
            entries.add(new ContainerContentsSyncPacket.Entry(
                e.itemId().toString(), e.count(), e.weight(),
                e.randomDurability(), e.durabilityChance(),
                e.randomEnchantment(), e.enchantmentChance(),
                e.slotOverride()));
        }
        return new ContainerContentsSyncPacket(plot.key(), localPos, entries,
            pool.fillMin(), pool.fillMax(), containerSize, anchor, right, up, link);
    }

    /**
     * Open the container menu at {@code (plot, localPos)} anchored on
     * {@code face}/{@code up} without doing a raycast. Used by the
     * block-variant menu's "click linked row" flow: the caller already
     * knows the cell + face from the block-variant menu's own anchor.
     *
     * <p>Caller's responsibility: the target cell must contain a
     * container-state block (chest/barrel/etc) — usually arranged by
     * doing a {@code PREVIEW_ENTRY} immediately before calling this so
     * the variant's barrel is placed at the cell.</p>
     */
    public static void openAt(ServerPlayer player, BlockVariantPlot plot,
                              BlockPos localPos, Direction face, Vec3 up) {
        if (!player.hasPermissions(2)) {
            actionBar(player, "Container contents menu requires OP", ChatFormatting.RED);
            return;
        }
        BlockPos clamped = clampToFootprint(localPos, plot);
        BlockPos worldPos = plot.origin().offset(clamped);
        // External openAt callers target container blocks (variant preview path) —
        // default to the loot category so saves from this entry land in the
        // Containers / Loot tab as before.
        OPEN.put(player.getUUID(), new OpenMenu(plot.key(), clamped, face, up,
            LootPrefabStore.CATEGORY_LOOT));
        sendSync(player, plot, clamped, worldPos, face, up);
    }

    /**
     * Re-send the open menu's sync packet for {@code player} if the menu is
     * currently anchored at {@code (plotKey, localPos)}. Used by
     * {@link games.brennan.dungeontrain.net.SaveLootPrefabPacket} and
     * {@link games.brennan.dungeontrain.event.PrefabUseHandler} after they
     * mutate the link state behind the controller's back.
     */
    public static void resyncIfOpen(ServerPlayer player, String plotKey, BlockPos localPos) {
        OpenMenu open = OPEN.get(player.getUUID());
        if (open == null) return;
        if (!open.plotKey().equals(plotKey)) return;
        if (!open.localPos().equals(localPos)) return;
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(plotKey)) return;
        BlockPos worldPos = plot.origin().offset(localPos);
        sendSync(player, plot, localPos, worldPos, open.face(), open.up());
    }

    public static void applyEdit(ServerPlayer player, ContainerContentsEditPacket packet) {
        if (!player.hasPermissions(2)) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: player {} not OP",
                player.getName().getString());
            return;
        }
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(packet.plotKey())) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.plotKey());
            return;
        }
        BlockPos localPos = packet.localPos();
        if (!plot.inBounds(localPos)) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: localPos {} out of bounds",
                localPos);
            return;
        }

        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool current = store.poolAt(localPos);
        ContainerContentsPool next = current;
        boolean dirty = false;

        switch (packet.op()) {
            case ADD -> {
                ResourceLocation id;
                int count;
                if (packet.itemId() != null && !packet.itemId().isEmpty()) {
                    id = ResourceLocation.tryParse(packet.itemId());
                    if (id == null) {
                        actionBar(player, "Bad item id: " + packet.itemId(), ChatFormatting.YELLOW);
                        return;
                    }
                    count = 1;
                } else {
                    // Empty itemId → use main-hand item.
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty() || held.getItem() == Items.AIR) {
                        actionBar(player, "Hold an item or use the search screen to add",
                            ChatFormatting.YELLOW);
                        return;
                    }
                    Item item = held.getItem();
                    id = BuiltInRegistries.ITEM.getKey(item);
                    count = held.getCount();
                }
                if (current.size() >= ContainerContentsPool.MAX_ENTRIES) {
                    actionBar(player, "Pool full (max " + ContainerContentsPool.MAX_ENTRIES + ")",
                        ChatFormatting.YELLOW);
                    return;
                }
                next = current.added(new ContainerContentsEntry(id, count, 1));
                dirty = true;
            }
            case REMOVE -> {
                if (current.isEmpty()) return;
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                next = current.removed(idx);
                dirty = true;
            }
            case CLEAR -> {
                if (current.isEmpty()) return;
                next = ContainerContentsPool.empty();
                dirty = true;
            }
            case BUMP_WEIGHT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newWeight = Math.max(1, e.weight() + packet.delta());
                next = current.replaced(idx, e.withWeight(newWeight));
                dirty = true;
            }
            case BUMP_COUNT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newCount;
                if (packet.delta() < 0 && e.count() <= 1) {
                    newCount = 64;
                } else if (packet.delta() > 0 && e.count() >= 64) {
                    newCount = 1;
                } else {
                    newCount = Math.max(1, Math.min(64, e.count() + packet.delta()));
                }
                next = current.replaced(idx, e.withCount(newCount));
                dirty = true;
            }
            case BUMP_FILL_MIN -> {
                // Wrap in [0, upperBound] where upperBound is fillMax (or
                // MAX_FILL_BOUND when fillMax == FILL_ALL).
                int cur = current.fillMin();
                int upperBound = current.fillMax() == ContainerContentsPool.FILL_ALL
                    ? ContainerContentsPool.MAX_FILL_BOUND
                    : current.fillMax();
                int next0;
                if (packet.delta() < 0 && cur <= 0) {
                    next0 = upperBound;
                } else if (packet.delta() > 0 && cur >= upperBound) {
                    next0 = 0;
                } else {
                    next0 = Math.max(0, Math.min(upperBound, cur + packet.delta()));
                }
                next = current.withFillMin(next0);
                dirty = true;
            }
            case BUMP_FILL_MAX -> {
                // Cycle through FILL_ALL → 0 → 1 … → MAX → FILL_ALL.
                int cur = current.fillMax();
                int next0;
                if (packet.delta() > 0) {
                    next0 = cur == ContainerContentsPool.FILL_ALL ? 0 : cur + 1;
                    if (next0 > ContainerContentsPool.MAX_FILL_BOUND) next0 = ContainerContentsPool.FILL_ALL;
                } else {
                    next0 = cur == ContainerContentsPool.FILL_ALL
                        ? ContainerContentsPool.MAX_FILL_BOUND
                        : (cur == 0 ? ContainerContentsPool.FILL_ALL : cur - 1);
                }
                // Bring fillMin down if it would exceed the new ceiling.
                int newMin = current.fillMin();
                if (next0 != ContainerContentsPool.FILL_ALL && newMin > next0) newMin = next0;
                next = current.withFillMax(next0).withFillMin(newMin);
                dirty = true;
            }
            case TOGGLE_RAND_DUR -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx, e.withRandomDurability(!e.randomDurability()));
                dirty = true;
            }
            case TOGGLE_RAND_ENCH -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx, e.withRandomEnchantment(!e.randomEnchantment()));
                dirty = true;
            }
            case BUMP_DUR_CHANCE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx,
                    e.withDurabilityChance(wrapChance(e.durabilityChance(), packet.delta())));
                dirty = true;
            }
            case BUMP_ENCH_CHANCE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx,
                    e.withEnchantmentChance(wrapChance(e.enchantmentChance(), packet.delta())));
                dirty = true;
            }
            case CYCLE_SLOT_ASSIGN -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx, e.cycleSlotOverride());
                dirty = true;
            }
            case UNLINK -> {
                String prev = store.linkAt(localPos);
                if (prev == null) return;
                store.clearLink(localPos);
                try {
                    store.save();
                } catch (IOException e) {
                    LOGGER.error("[DungeonTrain] ContainerContentsStore unlink save failed for {}: {}",
                        plot.key(), e.toString());
                    actionBar(player, "Unlink save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
                    return;
                }
                actionBar(player, "Unlinked container from '" + prev + "'", ChatFormatting.GREEN);
                resyncSameFace(player, plot, localPos);
                return;
            }
        }

        if (!dirty) return;

        // If this position is linked, route the mutation to the template
        // instead of writing a local pool. The store's link map already
        // dropped the local pool when the link was set, so writing local
        // here would re-introduce duplication and fight the live-reference
        // model.
        String linked = store.linkAt(localPos);
        if (linked != null) {
            Optional<LootPrefabStore.Data> templ = LootPrefabStore.load(linked);
            if (templ.isEmpty()) {
                actionBar(player, "Linked template '" + linked + "' missing — unlink first",
                    ChatFormatting.YELLOW);
                return;
            }
            try {
                // Preserve the existing template's category — edits propagated
                // via the link-readthrough path don't reclassify the prefab.
                LootPrefabStore.save(linked, next, templ.get().sourceBlock(), templ.get().category());
                ContainerContentsLinkPropagator.propagate(level, linked);
                net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                    games.brennan.dungeontrain.net.PrefabRegistrySyncPacket.fromRegistries());
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Linked template save failed for {}: {}",
                    linked, e.toString());
                actionBar(player, "Template save failed: " + e.getClass().getSimpleName(),
                    ChatFormatting.RED);
                return;
            }
            // CLEAR on a linked container empties the template — close the
            // menu since the pool is now empty.
            if (next.isEmpty() && packet.op() == ContainerContentsEditPacket.Op.CLEAR) {
                OPEN.remove(player.getUUID());
                DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
                return;
            }
            resyncSameFace(player, plot, localPos);
            return;
        }

        store.putPool(localPos, next);
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] ContainerContentsStore save failed for {}: {}",
                plot.key(), e.toString());
            actionBar(player, "Save failed: " + e.getClass().getSimpleName(), ChatFormatting.RED);
        }
        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "used_contents_variant");
        if (packet.op() == ContainerContentsEditPacket.Op.ADD) {
            games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
                .trigger(player, "made_contents_variant");
        }

        // Re-roll the local chest so the edit takes visible effect immediately.
        // The linked-template path re-rolls via ContainerContentsLinkPropagator;
        // local pools were missing the equivalent step, so author edits only
        // showed up at the NEXT carriage spawn — confusing for an authoring
        // surface where the editor-plot chest IS the preview.
        rerollLocalContainer(level, plot, localPos, next);

        if (next.isEmpty() && packet.op() == ContainerContentsEditPacket.Op.CLEAR) {
            OPEN.remove(player.getUUID());
            DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
            return;
        }
        resyncSameFace(player, plot, localPos);
    }

    /**
     * Re-roll the BlockEntity at {@code (plot.origin + localPos)} using
     * {@code pool}, mirroring {@link ContainerContentsLinkPropagator}'s
     * per-position logic. Silent no-op when no BE is present (the slot might
     * not currently hold a container block — e.g. the variant is empty);
     * caller does not depend on the result. Uses
     * {@code carriageIndex = 0} to match {@link
     * games.brennan.dungeontrain.event.PrefabUseHandler}'s editor-plot rolls.
     */
    private static void rerollLocalContainer(ServerLevel level, BlockVariantPlot plot,
                                              BlockPos localPos, ContainerContentsPool pool) {
        BlockPos worldPos = plot.origin().offset(localPos);
        BlockEntity be = level.getBlockEntity(worldPos);
        if (be == null) return;
        BlockState state = level.getBlockState(worldPos);
        CompoundTag baseNbt = be.saveWithFullMetadata(level.registryAccess());
        CompoundTag rolled = ContainerContentsRoller.roll(
            pool, state, localPos,
            level.getSeed(), /*carriageIndex*/ 0,
            baseNbt, level.registryAccess(), level);
        if (rolled == null) return;
        be.loadCustomOnly(rolled, level.registryAccess());
        be.setChanged();
        ChiseledBookshelfSync.syncIfNeeded(level, worldPos);
    }

    private static void resyncSameFace(ServerPlayer player, BlockVariantPlot plot, BlockPos localPos) {
        OpenMenu open = OPEN.get(player.getUUID());
        Direction face;
        Vec3 up;
        if (open != null
            && open.plotKey().equals(plot.key())
            && open.localPos().equals(localPos)) {
            face = open.face();
            up = open.up();
        } else {
            face = Direction.UP;
            up = computeUp(face, player);
        }
        BlockPos worldPos = plot.origin().offset(localPos);
        sendSync(player, plot, localPos, worldPos, face, up);
    }

    private static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(text).withStyle(colour), true);
    }

    /**
     * Wrap a 0-100 chance value by {@code delta}, mirroring the
     * {@code BUMP_FILL_MIN} wrap behaviour: positive delta past 100 wraps to 0,
     * negative past 0 wraps to 100. Mid-range bumps clamp to the [0, 100] band.
     */
    private static int wrapChance(int current, int delta) {
        if (delta > 0 && current >= 100) return 0;
        if (delta < 0 && current <= 0) return 100;
        int next = current + delta;
        if (next < 0) next = 0;
        if (next > 100) next = 100;
        return next;
    }
}
