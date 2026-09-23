package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.debug.DebugFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Applies a content variant's linked loot prefab to an entity's NBT before
 * {@link net.minecraft.world.entity.EntityType#create} is called. Mirrors
 * {@link ContainerContentsPlacement} for block-side variants — same
 * {@link CarriageContentsVariantBlocks#resolve} lookup, same
 * {@link LootPrefabStore} prefab loading, same deterministic roll seeding via
 * {@link ContainerContentsRoller}. The output shape differs: instead of an
 * {@code Items} ListTag merged into a block-entity, rolled stacks are mapped
 * to entity-native slots:
 *
 * <ul>
 *   <li>{@code minecraft:armor_stand} — auto-slot each rolled stack via
 *       {@link Equipable#get(ItemStack)} with a shield/mainhand fallback,
 *       writing to the fixed-length {@code ArmorItems[4]} and
 *       {@code HandItems[2]} lists.
 *       Every slot the pool has entries for is rerolled (the template's
 *       baked-in item there is replaced, or cleared if the fill count
 *       doesn't pick that slot); slots the pool can't fill keep the
 *       template's baked-in items.</li>
 *   <li>{@code minecraft:item_frame} / {@code minecraft:glow_item_frame} — the
 *       first non-empty rolled stack becomes the frame's {@code Item} NBT.</li>
 * </ul>
 *
 * <p>Determinism: rolls use the same {@code (localPos, worldSeed, carriageIndex)}
 * basis as the block path, so the same template at the same seed always spawns
 * the same equipment.</p>
 */
public final class EntityVariantApplicator {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String ID_ARMOR_STAND = "minecraft:armor_stand";
    public static final String ID_ITEM_FRAME = "minecraft:item_frame";
    public static final String ID_GLOW_ITEM_FRAME = "minecraft:glow_item_frame";

    /** Armor stand has 4 armor slots + 2 hand slots = 6 candidate stacks per roll. */
    private static final int ARMOR_STAND_SLOTS = 6;
    /** Item frame holds a single stack. */
    private static final int ITEM_FRAME_SLOTS = 1;

    private static final String NBT_ID = "id";
    private static final String NBT_ARMOR_ITEMS = "ArmorItems";
    private static final String NBT_HAND_ITEMS = "HandItems";
    private static final String NBT_ITEM = "Item";

    private EntityVariantApplicator() {}

    /**
     * Apply the variant + cell-level pool at {@code localPos} (interior-local
     * coords) to {@code entityNbt} in-place. Resolution order mirrors the
     * block-side
     * {@link games.brennan.dungeontrain.train.CarriageContentsPlacer
     * applyVariantBlocks → applyContentLinks} pipeline:
     *
     * <ol>
     *   <li>Variant sidecar entry with a non-null {@code linkedLootPrefabId}
     *       wins. The variant flow chose a specific candidate for this
     *       {@code (seed, carriageIndex, localPos)} and that choice may
     *       differ per spawn.</li>
     *   <li>Else fall back to the cell-level pool from
     *       {@link ContainerContentsStore#poolAt(BlockPos)} — the same store
     *       the C-menu writes to when the player authors a link/pool on a
     *       cell directly (entity-targeted authoring lands here).</li>
     *   <li>Else no-op.</li>
     * </ol>
     *
     * No-op also when the entity's {@code id} is not one of the supported
     * types (armor stand, item frame, glow item frame), and when the
     * resolved pool is empty.
     *
     * <p>Caller is expected to pass an {@code entityNbt} that is safe to
     * mutate — in the existing spawn path that is already a {@code .copy()}
     * of the template entry's NBT.</p>
     */
    public static void applyTo(CompoundTag entityNbt, BlockPos localPos, long seed, int carriageIndex,
                                @Nullable CarriageContentsVariantBlocks sidecar,
                                @Nullable ContainerContentsStore contentsStore,
                                ServerLevel level) {
        if (entityNbt == null) return;
        String entityId = entityNbt.getString(NBT_ID);
        if (entityId.isEmpty()) return;
        if (!isSupported(entityId)) return;

        ContainerContentsPool pool = resolvePool(localPos, seed, carriageIndex, sidecar, contentsStore);
        if (pool == null || pool.isEmpty()) return;

        HolderLookup.Provider registries = level.registryAccess();

        switch (entityId) {
            case ID_ARMOR_STAND ->
                applyToArmorStand(entityNbt, pool, localPos, seed, carriageIndex, registries);
            case ID_ITEM_FRAME, ID_GLOW_ITEM_FRAME ->
                applyToItemFrame(entityNbt, pool, localPos, seed, carriageIndex, registries);
            default -> { /* filtered above */ }
        }
    }

    /**
     * Two-tier pool resolution: variant sidecar's per-spawn pick (with
     * {@code linkedLootPrefabId}) first, falling through to the cell-level
     * {@link ContainerContentsStore} pool authored via the C menu.
     */
    @Nullable
    private static ContainerContentsPool resolvePool(BlockPos localPos, long seed, int carriageIndex,
                                                      @Nullable CarriageContentsVariantBlocks sidecar,
                                                      @Nullable ContainerContentsStore contentsStore) {
        if (sidecar != null && !sidecar.isEmpty()) {
            VariantState picked = sidecar.resolve(localPos, seed, carriageIndex);
            if (picked != null) {
                String prefabId = picked.linkedLootPrefabId();
                if (prefabId != null && !prefabId.isEmpty()) {
                    Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
                    if (loaded.isPresent()) {
                        ContainerContentsPool pool = loaded.get().pool();
                        if (pool != null && !pool.isEmpty()) return pool;
                    }
                }
            }
        }
        if (contentsStore != null) {
            ContainerContentsPool pool = contentsStore.poolAt(localPos);
            if (pool != null && !pool.isEmpty()) return pool;
        }
        return null;
    }

    private static boolean isSupported(String entityId) {
        return ID_ARMOR_STAND.equals(entityId)
            || ID_ITEM_FRAME.equals(entityId)
            || ID_GLOW_ITEM_FRAME.equals(entityId);
    }

    /**
     * Roll the prefab into the armor stand's fixed-length
     * {@code ArmorItems[4]} (FEET / LEGS / CHEST / HEAD) and
     * {@code HandItems[2]} (MAINHAND / OFFHAND) NBT lists.
     *
     * <p><b>Slot-aware reroll</b> — at most one item per slot. The pool's
     * entries are bucketed by their natural slot ({@link #equipmentSlotFor});
     * every slot with at least one matching entry is cleared and rerolled
     * with a weighted-random pick from that slot's bucket. So a pool with
     * one helmet / chestplate / leggings / boots at {@code fillMin=4} always
     * produces one of each, never four helmets — and a stand saved wearing
     * leather armor rolls fresh armor each spawn instead of keeping the
     * saved set. Slots the pool has no entries for keep the template's
     * items (an armor-only pool won't strip a sword from the hand).</p>
     */
    private static void applyToArmorStand(CompoundTag entityNbt, ContainerContentsPool pool,
                                           BlockPos localPos, long seed, int carriageIndex,
                                           HolderLookup.Provider registries) {
        java.util.EnumSet<EquipmentSlot> covered = coveredSlots(pool);
        if (covered.isEmpty()) return;

        ListTag armorItems = readOrInitList(entityNbt, NBT_ARMOR_ITEMS, 4);
        ListTag handItems = readOrInitList(entityNbt, NBT_HAND_ITEMS, 2);
        for (EquipmentSlot slot : covered) {
            setStandSlotTag(armorItems, handItems, slot, new CompoundTag());
        }

        java.util.Map<EquipmentSlot, ItemStack> rolled = rollForArmorStandSlots(
            pool, covered, localPos, seed, carriageIndex, registries);
        for (java.util.Map.Entry<EquipmentSlot, ItemStack> e : rolled.entrySet()) {
            ItemStack stack = e.getValue();
            if (stack.isEmpty()) continue;
            CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
            setStandSlotTag(armorItems, handItems, e.getKey(), stackTag);
        }

        entityNbt.put(NBT_ARMOR_ITEMS, armorItems);
        entityNbt.put(NBT_HAND_ITEMS, handItems);

        if (DebugFlags.logLootRolls()) {
            LOGGER.info("[DT-armor-stand-variant] rerolled slots={} filled={} at localPos={} seed={} carriageIdx={}",
                covered, rolled.keySet(), localPos, seed, carriageIndex);
        }
    }

    /** Write {@code stackTag} into the stand's NBT list slot for {@code slot}. */
    private static void setStandSlotTag(ListTag armorItems, ListTag handItems,
                                        EquipmentSlot slot, CompoundTag stackTag) {
        switch (slot) {
            case FEET -> armorItems.set(0, stackTag);
            case LEGS -> armorItems.set(1, stackTag);
            case CHEST -> armorItems.set(2, stackTag);
            case HEAD -> armorItems.set(3, stackTag);
            case MAINHAND -> handItems.set(0, stackTag);
            case OFFHAND -> handItems.set(1, stackTag);
            default -> { /* BODY / SADDLE — armor stand has no such slot */ }
        }
    }

    /**
     * Armor-stand slots {@code pool} has at least one entry for, keyed by
     * each item's natural slot. BODY is excluded — armor stands have none.
     */
    private static java.util.EnumSet<EquipmentSlot> coveredSlots(ContainerContentsPool pool) {
        java.util.EnumSet<EquipmentSlot> out = java.util.EnumSet.noneOf(EquipmentSlot.class);
        out.addAll(bucketBySlot(pool).keySet());
        out.remove(EquipmentSlot.BODY);
        return out;
    }

    /** Pool entries bucketed by the equipment slot their item naturally targets. */
    private static java.util.EnumMap<EquipmentSlot, java.util.List<ContainerContentsEntry>> bucketBySlot(
            ContainerContentsPool pool) {
        java.util.EnumMap<EquipmentSlot, java.util.List<ContainerContentsEntry>> bucketsBySlot =
            new java.util.EnumMap<>(EquipmentSlot.class);
        for (ContainerContentsEntry entry : pool.entries()) {
            if (entry.isAir()) continue;
            net.minecraft.world.item.Item item = ContainerContentsRoller.resolveItem(entry.itemId());
            if (item == null) continue;
            EquipmentSlot slot = equipmentSlotFor(new ItemStack(item));
            bucketsBySlot.computeIfAbsent(slot, s -> new java.util.ArrayList<>()).add(entry);
        }
        return bucketsBySlot;
    }

    /**
     * Slot-aware roll for armor stand fills. Buckets the pool by each
     * entry's natural equipment slot, intersects with {@code candidateSlots},
     * then rolls K (uniform in {@code [fillMin, min(fillMax,
     * pickable.size())]}) of those slots — each receives a weighted-random
     * pick from its bucket.
     *
     * <p>Determinism: each picked slot uses its enum {@code ordinal()} as
     * the slot key, so the per-slot picks reuse the same mixers as the
     * chest path (independent randomization per slot, deterministic for a
     * given {@code (worldSeed, carriageIndex, localPos)}).</p>
     */
    private static java.util.Map<EquipmentSlot, ItemStack> rollForArmorStandSlots(
            ContainerContentsPool pool, java.util.EnumSet<EquipmentSlot> candidateSlots,
            BlockPos localPos, long seed, int carriageIndex,
            HolderLookup.Provider registries) {
        if (candidateSlots.isEmpty()) return java.util.Collections.emptyMap();
        java.util.EnumMap<EquipmentSlot, java.util.List<ContainerContentsEntry>> bucketsBySlot =
            bucketBySlot(pool);
        // Pickable = candidate slots ∩ slots the pool can fill.
        java.util.EnumSet<EquipmentSlot> pickable = java.util.EnumSet.copyOf(candidateSlots);
        pickable.retainAll(bucketsBySlot.keySet());
        if (pickable.isEmpty()) return java.util.Collections.emptyMap();

        int slotCount = pickable.size();
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? slotCount : Math.min(pool.fillMax(), slotCount);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = ContainerContentsRoller.rollKCount(effectiveMin, effectiveMax, localPos, seed, carriageIndex);
        if (k <= 0) return java.util.Collections.emptyMap();

        // Deterministically pick K distinct slots from pickable. Fisher-
        // Yates on the enum's natural order, seeded the same way the chest
        // path seeds its slot subset.
        EquipmentSlot[] order = pickable.toArray(new EquipmentSlot[0]);
        shuffleSlotsDeterministic(order, localPos, seed, carriageIndex);
        java.util.Map<EquipmentSlot, ItemStack> out = new java.util.EnumMap<>(EquipmentSlot.class);
        for (int i = 0; i < k && i < order.length; i++) {
            EquipmentSlot slot = order[i];
            int slotKey = slot.ordinal();
            ContainerContentsEntry picked = ContainerContentsRoller.pickFiltered(
                pool, e -> bucketsBySlot.get(slot).contains(e),
                localPos, seed, carriageIndex, slotKey);
            if (picked == null || picked.isAir()) continue;
            int count = ContainerContentsRoller.rollItemCount(picked.count(), localPos, seed, carriageIndex, slotKey);
            ItemStack stack = ContainerContentsRoller.rollItemStack(
                picked, count, localPos, seed, carriageIndex, slotKey, registries);
            if (stack.isEmpty()) continue;
            out.put(slot, stack);
        }
        return out;
    }

    /**
     * Deterministic Fisher-Yates over {@code slots}, seeded the same way the
     * chest path seeds its slot-subset shuffle so the slot-pick order is
     * reproducible across world reloads.
     */
    private static void shuffleSlotsDeterministic(EquipmentSlot[] slots,
                                                   BlockPos localPos, long seed, int carriageIndex) {
        long state = seed
            ^ ((long) localPos.getX() * 0x9E3779B97F4A7C15L)
            ^ ((long) localPos.getY() * 0xBF58476D1CE4E5B9L)
            ^ ((long) localPos.getZ() * 0x94D049BB133111EBL)
            ^ ((long) carriageIndex * 0xCAFEBABE12345678L);
        for (int i = slots.length - 1; i > 0; i--) {
            state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
            state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
            state = state ^ (state >>> 31);
            int j = (int) ((state & 0x7FFFFFFFFFFFFFFFL) % (i + 1));
            EquipmentSlot tmp = slots[i];
            slots[i] = slots[j];
            slots[j] = tmp;
        }
    }

    /**
     * Roll the prefab and write the first non-empty stack into the item
     * frame's {@code Item} NBT field. The other rolled stacks (if any) are
     * discarded — an item frame has a single visible slot.
     */
    private static void applyToItemFrame(CompoundTag entityNbt, ContainerContentsPool pool,
                                          BlockPos localPos, long seed, int carriageIndex,
                                          HolderLookup.Provider registries) {
        List<ItemStack> rolled = ContainerContentsRoller.rollStacks(
            pool, ITEM_FRAME_SLOTS, localPos, seed, carriageIndex, registries);
        if (rolled.isEmpty()) return;

        ItemStack pick = ItemStack.EMPTY;
        for (ItemStack s : rolled) {
            if (!s.isEmpty()) { pick = s; break; }
        }
        if (pick.isEmpty()) return;

        CompoundTag stackTag = (CompoundTag) pick.save(registries, new CompoundTag());
        entityNbt.put(NBT_ITEM, stackTag);

        if (DebugFlags.logLootRolls()) {
            LOGGER.info("[DT-item-frame-variant] applied {} at localPos={} seed={} carriageIdx={}",
                pick.getItem(), localPos, seed, carriageIndex);
        }
    }

    /**
     * Apply a loot pool directly to a live {@link Entity} (armor stand /
     * item frame / glow item frame). Parallel to {@link #applyTo} but
     * operates on an already-spawned entity rather than pre-spawn NBT —
     * used by the prefab-item placement flow (right-click an armor stand
     * prefab from the new "Armor Stands / Item Frames" creative tab).
     *
     * <p>Slot mapping mirrors {@link #applyToArmorStand} — armor items
     * resolve to FEET / LEGS / CHEST / HEAD via {@link Equipable#get};
     * shields land in OFFHAND; everything else defaults to MAINHAND.
     * Slots the pool covers are cleared and rerolled; existing equipment in
     * slots the pool can't fill is preserved.</p>
     *
     * @return {@code true} when the entity was an applicable type and the
     *         pool was applied (for a stand, its covered slots were rerolled —
     *         possibly to empty); {@code false} when the entity type isn't
     *         supported or the pool had nothing it could place.
     */
    public static boolean applyPoolToLiveEntity(Entity entity, ContainerContentsPool pool,
                                                 long seed, int carriageIndex,
                                                 HolderLookup.Provider registries) {
        if (entity == null || pool == null || pool.isEmpty()) return false;
        BlockPos pos = entity.blockPosition();

        if (entity instanceof ArmorStand stand) {
            // Slot-aware reroll — same logic as the NBT path. Clear every
            // slot the pool covers, then roll at-most-one stack per slot.
            java.util.EnumSet<EquipmentSlot> covered = coveredSlots(pool);
            if (covered.isEmpty()) return false;
            for (EquipmentSlot slot : covered) {
                stand.setItemSlot(slot, ItemStack.EMPTY);
            }
            java.util.Map<EquipmentSlot, ItemStack> rolled = rollForArmorStandSlots(
                pool, covered, pos, seed, carriageIndex, registries);
            for (java.util.Map.Entry<EquipmentSlot, ItemStack> e : rolled.entrySet()) {
                stand.setItemSlot(e.getKey(), e.getValue());
            }
            return true;
        }
        if (entity instanceof ItemFrame frame) {
            List<ItemStack> rolled = ContainerContentsRoller.rollStacks(
                pool, ITEM_FRAME_SLOTS, pos, seed, carriageIndex, registries);
            for (ItemStack stack : rolled) {
                if (!stack.isEmpty()) {
                    frame.setItem(stack, /*updateNeighbours*/ true);
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Resolve the natural equipment slot for {@code stack}. Mirrors the
     * vanilla {@code Mob#getEquipmentSlotForItem} logic that was moved off
     * the static surface in 1.21 — equippable items (armor, elytra, etc.)
     * use their {@link Equipable} slot; shields go to OFFHAND; everything
     * else defaults to MAINHAND so non-equip items still land on a stand
     * visibly (as a held tool/weapon).
     */
    private static EquipmentSlot equipmentSlotFor(ItemStack stack) {
        Equipable equipable = Equipable.get(stack);
        if (equipable != null) return equipable.getEquipmentSlot();
        if (stack.is(Items.SHIELD)) return EquipmentSlot.OFFHAND;
        return EquipmentSlot.MAINHAND;
    }

    /**
     * Return a {@link ListTag} of exactly {@code size} CompoundTags, seeded
     * from any existing entries at {@code key} in {@code entityNbt}. Missing
     * or short lists are padded with empty {@link CompoundTag}s so the caller
     * can safely {@code set(idx, …)} any slot in {@code [0, size)}.
     */
    private static ListTag readOrInitList(CompoundTag entityNbt, String key, int size) {
        ListTag existing = entityNbt.contains(key, Tag.TAG_LIST)
            ? entityNbt.getList(key, Tag.TAG_COMPOUND)
            : new ListTag();
        ListTag out = new ListTag();
        for (int i = 0; i < size; i++) {
            if (i < existing.size()) out.add(existing.getCompound(i));
            else out.add(new CompoundTag());
        }
        return out;
    }
}
