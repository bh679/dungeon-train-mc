package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        CompoundTag finalNbt = rollForPlacement(level, state, baseBeNbt, plotKey,
            localPos, worldSeed, carriageIndex, variantLinkedLootPrefabId, level);
        SilentBlockOps.setBlockSilent(level, worldPos, state, finalNbt);
    }

    /**
     * Worldgen-time twin of {@link #place}. Rolls loot with the same
     * precedence, then writes {@code state} through {@link WorldGenLevel}
     * with {@link Block#UPDATE_CLIENTS} (neighbour-update cascades are unsafe
     * inside the chunkgen decoration window) and stamps the resulting NBT
     * onto the freshly-placed block entity.
     *
     * <p>BE stamping applies to <em>every</em> block entity, not just
     * containers (signs, banners, …), so worldgen placement matches the
     * runtime path. The BE-load-after-setBlock pattern mirrors vanilla
     * {@code StructureTemplate.placeInWorld} and
     * {@link SilentBlockOps#setBlockSilent(ServerLevel, BlockPos, BlockState, CompoundTag)}.</p>
     */
    public static void placeWorldgen(WorldGenLevel level, BlockPos worldPos, BlockState state,
                                     @Nullable CompoundTag baseBeNbt, String plotKey,
                                     BlockPos localPos, long worldSeed, int carriageIndex,
                                     @Nullable String variantLinkedLootPrefabId) {
        // WorldGenLevel is not a Level, so pass null for the optional roll
        // context (used only by the furnace-slot path for recipe lookups).
        CompoundTag finalNbt = rollForPlacement(level, state, baseBeNbt, plotKey,
            localPos, worldSeed, carriageIndex, variantLinkedLootPrefabId, null);
        level.setBlock(worldPos, state, Block.UPDATE_CLIENTS);
        if (finalNbt == null || !state.hasBlockEntity()) return;
        BlockEntity be = level.getBlockEntity(worldPos);
        if (be == null) return;
        // Stamp the BE's coordinate fields so load() doesn't overwrite the
        // freshly-placed position (vanilla BlockEntity.load reads x/y/z).
        CompoundTag positioned = finalNbt.copy();
        positioned.putInt("x", worldPos.getX());
        positioned.putInt("y", worldPos.getY());
        positioned.putInt("z", worldPos.getZ());
        be.loadWithComponents(positioned, level.registryAccess());
        be.setChanged();
    }

    /**
     * Resolve the container-contents pool for {@code (plotKey, localPos)} (or
     * the per-variant loot link) and roll it into {@code baseBeNbt}, returning
     * the merged NBT. Returns {@code baseBeNbt} unchanged when {@code state}
     * is not a container BE or no pool resolves. Performs no world writes.
     *
     * @param registryLevel level supplying {@code registryAccess()} for item
     *                       (de)serialisation — {@link ServerLevel} at runtime,
     *                       {@link WorldGenLevel} at chunkgen.
     * @param rollLevel      optional {@link Level} passed through to
     *                       {@link ContainerContentsRoller#roll} for the
     *                       furnace-slot path; {@code null} at worldgen.
     */
    @Nullable
    public static CompoundTag rollForPlacement(LevelReader registryLevel, BlockState state,
                                               @Nullable CompoundTag baseBeNbt, String plotKey,
                                               BlockPos localPos, long worldSeed, int carriageIndex,
                                               @Nullable String variantLinkedLootPrefabId,
                                               @Nullable Level rollLevel) {
        if (!(state.hasBlockEntity() && ContainerContentsRoller.isContainerState(state))) {
            return baseBeNbt;
        }
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
        if (pool.isEmpty()) return baseBeNbt;
        return ContainerContentsRoller.roll(pool, state, localPos, worldSeed, carriageIndex,
            baseBeNbt, registryLevel.registryAccess(), rollLevel);
    }
}
