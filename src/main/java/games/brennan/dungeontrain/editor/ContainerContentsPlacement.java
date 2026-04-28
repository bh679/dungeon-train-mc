package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Thin decorator over {@link SilentBlockOps#setBlockSilent} for the
 * variant-spawn placement path. When the placed block is a Container BE
 * AND a {@link ContainerContentsPool} has been authored at
 * {@code (plotKey, localPos)}, rolls the pool to synthesise the
 * {@code Items} list and merges it into the BE NBT before placement.
 *
 * <p>When no pool exists (or block has no inventory), this is exactly
 * equivalent to {@code SilentBlockOps.setBlockSilent(level, world, state, beNbt)}.
 *
 * <p>Determinism: the roll is keyed on
 * {@code (worldSeed, carriageIndex, localPos, slot)} via
 * {@link ContainerContentsRoller}, so the same chest at the same world
 * seed always rolls the same contents.</p>
 */
public final class ContainerContentsPlacement {

    private ContainerContentsPlacement() {}

    /**
     * Place {@code state} at {@code worldPos}. If a container-contents pool
     * is configured at {@code (plotKey, localPos)} and {@code state} is a
     * Container BE, items are rolled and merged into {@code baseBeNbt}; the
     * resulting NBT is passed to {@link SilentBlockOps#setBlockSilent}.
     */
    public static void place(ServerLevel level, BlockPos worldPos, BlockState state,
                             @Nullable CompoundTag baseBeNbt, String plotKey,
                             BlockPos localPos, long worldSeed, int carriageIndex) {
        CompoundTag finalNbt = baseBeNbt;
        if (state.hasBlockEntity() && ContainerContentsRoller.isContainerState(state)) {
            ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey);
            ContainerContentsPool pool = store.poolAt(localPos);
            if (!pool.isEmpty()) {
                finalNbt = ContainerContentsRoller.roll(pool, state, localPos, worldSeed, carriageIndex, baseBeNbt);
            }
        }
        SilentBlockOps.setBlockSilent(level, worldPos, state, finalNbt);
    }
}
