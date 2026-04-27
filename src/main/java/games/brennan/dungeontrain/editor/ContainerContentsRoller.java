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

        ListTag items = new ListTag();
        for (int slot = 0; slot < slots; slot++) {
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
