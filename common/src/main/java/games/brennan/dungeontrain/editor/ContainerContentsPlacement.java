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
     * Backward-compat overload — no per-variant link, defers to the
     * cell-level link in {@link ContainerContentsStore}.
     */
    public static void place(ServerLevel level, BlockPos worldPos, BlockState state,
                             @Nullable CompoundTag baseBeNbt, String plotKey,
                             BlockPos localPos, long worldSeed, int carriageIndex) {
        place(level, worldPos, state, baseBeNbt, plotKey, localPos, worldSeed, carriageIndex, null);
    }

    /**
     * Place {@code state} at {@code worldPos}. If a container-contents pool
     * is configured for this position and {@code state} is a Container BE,
     * items are rolled and merged into {@code baseBeNbt}; the resulting NBT
     * is passed to {@link SilentBlockOps#setBlockSilent}.
     *
     * <p>Pool resolution precedence:
     * <ol>
     *   <li>If {@code variantLinkedLootPrefabId} is non-null, fetch the pool
     *       directly from {@link LootPrefabStore}. The per-variant link wins
     *       over the cell-level link so two block-variants at the same cell
     *       can roll from different templates.</li>
     *   <li>Otherwise fall back to
     *       {@link ContainerContentsStore#poolAt(BlockPos)} which reads
     *       through the cell-level link if one exists.</li>
     *   <li>If neither yields a pool, fall back to
     *       {@link BlockLootDefaults#resolveDefaultPool} — a block-type-wide
     *       default (e.g. bookshelves), applied only to cells with no curated
     *       loot of their own.</li>
     * </ol>
     */
    public static void place(ServerLevel level, BlockPos worldPos, BlockState state,
                             @Nullable CompoundTag baseBeNbt, String plotKey,
                             BlockPos localPos, long worldSeed, int carriageIndex,
                             @Nullable String variantLinkedLootPrefabId) {
        CompoundTag finalNbt = baseBeNbt;
        if (state.hasBlockEntity() && ContainerContentsRoller.isContainerState(state)) {
            ContainerContentsPool pool;
            if (variantLinkedLootPrefabId != null) {
                pool = LootPrefabStore.load(variantLinkedLootPrefabId)
                    .map(LootPrefabStore.Data::pool)
                    .orElse(ContainerContentsPool.empty());
            } else {
                pool = ContainerContentsStore.loadFor(plotKey).poolAt(localPos);
            }
            if (pool.isEmpty()) {
                pool = BlockLootDefaults.resolveDefaultPool(state, localPos, worldSeed, carriageIndex)
                    .orElse(ContainerContentsPool.empty());
            }
            if (!pool.isEmpty()) {
                finalNbt = ContainerContentsRoller.roll(pool, state, localPos, worldSeed, carriageIndex, baseBeNbt, level.registryAccess(), level);
            }
        }
        SilentBlockOps.setBlockSilent(level, worldPos, state, finalNbt);
    }
}
