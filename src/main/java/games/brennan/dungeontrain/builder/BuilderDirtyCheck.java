package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
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
     * The baseline key for a track plot.
     *
     * <p>Keyed on kind <em>and</em> name because {@code default} exists under every one of the eight
     * kinds — a single {@code track} key would have a pillar's baseline answering for a tunnel, and
     * the comparison would report the whole thing dirty the moment you opened the second one.</p>
     */
    public static String snapshotKey(TrackKind kind, String name) {
        return EditorPlotSnapshots.key("builder",
                "track:" + (kind == null ? "" : kind.id()) + ":" + (name == null ? "" : name));
    }

    /**
     * Indices of the build volumes whose blocks differ from their post-stamp baseline.
     *
     * <p>Carriages when the mode parks a train, the single track plot when it doesn't, and a portal
     * room's own box in Train Dimensions — the indices are into {@link BuilderBounds#volumesFor}, so
     * the latter two report {@code [0]} for "the one thing here has been edited". Callers only ever
     * ask how many there are and whether the list is empty, which reads the same either way.</p>
     *
     * <p>No {@code carriages <= 0} early-out: that was the right guard while every volume was a
     * carriage, and it is exactly what would stop a track plot or a room ever reporting itself
     * dirty, since neither mode parks a carriage by design.</p>
     */
    public static List<Integer> dirtyCarriages(ServerLevel level) {
        List<Integer> dirty = new ArrayList<>();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        TrackKind trackKind = BuilderTrackBuild.kindOf(data);
        boolean track = trackKind != null && BuilderWorldSetup.parkedCarriages(data) <= 0;
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);

        for (int i = 0; i < volumes.size(); i++) {
            BoundingBox box = volumes.get(i);
            String key = track ? snapshotKey(trackKind, data.builderName()) : snapshotKey(i);
            Map<BlockPos, BlockState> baseline = EditorPlotSnapshots.get(key);
            BlockPos origin = BuilderBounds.originOf(box);
            // Size from the box: a room's is the author's and a track plot's is its footprint,
            // neither of which is CarriageDims.
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
     * @param dims     the carriage footprint to walk — the convenience form, for the volumes that
     *                 really are carriage-shaped
     * @param liveAt   live block state at a local position
     */
    public static boolean isDirty(Map<BlockPos, BlockState> baseline, CarriageDims dims,
                                  Function<BlockPos, BlockState> liveAt) {
        return isDirty(baseline, new Vec3i(dims.length(), dims.height(), dims.width()), liveAt);
    }

    /**
     * As above, over an arbitrary footprint.
     *
     * <p>The volume is a parameter rather than carriage dims because a track plot is not
     * carriage-shaped — a tunnel is 10×14×13 and a pillar section is one block wide. Walking the
     * carriage box over a pillar would compare mostly-empty air either side of it and call every
     * pillar clean.</p>
     *
     * @param size the plot's footprint in blocks
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
