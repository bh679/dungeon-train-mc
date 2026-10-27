package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.compat.vista.TvBlocks;
import games.brennan.dungeontrain.editor.RolledItemsFingerprint;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a difficulty swap takes out of the world: the loot left lying around or stored under the old
 * difficulty, so it can't be picked back up under the new one (each difficulty is its own run, see
 * {@code player/DifficultyPartition}).
 *
 * <ul>
 *   <li><b>Item entities</b> are removed.</li>
 *   <li><b>Block containers</b> are emptied unless they still hold exactly what the train rolled into them
 *       ({@link RolledItemsFingerprint}). A pending vanilla loot table is left too: nobody has opened it,
 *       and asking it whether it is empty would roll it with no one there.</li>
 *   <li><b>Entity storage</b>: storage minecarts and chest boats, a chested donkey/mule/llama's chest, an
 *       item frame's item and an armor stand's gear are emptied, unless the entity still holds what it
 *       was first seen with (a carriage template's decorative frame keeps its item).</li>
 * </ul>
 *
 * <p>Vista TVs are never touched: their one slot is the tuner, not storage.</p>
 */
public final class DifficultyLootClear {

    /** Entity persistent-data key: the difficulty profile the entity's items belong to. */
    public static final String ENTITY_KEY = "dt_difficulty";

    /** First chest slot of a chested horse in {@code AbstractHorse#getSlot} numbering. */
    private static final int HORSE_CHEST_SLOT_BASE = 500;
    /** More than any chested horse holds (a strength-5 llama has 15). */
    private static final int HORSE_CHEST_SLOT_LIMIT = 32;

    /** Entity-NBT keys holding carried or stored items, cleared from snapshots. */
    private static final Set<String> ENTITY_ITEM_KEYS = Set.of("Items", "Item", "ArmorItems", "HandItems");

    private DifficultyLootClear() {}

    // ---- block entities --------------------------------------------------------------------------

    /** Empty {@code be} unless it is exempt or untouched. True when something was removed. */
    public static boolean clearBlockEntity(ServerLevel level, BlockEntity be) {
        if (TvBlocks.isTelevision(be.getBlockState())) return false;
        if (be instanceof Container container) {
            if (be instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) return false;
            if (container.isEmpty()) return false;
            if (RolledItemsFingerprint.isPristine(be, container, level.registryAccess())) return false;
            container.clearContent();
            be.setChanged();
            return true;
        }
        IItemHandler handler = level.getCapability(
            Capabilities.ItemHandler.BLOCK, be.getBlockPos(), be.getBlockState(), be, null);
        return handler != null && clearHandler(handler);
    }

    private static boolean clearHandler(IItemHandler handler) {
        boolean removed = false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, ItemStack.EMPTY);
            } else {
                handler.extractItem(slot, stack.getCount(), false);
            }
            removed = true;
        }
        return removed;
    }

    /**
     * Fingerprint {@code be}'s contents as placed when a carriage or room has just been generated, so
     * template-baked items count as untouched. Skips TVs and pending loot tables (asking those whether
     * they are empty would roll them).
     */
    public static void fingerprintPlaced(ServerLevel level, BlockEntity be) {
        if (TvBlocks.isTelevision(be.getBlockState())) return;
        if (!(be instanceof Container container)) return;
        if (be instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null) return;
        RolledItemsFingerprint.stampIfAbsent(be, container, level.registryAccess());
    }

    // ---- entities --------------------------------------------------------------------------------

    /** Whether a swap has anything to take from {@code entity}. */
    public static boolean handles(Entity entity) {
        return entity instanceof ItemEntity
            || entity instanceof ItemFrame
            || entity instanceof ArmorStand
            || entity instanceof AbstractChestedHorse
            || entity instanceof Container;
    }

    /**
     * Remove an item entity, or empty the others' storage unless it still holds what it was first seen with
     * ({@link #fingerprintSpawn}): a carriage template's decorative frame or armour stand keeps its item.
     * True when something was removed.
     */
    public static boolean clearEntity(Entity entity) {
        if (entity instanceof ItemEntity) {
            entity.discard();
            return true;
        }
        if (entity instanceof ContainerEntity vehicle && vehicle.getLootTable() != null) return false;
        if (isAsSpawned(entity)) return false;
        if (entity instanceof ItemFrame frame) {
            if (frame.getItem().isEmpty()) return false;
            frame.setItem(ItemStack.EMPTY, false);
            return true;
        }
        if (entity instanceof ArmorStand stand) {
            boolean removed = false;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (stand.getItemBySlot(slot).isEmpty()) continue;
                stand.setItemSlot(slot, ItemStack.EMPTY);
                removed = true;
            }
            return removed;
        }
        if (entity instanceof AbstractChestedHorse horse) {
            return clearHorseChest(horse);
        }
        if (entity instanceof Container container && !container.isEmpty()) {
            container.clearContent();
            return true;
        }
        return false;
    }

    /**
     * Record what an entity holds the first time it is seen, so a swap can tell it apart from one a player
     * has filled since: a template's item frame or an End city's elytra frame is first seen holding its
     * item, a placed frame is first seen empty. Like a chunk with no recorded difficulty, an entity first
     * seen already stocked (before this existed) is taken as found.
     */
    public static void fingerprintSpawn(Entity entity) {
        if (entity instanceof ItemEntity) return;
        entity.getPersistentData().putInt(RolledItemsFingerprint.NBT_KEY,
            RolledItemsFingerprint.hashOf(heldItems(entity), entity.registryAccess()));
    }

    private static boolean isAsSpawned(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        return data.contains(RolledItemsFingerprint.NBT_KEY, Tag.TAG_INT)
            && data.getInt(RolledItemsFingerprint.NBT_KEY)
                == RolledItemsFingerprint.hashOf(heldItems(entity), entity.registryAccess());
    }

    /** The items a swap would take from {@code entity}, in a stable slot order. */
    private static List<ItemStack> heldItems(Entity entity) {
        List<ItemStack> out = new ArrayList<>();
        if (entity instanceof ItemFrame frame) {
            out.add(frame.getItem());
        } else if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : EquipmentSlot.values()) out.add(stand.getItemBySlot(slot));
        } else if (entity instanceof AbstractChestedHorse horse) {
            for (int i = 0; i < HORSE_CHEST_SLOT_LIMIT; i++) {
                SlotAccess slot = horse.getSlot(HORSE_CHEST_SLOT_BASE + i);
                if (slot == SlotAccess.NULL) break;
                out.add(slot.get());
            }
        } else if (entity instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) out.add(container.getItem(i));
        }
        return out;
    }

    /** Only the chest: a saddle or horse armour is the horse's own gear, not stored loot. */
    private static boolean clearHorseChest(AbstractChestedHorse horse) {
        boolean removed = false;
        for (int i = 0; i < HORSE_CHEST_SLOT_LIMIT; i++) {
            SlotAccess slot = horse.getSlot(HORSE_CHEST_SLOT_BASE + i);
            if (slot == SlotAccess.NULL) break;
            if (slot.get().isEmpty()) continue;
            slot.set(ItemStack.EMPTY);
            removed = true;
        }
        return removed;
    }

    /** The profile an entity's items were tagged with, or {@code ""} for one that was never tagged. */
    public static String entityKey(Entity entity) {
        return entity.getPersistentData().getString(ENTITY_KEY);
    }

    public static void tagEntity(Entity entity, String key) {
        entity.getPersistentData().putString(ENTITY_KEY, key);
    }

    // ---- decisions (pure, unit-tested) -----------------------------------------------------------

    /**
     * Whether stuff recorded under {@code recordedKey} must be cleared now that {@code currentKey} is the
     * difficulty. Nothing recorded means nothing is known, which is never grounds to delete.
     */
    public static boolean isStale(String recordedKey, String currentKey) {
        return recordedKey != null && !recordedKey.isEmpty() && !recordedKey.equals(currentKey);
    }

    // ---- snapshots (portal room memory) ----------------------------------------------------------

    /**
     * Clear the old difficulty's loot out of a {@code CarriageBlockSnapshot} tag in place: a block
     * entity's items unless they still match their roll fingerprint, item entities, and the items held
     * by other entities. {@code isTelevision} receives a cell's block-state tag.
     */
    public static void scrubSnapshot(CompoundTag snapshot, HolderLookup.Provider registries,
                                     Predicate<CompoundTag> isTelevision) {
        ListTag cells = snapshot.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            if (!cell.contains("b", Tag.TAG_COMPOUND) || isTelevision.test(cell.getCompound("s"))) continue;
            scrubBlockEntityTag(cell.getCompound("b"), registries);
        }
        ListTag ents = snapshot.getList("ents", Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag ent = ents.getCompound(i);
            if (isItemEntityTag(ent)) continue;
            ENTITY_ITEM_KEYS.forEach(ent::remove);
            kept.add(ent);
        }
        if (snapshot.contains("ents", Tag.TAG_LIST)) snapshot.put("ents", kept);
    }

    /** As {@link #clearBlockEntity}, on saved block-entity NBT. */
    static void scrubBlockEntityTag(CompoundTag be, HolderLookup.Provider registries) {
        if (be.contains("LootTable", Tag.TAG_STRING)) return;
        if (RolledItemsFingerprint.isPristine(be, registries)) return;
        be.remove("Items");
        be.remove("item");
    }

    private static boolean isItemEntityTag(CompoundTag ent) {
        return "minecraft:item".equals(ent.getString("id"));
    }
}
