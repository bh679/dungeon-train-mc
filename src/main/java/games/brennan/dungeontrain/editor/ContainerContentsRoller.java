package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Pure utility — turn a {@link ContainerContentsPool} into the {@code Items}
 * NBT list that {@link net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity}
 * and {@link net.minecraft.world.level.block.entity.DispenserBlockEntity}
 * read on load.
 *
 * <p>Determinism: the roll is keyed on
 * {@code (worldSeed, carriageIndex, localPos, slot)} so the same chest in
 * the same carriage at the same world seed always rolls the same contents.
 * Mixer mirrors the variant-resolve mixer in
 * {@link CarriageVariantBlocks#pickIndexWeighted}.</p>
 */
public final class ContainerContentsRoller {

    private static final long MIX_X = 0x9E3779B97F4A7C15L;
    private static final long MIX_Y = 0xBF58476D1CE4E5B9L;
    private static final long MIX_Z = 0x94D049BB133111EBL;
    private static final long MIX_S = 0xC6BC279692B5C323L;
    private static final long MIX_C = 0xCAFEBABE12345678L;

    /**
     * BE NBT key on {@code decorated_pot}. Holds the rolled {@link ItemStack} that
     * the vase-break handler ({@code VasePotLootDrop}) materialises into a world
     * drop. Decorated pots in 1.20.1 have no native inventory slot, so the
     * roller writes here instead of the vanilla {@code Items} list.
     *
     * <p>If a future Minecraft version gives {@code DecoratedPotBlockEntity} a
     * real {@link Container} interface, {@link #containerSlotsFor} starts
     * returning the native size and the roller stops writing this key — the
     * vanilla loot mechanism takes over.
     */
    public static final String NBT_POT_LOOT = "dt_pot_loot";

    private ContainerContentsRoller() {}

    /**
     * Build the BE NBT for a container at {@code localPos} given a pool. If
     * the pool is empty or the block has no Container BE, returns
     * {@code baseNbt} unchanged.
     *
     * <p>Container size is read from the freshly-instantiated BlockEntity
     * (via {@code beType.create}) so dispensers get 9 slots, hoppers 5,
     * chests 27, etc. — without us hardcoding a table.</p>
     */
    @Nullable
    public static CompoundTag roll(ContainerContentsPool pool, BlockState state,
                                   BlockPos localPos, long worldSeed, int carriageIndex,
                                   @Nullable CompoundTag baseNbt,
                                   HolderLookup.Provider registries) {
        if (pool == null || pool.isEmpty()) return baseNbt;
        if (!state.hasBlockEntity()) return baseNbt;
        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return baseNbt;

        // Native Container path takes priority. If a future Minecraft version
        // makes DecoratedPotBlockEntity implement Container, this branch picks
        // it up automatically and the dt_pot_loot fallback becomes dormant.
        int slots = nativeContainerSlots(state);
        if (slots <= 0) {
            if (isDecoratedPot(state)) {
                return rollDecoratedPot(pool, totalWeight, localPos, worldSeed, carriageIndex, baseNbt, registries);
            }
            return baseNbt;
        }

        // Roll K = uniform random in [fillMin, effectiveMax] (inclusive).
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? slots : Math.min(pool.fillMax(), slots);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);
        int[] slotsToFill = resolveSlotSubset(slots, k, localPos, worldSeed, carriageIndex);

        ListTag items = new ListTag();
        for (int slot : slotsToFill) {
            ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, slot);
            if (picked == null || picked.isAir()) continue;
            // Per-entry count is treated as a max — roll the actual stack
            // count uniformly in [1, picked.count()] for variety per slot.
            int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, slot);
            CompoundTag stack = new CompoundTag();
            stack.putByte("Slot", (byte) slot);
            stack.putString("id", picked.itemId().toString());
            stack.putByte("Count", (byte) Math.max(1, Math.min(64, rolledCount)));
            items.add(stack);
        }

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        // Drop any pre-existing Items list — the rolled pool is authoritative.
        out.put("Items", items);
        return out;
    }

    /**
     * Deterministic K = uniform integer in {@code [min, max]}. Same mixer
     * basis as the slot-pick / count rolls but with a distinct salt so all
     * three rolls are independent.
     */
    private static int rollKCount(int min, int max, BlockPos localPos, long worldSeed, int carriageIndex) {
        if (max <= min) return min;
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ 0x5BD1E995L; // K-roll salt
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        int range = max - min + 1;
        return min + (int) ((state & 0x7FFFFFFFFFFFFFFFL) % range);
    }

    /**
     * Deterministic stack count for a slot — uniform in {@code [1, max]}.
     * Independent salt so it doesn't correlate with the entry pick.
     */
    private static int rollItemCount(int max, BlockPos localPos, long worldSeed, int carriageIndex, int slot) {
        if (max <= 1) return 1;
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S)
            ^ 0xA5A5A5A5DEADBEEFL; // count-roll salt
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return 1 + (int) ((state & 0x7FFFFFFFFFFFFFFFL) % max);
    }

    /**
     * Pick {@code count} slot indices from {@code [0..slots)} via seeded
     * Fisher–Yates. Returns the prefix of length {@code count}.
     */
    private static int[] resolveSlotSubset(int slots, int count, BlockPos localPos,
                                           long worldSeed, int carriageIndex) {
        if (count >= slots) {
            int[] all = new int[slots];
            for (int i = 0; i < slots; i++) all[i] = i;
            return all;
        }
        if (count <= 0) return new int[0];
        long state = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C);
        int[] order = new int[slots];
        for (int i = 0; i < slots; i++) order[i] = i;
        for (int i = slots - 1; i > 0; i--) {
            state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
            state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
            state = state ^ (state >>> 31);
            int j = (int) ((state & 0x7FFFFFFFFFFFFFFFL) % (i + 1));
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        int[] picked = new int[count];
        System.arraycopy(order, 0, picked, 0, count);
        return picked;
    }

    /**
     * Slot count for a container BE at {@code state}. Public so the menu
     * controller can show a hint of "max" when the player nudges fill count
     * past the container's capacity.
     */
    public static int slotsForContainer(BlockState state) {
        return containerSlotsFor(state);
    }

    /**
     * Slot count for a container BE. Looks up the BE type's default instance,
     * casts to {@link Container}. Returns 0 for non-container BEs.
     *
     * <p>Decorated pots in 1.20.1 have no native {@link Container} BE — they
     * fall through to a virtual single-slot count (delivered via the
     * {@code VasePotLootDrop} break handler). If a future Minecraft version
     * gives the pot a real {@code Container} (1.20.5+ does this with
     * {@code ContainerSingleItem}), the native check fires first and the
     * fallback is skipped.
     */
    private static int containerSlotsFor(BlockState state) {
        int native_ = nativeContainerSlots(state);
        if (native_ > 0) return native_;
        if (isDecoratedPot(state)) return 1;
        return 0;
    }

    /** Native vanilla {@link Container} slot count, or 0 if the BE doesn't implement it. */
    private static int nativeContainerSlots(BlockState state) {
        BlockEntityType<?> beType = beTypeFor(state);
        if (beType == null) return 0;
        try {
            BlockEntity probe = beType.create(BlockPos.ZERO, state);
            if (probe instanceof Container c) return c.getContainerSize();
        } catch (Throwable ignored) {
            // Some BEs throw on null Level access during construction — bail.
        }
        return 0;
    }

    public static boolean isDecoratedPot(BlockState state) {
        return state.is(Blocks.DECORATED_POT);
    }

    /**
     * Decorated-pot roll path — picks a single weighted entry and writes it as
     * an {@link ItemStack} CompoundTag at {@link #NBT_POT_LOOT}. The {@code
     * fillMin}/{@code fillMax} pool semantics are preserved for vases too:
     * with {@code effectiveMax = min(fillMax, 1)} the K-roll either picks "1"
     * (an item drops) or "0" (no item this carriage), giving the same rarity
     * dial the chest path uses.
     */
    private static CompoundTag rollDecoratedPot(ContainerContentsPool pool, int totalWeight,
                                                BlockPos localPos, long worldSeed, int carriageIndex,
                                                @Nullable CompoundTag baseNbt,
                                                HolderLookup.Provider registries) {
        int effectiveMax = pool.fillMax() == ContainerContentsPool.FILL_ALL
            ? 1 : Math.min(pool.fillMax(), 1);
        int effectiveMin = Math.max(0, Math.min(pool.fillMin(), effectiveMax));
        int k = rollKCount(effectiveMin, effectiveMax, localPos, worldSeed, carriageIndex);

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        out.remove(NBT_POT_LOOT);
        if (k <= 0) return out;

        ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, /*slot*/ 0);
        if (picked == null || picked.isAir()) return out;
        Item item = resolveItem(picked.itemId());
        if (item == null) return out;

        int rolledCount = rollItemCount(picked.count(), localPos, worldSeed, carriageIndex, /*slot*/ 0);
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(stack0(item).getMaxStackSize(), rolledCount)));
        CompoundTag stackTag = (CompoundTag) stack.save(registries, new CompoundTag());
        out.put(NBT_POT_LOOT, stackTag);
        return out;
    }

    private static ItemStack stack0(Item item) { return new ItemStack(item); }

    @Nullable
    private static BlockEntityType<?> beTypeFor(BlockState state) {
        try {
            return state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb
                ? probeType(eb, state)
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockEntityType<?> probeType(net.minecraft.world.level.block.EntityBlock eb, BlockState state) {
        BlockEntity be = eb.newBlockEntity(BlockPos.ZERO, state);
        return be == null ? null : be.getType();
    }

    @Nullable
    private static ContainerContentsEntry pickEntry(ContainerContentsPool pool, int totalWeight,
                                                    BlockPos localPos, long worldSeed, int carriageIndex,
                                                    int slot) {
        long mixed = worldSeed
            ^ ((long) localPos.getX() * MIX_X)
            ^ ((long) localPos.getY() * MIX_Y)
            ^ ((long) localPos.getZ() * MIX_Z)
            ^ ((long) carriageIndex * MIX_C)
            ^ ((long) slot * MIX_S);
        // Splittable-mix
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed = mixed ^ (mixed >>> 31);
        long unsigned = mixed & 0x7FFFFFFFFFFFFFFFL;
        int target = (int) (unsigned % totalWeight);
        for (ContainerContentsEntry e : pool.entries()) {
            target -= e.weight();
            if (target < 0) return e;
        }
        return pool.entries().get(pool.entries().size() - 1);
    }

    /**
     * True if a fresh {@link BlockEntity} created from {@code state} is a
     * {@link Container}. Used by the menu controller to reject "look at a
     * non-container" toggle attempts up front.
     */
    public static boolean isContainerState(BlockState state) {
        return containerSlotsFor(state) > 0;
    }

    /** Lookup helper for a placed-world Item id, returns null if not registered. */
    @Nullable
    public static Item resolveItem(net.minecraft.resources.ResourceLocation id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
    }
}
