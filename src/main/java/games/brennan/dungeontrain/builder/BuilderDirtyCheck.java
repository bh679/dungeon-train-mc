package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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

    /**
     * Indices of the build volumes whose blocks differ from their post-stamp baseline.
     *
     * <p>Volumes, not carriages: {@link BuilderBounds#volumesFor} answers with a portal room's one
     * box in Train Dimensions and the carriage run everywhere else. The old {@code carriages <= 0}
     * early-out is gone with it — that was the right guard while every volume was a carriage, and
     * it is exactly what would make a room never report itself dirty, since Train Dimensions parks
     * no carriages by design.</p>
     */
    public static List<Integer> dirtyCarriages(ServerLevel level) {
        List<Integer> dirty = new ArrayList<>();
        // volumesFor, not buildVolumes: it reads parkedCarriages for a carriage world and answers
        // with the room's single box in Train Dimensions. The carriages <= 0 early-out that used to
        // guard this went with it — that was right while every volume was a carriage, and it is
        // exactly what would stop a room ever reporting itself dirty, since Train Dimensions parks
        // no carriages by design.
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        for (int i = 0; i < volumes.size(); i++) {
            BoundingBox box = volumes.get(i);
            Map<BlockPos, BlockState> baseline = EditorPlotSnapshots.get(snapshotKey(i));
            BlockPos origin = BuilderBounds.originOf(box);
            if (isDirty(baseline, BuilderBounds.sizeOf(box),
                    local -> level.getBlockState(origin.offset(local)))) {
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
     * @param size     the volume's extent, from the box rather than from {@link CarriageDims} — a
     *                 portal room's is the author's, and comparing it through carriage dims would
     *                 walk the wrong window
     * @param liveAt   live block state at a local position
     */
    public static boolean isDirty(Map<BlockPos, BlockState> baseline, Vec3i size,
                                  Function<BlockPos, BlockState> liveAt) {
        if (baseline == null) {
            return false;
        }
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
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
