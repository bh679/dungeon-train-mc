package games.brennan.dungeontrain.editor;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.OptionalInt;

/**
 * A hash of exactly what {@link ContainerContentsRoller} put in a container, kept in the block entity's
 * persistent data so a later pass can tell a container nobody has touched from one that has been looted,
 * topped up, fed by a hopper or opened (opening resolves pending books and spends the Luck bonus, both of
 * which change the contents).
 *
 * <p>Read by the difficulty swap ({@code world/DifficultyLootClear}): a container whose items still hash
 * to its fingerprint is left as generated, anything else is emptied.</p>
 *
 * <p><b>Hashed as saved NBT, not as live stacks.</b> A live {@code ItemStack}'s components hold registry
 * {@code Holder}s whose hash is identity-based and changes every boot; a {@link Tag}'s hash is content-based.
 * The rolled stacks are put through the same parse + {@code ItemStack.save} round trip a container makes
 * when it loads and saves, so both sides are hashed in the form the container really stores. Order of
 * slots doesn't matter (each element carries its slot), so the per-slot hashes are summed. Should the two
 * ever disagree, the container reads as touched and is emptied, which is the safe way to be wrong.</p>
 */
public final class RolledItemsFingerprint {

    /** Persistent-data key (inside {@code NeoForgeData}) holding the fingerprint. */
    public static final String NBT_KEY = "dt_rolled_items";

    private static final String NBT_NEOFORGE_DATA = "NeoForgeData";
    private static final String NBT_ITEMS = "Items";
    private static final String NBT_SINGLE_ITEM = "item";
    private static final String NBT_SLOT = "Slot";

    private RolledItemsFingerprint() {}

    /**
     * A copy of rolled block-entity NBT with its fingerprint added, or {@code beNbt} itself when it holds
     * neither an {@code Items} list nor a single {@code item} (nothing a container would load).
     */
    public static CompoundTag stamp(CompoundTag beNbt, HolderLookup.Provider registries) {
        if (beNbt == null) return null;
        OptionalInt hash = hashOfSaved(beNbt, registries);
        if (hash.isEmpty()) return beNbt;
        CompoundTag out = beNbt.copy();
        CompoundTag data = out.contains(NBT_NEOFORGE_DATA, Tag.TAG_COMPOUND)
            ? out.getCompound(NBT_NEOFORGE_DATA).copy()
            : new CompoundTag();
        data.putInt(NBT_KEY, hash.getAsInt());
        out.put(NBT_NEOFORGE_DATA, data);
        return out;
    }

    /**
     * Fingerprint what {@code container} holds right now, when it holds something and has no fingerprint
     * yet: contents baked into a template (never rolled) count as generated once their carriage or room is
     * placed. True when a fingerprint was written.
     */
    public static boolean stampIfAbsent(BlockEntity be, Container container, HolderLookup.Provider registries) {
        CompoundTag data = be.getPersistentData();
        if (data.contains(NBT_KEY, Tag.TAG_INT) || container.isEmpty()) return false;
        data.putInt(NBT_KEY, hashOf(container, registries));
        be.setChanged();
        return true;
    }

    /** True when {@code be} was rolled and {@code container} (its own inventory) still holds exactly that roll. */
    public static boolean isPristine(BlockEntity be, Container container, HolderLookup.Provider registries) {
        CompoundTag data = be.getPersistentData();
        if (!data.contains(NBT_KEY, Tag.TAG_INT)) return false;
        return data.getInt(NBT_KEY) == hashOf(container, registries);
    }

    /** {@link #isPristine} on saved block-entity NBT, as a snapshot holds it. */
    public static boolean isPristine(CompoundTag beNbt, HolderLookup.Provider registries) {
        CompoundTag data = beNbt.getCompound(NBT_NEOFORGE_DATA);
        if (!data.contains(NBT_KEY, Tag.TAG_INT)) return false;
        OptionalInt hash = hashOfSaved(beNbt, registries);
        return hash.isPresent() && hash.getAsInt() == data.getInt(NBT_KEY);
    }

    /** The fingerprint of saved block-entity NBT's {@code Items} or {@code item}; empty when it holds neither. */
    static OptionalInt hashOfSaved(CompoundTag beNbt, HolderLookup.Provider registries) {
        if (beNbt.contains(NBT_ITEMS, Tag.TAG_LIST)) {
            return OptionalInt.of(hashOf(beNbt.getList(NBT_ITEMS, Tag.TAG_COMPOUND), registries));
        }
        if (beNbt.contains(NBT_SINGLE_ITEM, Tag.TAG_COMPOUND)) {
            return OptionalInt.of(slotHash(beNbt.getCompound(NBT_SINGLE_ITEM), 0, registries));
        }
        return OptionalInt.empty();
    }

    /** The fingerprint of what {@code container} holds right now. */
    public static int hashOf(Container container, HolderLookup.Provider registries) {
        int sum = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            sum += mix(saved(stack, slot, registries).hashCode());
        }
        return sum;
    }

    /** The fingerprint of {@code stacks}, each counted at its index as its slot. */
    public static int hashOf(List<ItemStack> stacks, HolderLookup.Provider registries) {
        int sum = 0;
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) continue;
            sum += mix(saved(stack, slot, registries).hashCode());
        }
        return sum;
    }

    /** The fingerprint of a container {@code Items} list, each element carrying its {@code Slot}. */
    static int hashOf(ListTag items, HolderLookup.Provider registries) {
        int sum = 0;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag element = items.getCompound(i);
            sum += slotHash(element, element.getByte(NBT_SLOT) & 0xFF, registries);
        }
        return sum;
    }

    /** One slot's contribution, after the parse + save round trip; an unparseable stack contributes nothing. */
    private static int slotHash(CompoundTag stackTag, int slot, HolderLookup.Provider registries) {
        ItemStack stack = ItemStack.parseOptional(registries, stackTag);
        if (stack.isEmpty()) return 0;
        return mix(saved(stack, slot, registries).hashCode());
    }

    private static CompoundTag saved(ItemStack stack, int slot, HolderLookup.Provider registries) {
        CompoundTag tag = (CompoundTag) stack.save(registries, new CompoundTag());
        tag.putByte(NBT_SLOT, (byte) slot);
        return tag;
    }

    /** Spread each slot's hash before summing, so two slots can't cancel by simple arithmetic. */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }
}
