package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
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
                                   @Nullable CompoundTag baseNbt) {
        if (pool == null || pool.isEmpty()) return baseNbt;
        if (!state.hasBlockEntity()) return baseNbt;
        int slots = containerSlotsFor(state);
        if (slots <= 0) return baseNbt;
        int totalWeight = pool.totalWeight();
        if (totalWeight <= 0) return baseNbt;

        // Resolve which slot indices get rolled. fillCount == FILL_ALL → every
        // slot. Otherwise pick a deterministic subset of size min(fillCount,
        // slots) using a seeded Fisher–Yates over the slot indices.
        int[] slotsToFill = resolveSlotSubset(slots, pool.fillCount(), localPos, worldSeed, carriageIndex);

        ListTag items = new ListTag();
        for (int slot : slotsToFill) {
            ContainerContentsEntry picked = pickEntry(pool, totalWeight, localPos, worldSeed, carriageIndex, slot);
            if (picked == null || picked.isAir()) continue;
            CompoundTag stack = new CompoundTag();
            stack.putByte("Slot", (byte) slot);
            stack.putString("id", picked.itemId().toString());
            stack.putByte("Count", (byte) Math.max(1, Math.min(64, picked.count())));
            items.add(stack);
        }

        CompoundTag out = baseNbt == null ? new CompoundTag() : baseNbt.copy();
        // Drop any pre-existing Items list — the rolled pool is authoritative.
        out.put("Items", items);
        return out;
    }

    /**
     * Pick which slot indices participate in the roll. For
     * {@link ContainerContentsPool#FILL_ALL} returns {@code [0..slots)}.
     * Otherwise returns a deterministically-shuffled prefix of length
     * {@code min(fillCount, slots)} so the same chest at the same seed
     * fills the same set of slots (and the spatial pattern stays varied
     * across nearby chests via the localPos mix).
     */
    private static int[] resolveSlotSubset(int slots, int fillCount, BlockPos localPos,
                                           long worldSeed, int carriageIndex) {
        if (fillCount == ContainerContentsPool.FILL_ALL || fillCount >= slots) {
            int[] all = new int[slots];
            for (int i = 0; i < slots; i++) all[i] = i;
            return all;
        }
        if (fillCount <= 0) return new int[0];
        // Fisher–Yates with a splittable-mixer-derived RNG.
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
        int[] picked = new int[fillCount];
        System.arraycopy(order, 0, picked, 0, fillCount);
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
     */
    private static int containerSlotsFor(BlockState state) {
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
