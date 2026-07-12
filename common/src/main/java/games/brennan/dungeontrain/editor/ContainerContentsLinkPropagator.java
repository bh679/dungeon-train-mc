package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Cross-plot fan-out for loot prefab updates. When a template's pool
 * changes (saved or edited via a linked container's menu), every container
 * in every editor plot that links to that template needs its in-world
 * BlockEntity refreshed so the rolled items reflect the new pool.
 *
 * <p>Editor-only by construction: links only exist for containers placed or
 * saved inside an editor plot ({@link BlockVariantPlot#resolveAt}), so this
 * propagator never touches gameplay containers.</p>
 */
public final class ContainerContentsLinkPropagator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContainerContentsLinkPropagator() {}

    /**
     * Re-roll every linked container's BlockEntity to reflect the freshly-saved
     * pool for {@code prefabId}. Cheap to call on every template save — the
     * scan is bounded by the number of editor plot stores on disk (a few
     * dozen at most).
     *
     * <p>Each propagation event uses a unique {@code carriageIndex} salt so
     * the deterministic roller produces fresh items rather than re-deriving
     * the original placement-time roll.</p>
     */
    public static void propagate(ServerLevel level, String prefabId) {
        if (level == null || prefabId == null || prefabId.isEmpty()) return;
        Optional<LootPrefabStore.Data> loaded = LootPrefabStore.load(prefabId);
        if (loaded.isEmpty()) return;
        ContainerContentsPool pool = loaded.get().pool();
        if (pool.isEmpty()) {
            // Empty pool — nothing meaningful to roll. Leave the chest as-is.
            return;
        }

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        long worldSeed = level.getSeed();
        // One unique salt per propagation event — all linked containers in
        // this fan-out get a fresh, mutually-consistent roll. Different from
        // both the placement-time roll (carriageIndex=0) and any prior
        // propagation, so the items visibly change.
        int rollSalt = (int) (System.nanoTime() & 0x7FFFFFFFL);

        int touched = 0;
        for (String plotKey : ContainerContentsStore.allKnownPlotKeys()) {
            ContainerContentsStore store = ContainerContentsStore.loadFor(plotKey);
            List<BlockPos> positions = store.positionsLinkedTo(prefabId);
            if (positions.isEmpty()) continue;

            BlockVariantPlot plot = BlockVariantPlot.resolveByKey(plotKey, dims);
            if (plot == null) {
                LOGGER.debug("[DungeonTrain] Skipping link propagation for {} — plot can't resolve.",
                    plotKey);
                continue;
            }

            for (BlockPos localPos : positions) {
                BlockPos worldPos = plot.origin().offset(localPos);
                BlockEntity be = level.getBlockEntity(worldPos);
                if (be == null) {
                    // Container block was broken or never loaded. Skip — the
                    // link record persists, so the next placement at this
                    // position will pick the template up.
                    continue;
                }
                BlockState state = level.getBlockState(worldPos);
                CompoundTag baseNbt = be.saveWithFullMetadata(level.registryAccess());
                CompoundTag rolled = ContainerContentsRoller.roll(
                    pool, state, localPos, worldSeed, rollSalt, baseNbt, level.registryAccess(), level);
                if (rolled == null) continue;
                be.loadCustomOnly(rolled, level.registryAccess());
                be.setChanged();
                ChiseledBookshelfSync.syncIfNeeded(level, worldPos);
                touched++;
            }
        }
        if (touched > 0) {
            LOGGER.info("[DungeonTrain] Loot prefab '{}' propagated to {} linked container(s).",
                prefabId, touched);
        }
    }
}
