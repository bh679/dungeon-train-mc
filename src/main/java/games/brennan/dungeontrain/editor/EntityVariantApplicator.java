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
 *       Slots not filled by a roll keep the template's baked-in items
 *       (overlay semantics, not replace).</li>
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
     * <p>Slot assignment is by item type via {@link #equipmentSlotFor} —
     * armor items resolve to FEET / LEGS / CHEST / HEAD via
     * {@link Equipable#get(ItemStack)}; shields land in OFFHAND; everything
     * else defaults to MAINHAND. Multiple rolled stacks mapping to the same
     * slot resolve last-write-wins (the roller produces a deterministic
     * order so the same seed → same outcome).</p>
     *
     * <p>Only slots that receive a rolled stack are overwritten; the
     * template's existing bake survives in untouched slots.</p>
     */
    private static void applyToArmorStand(CompoundTag entityNbt, ContainerContentsPool pool,
                                           BlockPos localPos, long seed, int carriageIndex,
                                           HolderLookup.Provider registries) {
        List<ItemStack> rolled = ContainerContentsRoller.rollStacks(
            pool, ARMOR_STAND_SLOTS, localPos, seed, carriageIndex, registries);
        if (rolled.isEmpty()) return;

        ListTag armorItems = readOrInitList(entityNbt, NBT_ARMOR_ITEMS, 4);
        ListTag handItems = readOrInitList(entityNbt, NBT_HAND_ITEMS, 2);
        boolean armorTouched = false;
        boolean handsTouched = false;

        for (ItemStack stack : rolled) {
            if (stack.isEmpty()) continue;
            EquipmentSlot slot = equipmentSlotFor(stack);
            CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
            switch (slot) {
                case FEET -> { armorItems.set(0, stackTag); armorTouched = true; }
                case LEGS -> { armorItems.set(1, stackTag); armorTouched = true; }
                case CHEST -> { armorItems.set(2, stackTag); armorTouched = true; }
                case HEAD -> { armorItems.set(3, stackTag); armorTouched = true; }
                case MAINHAND -> { handItems.set(0, stackTag); handsTouched = true; }
                case OFFHAND -> { handItems.set(1, stackTag); handsTouched = true; }
                default -> { /* BODY / SADDLE etc — armor stand has no such slot */ }
            }
        }

        if (armorTouched) entityNbt.put(NBT_ARMOR_ITEMS, armorItems);
        if (handsTouched) entityNbt.put(NBT_HAND_ITEMS, handItems);

        if (DebugFlags.logLootRolls()) {
            LOGGER.info("[DT-armor-stand-variant] applied {} rolled stacks at localPos={} seed={} carriageIdx={} armorTouched={} handsTouched={}",
                rolled.size(), localPos, seed, carriageIndex, armorTouched, handsTouched);
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
     * Multiple stacks mapping to the same slot resolve last-write-wins.
     * Existing equipment in slots not touched by the roll is preserved.</p>
     *
     * @return {@code true} when the entity was an applicable type and at
     *         least one rolled stack was written; {@code false} when the
     *         entity type isn't supported or the pool was empty.
     */
    public static boolean applyPoolToLiveEntity(Entity entity, ContainerContentsPool pool,
                                                 long seed, int carriageIndex,
                                                 HolderLookup.Provider registries) {
        if (entity == null || pool == null || pool.isEmpty()) return false;
        BlockPos pos = entity.blockPosition();

        if (entity instanceof ArmorStand stand) {
            List<ItemStack> rolled = ContainerContentsRoller.rollStacks(
                pool, ARMOR_STAND_SLOTS, pos, seed, carriageIndex, registries);
            if (rolled.isEmpty()) return false;
            boolean any = false;
            for (ItemStack stack : rolled) {
                if (stack.isEmpty()) continue;
                EquipmentSlot slot = equipmentSlotFor(stack);
                stand.setItemSlot(slot, stack);
                any = true;
            }
            return any;
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
