package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Has the builder changed a carriage since it was stamped?
 *
 * <p>The baseline is the post-stamp world, held in the editor's own
 * {@link EditorPlotSnapshots} store under a {@code builder:carriage:<i>} key. That store is keyed
 * by an opaque string, so builder plots slot in beside the editor's without either knowing about
 * the other — and comparing against the stamped world (rather than the saved NBT) is the same
 * choice the editor made, because a stamp composes base NBT + parts overlay + sidecar variants and
 * any of those can drift.</p>
 *
 * <p><b>No snapshot means clean.</b> Snapshots live in memory and are lost on a server restart, so
 * a reopened builder world has no baseline; reporting everything dirty there would be worse than
 * useless. The editor takes the same position.</p>
 */
public final class BuilderDirtyCheck {

    private BuilderDirtyCheck() {}

    public static String snapshotKey(int carriageIndex) {
        return EditorPlotSnapshots.key("builder", "carriage:" + carriageIndex);
    }

    /** Indices of the carriages whose blocks differ from their post-stamp baseline. */
    public static List<Integer> dirtyCarriages(ServerLevel level) {
        List<Integer> dirty = new ArrayList<>();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        int carriages = BuilderMode.fromId(data.builderMode())
                .map(BuilderMode::carriageCount)
                .orElse(0);
        if (carriages <= 0) {
            return dirty;
        }
        CarriageDims dims = data.dims();
        List<BoundingBox> volumes = BuilderBounds.buildVolumes(carriages, dims);
        for (int i = 0; i < volumes.size(); i++) {
            BoundingBox box = volumes.get(i);
            Map<BlockPos, BlockState> baseline = EditorPlotSnapshots.get(snapshotKey(i));
            BlockPos origin = new BlockPos(box.minX(), box.minY(), box.minZ());
            if (isDirty(baseline, dims, local -> level.getBlockState(origin.offset(local)))) {
                dirty.add(i);
            }
        }
        return dirty;
    }

    public static boolean hasUnsavedChanges(ServerLevel level) {
        return !dirtyCarriages(level).isEmpty();
    }

    /**
     * The comparison itself, with the world behind a lookup so it can be tested.
     *
     * @param baseline post-stamp snapshot in <b>local</b> coordinates, or null if never captured.
     *                 {@link EditorPlotSnapshots} omits air to stay sparse, so an absent entry
     *                 means "this position was air" — not "unknown".
     * @param liveAt   live block state at a local position
     */
    public static boolean isDirty(Map<BlockPos, BlockState> baseline, CarriageDims dims,
                                  Function<BlockPos, BlockState> liveAt) {
        if (baseline == null) {
            return false;
        }
        for (int dx = 0; dx < dims.length(); dx++) {
            for (int dy = 0; dy < dims.height(); dy++) {
                for (int dz = 0; dz < dims.width(); dz++) {
                    BlockPos local = new BlockPos(dx, dy, dz);
                    BlockState expected = baseline.get(local);
                    BlockState live = liveAt.apply(local);
                    if (expected == null) {
                        // Air in the baseline: anything solid here is something the builder added.
                        if (live != null && !live.isAir()) {
                            return true;
                        }
                    } else if (!expected.equals(live)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Convenience for tests and callers that only have a sparse map of live blocks. */
    public static Function<BlockPos, BlockState> liveFrom(Map<BlockPos, BlockState> live) {
        return pos -> live.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
}
