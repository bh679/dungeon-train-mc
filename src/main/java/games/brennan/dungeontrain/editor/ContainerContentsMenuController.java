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

import static games.brennan.dungeontrain.editor.EditorFeedback.actionBar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
public final class ContainerContentsMenuController extends PlayerMenuController<ContainerContentsMenuController.OpenMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    /**
     * Per-player open-menu state. {@code category} reflects what the player
     * was looking at when the menu opened — {@link LootPrefabStore#CATEGORY_LOOT}
     * for a container block, {@link LootPrefabStore#CATEGORY_ARMOR_STAND} or
     * {@link LootPrefabStore#CATEGORY_ITEM_FRAME} for the corresponding entity.
     * The save flow reads this to route prefabs to the right creative tab.
     */
    record OpenMenu(String plotKey, BlockPos localPos, Direction face, Vec3 up, String category) {}

    public static final ContainerContentsMenuController INSTANCE = new ContainerContentsMenuController();

    private ContainerContentsMenuController() {
        super("ContainerContentsMenu", "container contents");
    }

    @Override
    protected String requiresOpKey() {
        return "chat.dungeontrain.editor_bar.contents_menu.requires_op";
    }

    @Override
    protected String keyOf(OpenMenu state) {
        return state.plotKey();
    }

    /** The undo path's resync: wherever the menu is anchored, re-send it. */
    @Override
    protected void resync(ServerPlayer player, OpenMenu open) {
        resyncIfOpen(player, open.plotKey(), open.localPos());
    }

    public static void toggle(ServerPlayer player, boolean open) {
        if (!open) {
            INSTANCE.remove(player);
            DungeonTrainNet.sendTo(player, ContainerContentsSyncPacket.empty());
            return;
        }
        if (!INSTANCE.requireOp(player)) return;

        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        // Block raycast first — chest/barrel/etc.
        HitResult blockHit = player.pick(TOGGLE_REACH, 1.0f, false);
        BlockHitResult validBlockHit = null;
        if (blockHit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS) {
            BlockState targetState = level.getBlockState(bhit.getBlockPos());
            if (ContainerContentsRoller.isLootAuthorable(targetState)) {
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

        if (validBlockHit == null && entityHit == null) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.look_at_container"),
                ChatFormatting.YELLOW);
            return;
        }

        // Resolved from what is being looked at, not from where the player is standing: authoring
        // into a plot from outside it is ordinary — you back off a carriage to see the wall you are
        // filling — and a menu that refused there was reading the wrong end of the gesture.
        boolean blockFirst = validBlockHit != null && blockDist2 <= entityDist2;
        BlockPos target = blockFirst
            ? validBlockHit.getBlockPos()
            : BlockPos.containing(entityHit.getLocation());
        BlockVariantPlot plot = BlockVariantPlot.resolveAtPos(level, target, dims);
        if (plot == null) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.block_not_in_plot"), ChatFormatting.YELLOW);
            return;
        }

        if (blockFirst) {
            openAtBlock(player, plot, level, validBlockHit);
            return;
        }
        openAtEntity(player, plot, level, entityHit);
    }

    /** Open the menu for a container block hit — the original code path. */
    private static void openAtBlock(ServerPlayer player, BlockVariantPlot plot,
                                     ServerLevel level, BlockHitResult bhit) {
        BlockPos worldPos = bhit.getBlockPos();
        BlockPos localPos = worldPos.subtract(plot.origin());
        if (!plot.inBoundsTolerant(localPos)) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.container_outside_plot"), ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = MenuPanelGeometry.clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        prefillVault(level, plot, clampedLocal, clampedWorld);

        Direction face = bhit.getDirection();
        Vec3 up = MenuPanelGeometry.computeUp(face, player);
        INSTANCE.put(player, new OpenMenu(plot.key(), clampedLocal, face, up,
            LootPrefabStore.CATEGORY_LOOT));
        sendSync(player, plot, clampedLocal, clampedWorld, face, up);
    }

    /**
     * A vault with no loot of its own opens with vanilla's trial reward already in it, as an
     * ordinary local pool the author can edit: that is what it rolls at spawn anyway, so the menu
     * shows it instead of an empty list. A linked vault keeps its prefab.
     */
    private static void prefillVault(ServerLevel level, BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos) {
        if (!VaultLoot.isVault(level.getBlockState(worldPos))) return;
        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        if (store.linkAt(localPos) != null || !store.poolAt(localPos).isEmpty()) return;
        ContainerContentsPool pool = VaultLoot.defaultPool(ContainerContentsPool.DEFAULT_VAULT_KEY);
        if (pool.isEmpty()) return;
        store.putPool(localPos, pool);
        try {
            store.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Vault prefill save failed for {}: {}", plot.key(), e.toString());
            return;
        }
        rerollLocalContainer(level, plot, localPos, pool);
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
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.entity_outside_plot"), ChatFormatting.YELLOW);
            return;
        }
        BlockPos clampedLocal = MenuPanelGeometry.clampToFootprint(localPos, plot);
        BlockPos clampedWorld = plot.origin().offset(clampedLocal);

        // Face the player horizontally — vertical Y faces would force the
        // existing computeUp branch into its degenerate path. fromYRot returns
        // a horizontal cardinal direction; flipping by 180° points it at the
        // player, so the menu's front faces the author.
        Direction face = Direction.fromYRot(player.getYRot() + 180);
        Vec3 up = MenuPanelGeometry.computeUp(face, player);
        String category = entity instanceof ArmorStand
            ? LootPrefabStore.CATEGORY_ARMOR_STAND
            : LootPrefabStore.CATEGORY_ITEM_FRAME;
        INSTANCE.put(player, new OpenMenu(plot.key(), clampedLocal, face, up, category));
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
        OpenMenu open = INSTANCE.open(player);
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

    private static void sendSync(ServerPlayer player, BlockVariantPlot plot,
                                 BlockPos localPos, BlockPos worldPos,
                                 Direction face, Vec3 up) {
        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool pool = store.poolAt(localPos);
        String link = store.linkAt(localPos);
        BlockState state = player.serverLevel().getBlockState(worldPos);
        int containerSize = ContainerContentsRoller.slotsForContainer(state);
        String vaultKey = VaultLoot.isVault(state) ? pool.vaultKey() : null;
        DungeonTrainNet.sendTo(player,
            buildSyncPacket(plot, localPos, worldPos, face, up, pool, containerSize, link, vaultKey));
    }

    private static ContainerContentsSyncPacket buildSyncPacket(
        BlockVariantPlot plot, BlockPos localPos, BlockPos worldPos,
        Direction face, Vec3 up, ContainerContentsPool pool, int containerSize,
        String link, String vaultKey
    ) {
        Vec3 normal = MenuPanelGeometry.normalOf(face);
        Vec3 anchor = MenuPanelGeometry.faceAnchor(worldPos, face);
        Vec3 right = MenuPanelGeometry.right(up, normal);

        List<ContainerContentsSyncPacket.Entry> entries = new ArrayList<>(pool.entries().size());
        for (ContainerContentsEntry e : pool.entries()) {
            entries.add(new ContainerContentsSyncPacket.Entry(
                e.itemId().toString(), e.count(), e.weight(),
                e.randomDurability(), e.durabilityChance(),
                e.randomEnchantment(), e.enchantmentChance(),
                e.slotOverride(),
                e.potionId() == null ? "" : e.potionId().toString(),
                e.scaleWithDistance(),
                e.potionForm().ordinal(),
                e.book() == null ? "" : e.book().title()));
        }
        return new ContainerContentsSyncPacket(plot.key(), localPos, entries,
            pool.fillMin(), pool.fillMax(), containerSize, anchor, right, up, link, vaultKey);
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
        if (!INSTANCE.requireOp(player)) return;
        BlockPos clamped = MenuPanelGeometry.clampToFootprint(localPos, plot);
        BlockPos worldPos = plot.origin().offset(clamped);
        // External openAt callers target container blocks (variant preview path) —
        // default to the loot category so saves from this entry land in the
        // Containers / Loot tab as before.
        INSTANCE.put(player, new OpenMenu(plot.key(), clamped, face, up,
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
        OpenMenu open = INSTANCE.open(player);
        if (open == null) return;
        if (!open.plotKey().equals(plotKey)) return;
        if (!open.localPos().equals(localPos)) return;
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveByKey(level, plotKey, dims);
        if (plot == null) return;
        BlockPos worldPos = plot.origin().offset(localPos);
        sendSync(player, plot, localPos, worldPos, open.face(), open.up());
    }

    public static void applyEdit(ServerPlayer player, ContainerContentsEditPacket packet) {
        // Authorised against the menu the server itself opened for this player, rather than against
        // where they are standing now — which is both stronger (the key came from the server, not
        // the packet) and what lets the author step off the plot while the menu is up.
        if (INSTANCE.authorise(player, packet.plotKey()) == null) return;
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveByKey(level, packet.plotKey(), dims);
        if (plot == null) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: no plot for '{}'",
                packet.plotKey());
            return;
        }
        BlockPos localPos = packet.localPos();
        if (!plot.inBounds(localPos)) {
            LOGGER.warn("[DungeonTrain] ContainerContentsMenu edit rejected: localPos {} out of bounds",
                localPos);
            return;
        }
        INSTANCE.armUndo(player);

        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        ContainerContentsPool current = store.poolAt(localPos);
        ContainerContentsPool next = current;
        boolean dirty = false;

        switch (packet.op()) {
            case ADD -> {
                ResourceLocation id;
                int count;
                ResourceLocation potionId = null;
                StoredBook book = null;
                if (packet.itemId() != null && !packet.itemId().isEmpty()) {
                    id = ResourceLocation.tryParse(packet.itemId());
                    if (id == null) {
                        actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.bad_item_id", packet.itemId()), ChatFormatting.YELLOW);
                        return;
                    }
                    count = 1;
                } else {
                    // Empty itemId → use main-hand item.
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty() || held.getItem() == Items.AIR) {
                        actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.hold_item_or_search"),
                            ChatFormatting.YELLOW);
                        return;
                    }
                    Item item = held.getItem();
                    id = BuiltInRegistries.ITEM.getKey(item);
                    count = held.getCount();
                    // A potion added from the hand keeps its exact potion — a Potion of
                    // Healing spawns as one, not as a bare bottle for the roller to reinterpret.
                    potionId = ContainerContentsPotions.potionIdOf(held);
                    // Likewise a signed book keeps its title, author and pages.
                    book = ContainerContentsBooks.bookOf(held, player.registryAccess());
                }
                if (current.size() >= ContainerContentsPool.MAX_ENTRIES) {
                    actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.pool_full", ContainerContentsPool.MAX_ENTRIES),
                        ChatFormatting.YELLOW);
                    return;
                }
                next = current.added(new ContainerContentsEntry(id, count, 1).withPotion(potionId).withBook(book));
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
            // Typed weight from the cmd-click number pad. Same clamp as the stepper,
            // so typing can't reach a value the arrows couldn't.
            case SET_WEIGHT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newWeight = Math.max(1, packet.delta());
                if (newWeight == e.weight()) return;
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
            // Typed values from the cmd-click number pad. Same bounds as the steppers, so typing
            // can't reach a value the clicks couldn't.
            case SET_FILL_MIN -> {
                int upperBound = current.fillMax() == ContainerContentsPool.FILL_ALL
                    ? ContainerContentsPool.MAX_FILL_BOUND
                    : current.fillMax();
                int next0 = Math.max(0, Math.min(upperBound, packet.delta()));
                if (next0 == current.fillMin()) return;
                next = current.withFillMin(next0);
                dirty = true;
            }
            case SET_FILL_MAX -> {
                int next0 = packet.delta() < 0
                    ? ContainerContentsPool.FILL_ALL
                    : Math.min(ContainerContentsPool.MAX_FILL_BOUND, packet.delta());
                if (next0 == current.fillMax()) return;
                int newMin = current.fillMin();
                if (next0 != ContainerContentsPool.FILL_ALL && newMin > next0) newMin = next0;
                next = current.withFillMax(next0).withFillMin(newMin);
                dirty = true;
            }
            case SET_COUNT -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int newCount = Math.max(1, Math.min(64, packet.delta()));
                if (newCount == e.count()) return;
                next = current.replaced(idx, e.withCount(newCount));
                dirty = true;
            }
            case SET_DUR_CHANCE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int chance = Math.max(0, Math.min(100, packet.delta()));
                if (chance == e.durabilityChance()) return;
                next = current.replaced(idx, e.withDurabilityChance(chance));
                dirty = true;
            }
            case SET_ENCH_CHANCE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                int chance = Math.max(0, Math.min(100, packet.delta()));
                if (chance == e.enchantmentChance()) return;
                next = current.replaced(idx, e.withEnchantmentChance(chance));
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
            case TOGGLE_SCALE -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx, e.withScaleWithDistance(!e.scaleWithDistance()));
                dirty = true;
            }
            case CYCLE_POTION_FORM -> {
                int idx = packet.entryIndex();
                if (idx < 0 || idx >= current.size()) return;
                ContainerContentsEntry e = current.entries().get(idx);
                next = current.replaced(idx, e.cyclePotionForm());
                dirty = true;
            }
            case CYCLE_VAULT_KEY -> {
                String nextKey = VaultLoot.nextKey(current.vaultKey());
                String linkedId = store.linkAt(localPos);
                // Linked to one of the two default vault prefabs: link the other one. Writing the
                // other default's loot into the linked prefab would overwrite that prefab's loot.
                String relink = linkedId == null ? null : VaultLoot.otherDefaultPrefab(linkedId);
                if (relink != null) {
                    store.setLink(localPos, relink);
                    try {
                        store.save();
                    } catch (IOException e) {
                        LOGGER.error("[DungeonTrain] Vault relink save failed for {}: {}", plot.key(), e.toString());
                        actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_failed",
                            e.getClass().getSimpleName()), ChatFormatting.RED);
                        return;
                    }
                    rerollLocalContainer(level, plot, localPos, store.poolAt(localPos));
                    resyncSameFace(player, plot, localPos);
                    return;
                }
                // Any other linked prefab only changes its key; a local pool that is still its
                // key's default loot follows the vault type to the other default.
                next = linkedId == null ? VaultLoot.withKey(current, nextKey) : current.withVaultKey(nextKey);
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
                    actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.unlink_save_failed", e.getClass().getSimpleName()), ChatFormatting.RED);
                    return;
                }
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.unlinked", prev), ChatFormatting.GREEN);
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
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.linked_template_missing", linked),
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
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.contents_menu.template_save_failed", e.getClass().getSimpleName()),
                    ChatFormatting.RED);
                return;
            }
            // CLEAR on a linked container empties the template — close the
            // menu since the pool is now empty.
            if (next.isEmpty() && packet.op() == ContainerContentsEditPacket.Op.CLEAR) {
                INSTANCE.remove(player);
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
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_failed", e.getClass().getSimpleName()), ChatFormatting.RED);
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
            INSTANCE.remove(player);
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
        VaultLoot.syncState(level, worldPos);
    }

    private static void resyncSameFace(ServerPlayer player, BlockVariantPlot plot, BlockPos localPos) {
        OpenMenu open = INSTANCE.open(player);
        Direction face;
        Vec3 up;
        if (open != null
            && open.plotKey().equals(plot.key())
            && open.localPos().equals(localPos)) {
            face = open.face();
            up = open.up();
        } else {
            face = Direction.UP;
            up = MenuPanelGeometry.computeUp(face, player);
        }
        BlockPos worldPos = plot.origin().offset(localPos);
        sendSync(player, plot, localPos, worldPos, face, up);
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
