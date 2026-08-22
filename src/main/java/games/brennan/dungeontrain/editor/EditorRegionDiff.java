package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.commands.CommandSourceStack;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Records a whole-plot editor operation — Clear, Reset, mirror rebuild, a bulk
 * block swap — as a single undoable step, by scanning the plot before and after
 * and keeping only the cells that actually moved.
 *
 * <p>The per-tick {@link EditorEditRecorder} cannot see these: they write
 * through {@link games.brennan.dungeontrain.worldgen.SilentBlockOps} or raw
 * {@code setBlock} loops, neither of which fires a block event. Rather than
 * teaching every such op to report its own writes, this wraps the op and
 * diffs around it — which also picks up whatever the op did indirectly.</p>
 *
 * <p>Diffing rather than storing the whole region keeps a step proportional to
 * what changed: re-stamping a plot that was already saved records nothing at
 * all.</p>
 */
public final class EditorRegionDiff {

    private EditorRegionDiff() {}

    /** A cell's state and block-entity contents at snapshot time. */
    private record Snapshot(BlockState state, @Nullable CompoundTag nbt) {}

    /**
     * Run {@code op}, recording everything it changed inside the player's plot
     * as one undo step labelled {@code label}.
     *
     * <p>The op runs regardless — a plot too big to record, or a player standing
     * outside every plot, must not turn into a silently skipped Clear. In the
     * too-big case the plot's history is dropped, so a later undo cannot apply
     * a step from before an unrecorded change.</p>
     */
    public static void record(ServerPlayer player, String label, Runnable op) {
        record(player, label, null, op);
    }

    /**
     * Value-returning form, for command handlers that must hand Brigadier a
     * result code. The op's return value is passed straight through — recording
     * never changes what the wrapped operation reports.
     */
    public static <T> T recording(CommandSourceStack source, String label, Supplier<T> op) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return op.get();
        var result = new Object() { T value; };
        record(player, label, null, () -> result.value = op.get());
        return result.value;
    }

    /**
     * As {@link #record(ServerPlayer, String, Runnable)}, but also snapshots the
     * variant sidecar of {@code sidecarPlotKey} — for ops like the bulk block
     * swap that rewrite geometry and variant pools together, and which the
     * author thinks of as one action.
     */
    public static void record(ServerPlayer player, String label,
                              @Nullable String sidecarPlotKey, Runnable op) {
        ServerLevel level = player.serverLevel();
        Optional<EditorPlotScope> maybeScope = EditorPlotScope.resolveAt(player, level);
        if (maybeScope.isEmpty()) {
            op.run();
            return;
        }
        EditorPlotScope scope = maybeScope.get();

        if (scope.volume() > EditorEditHistory.MAX_CELLS_PER_STEP) {
            EditorEditHistory.clearPlot(player.getUUID(), scope.key());
            op.run();
            return;
        }

        String sidecarBefore = sidecarPlotKey == null ? null : snapshotSidecar(level, sidecarPlotKey);
        Map<BlockPos, Snapshot> before = scan(level, scope);

        op.run();

        List<EditorEditHistory.Cell> cells = diff(level, before);
        List<EditorEditHistory.SidecarSnapshot> sidecars = List.of();
        if (sidecarPlotKey != null) {
            String sidecarAfter = snapshotSidecar(level, sidecarPlotKey);
            if (sidecarBefore != null && !sidecarBefore.equals(sidecarAfter)) {
                sidecars = List.of(new EditorEditHistory.SidecarSnapshot(
                    sidecarPlotKey, sidecarBefore, sidecarAfter));
            }
        }
        EditorEditHistory.push(player.getUUID(),
            new EditorEditHistory.Step(scope.key(), label, cells, sidecars));
    }

    /** Every cell in the plot box, block-entity contents included. */
    private static Map<BlockPos, Snapshot> scan(ServerLevel level, EditorPlotScope scope) {
        Vec3i size = scope.size();
        Map<BlockPos, Snapshot> out = new HashMap<>(scope.volume());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    cursor.set(scope.origin().getX() + dx,
                               scope.origin().getY() + dy,
                               scope.origin().getZ() + dz);
                    BlockPos pos = cursor.immutable();
                    BlockState state = level.getBlockState(pos);
                    out.put(pos, new Snapshot(state,
                        state.hasBlockEntity() ? EditorEditRecorder.readNbt(level, pos) : null));
                }
            }
        }
        return out;
    }

    /** Cells whose state differs from the snapshot, as before/after history cells. */
    private static List<EditorEditHistory.Cell> diff(ServerLevel level, Map<BlockPos, Snapshot> before) {
        List<EditorEditHistory.Cell> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Snapshot> entry : before.entrySet()) {
            BlockPos pos = entry.getKey();
            Snapshot snapshot = entry.getValue();
            BlockState after = level.getBlockState(pos);
            if (after == snapshot.state()) continue;
            out.add(new EditorEditHistory.Cell(pos, snapshot.state(), snapshot.nbt(),
                after, after.hasBlockEntity() ? EditorEditRecorder.readNbt(level, pos) : null));
        }
        return out;
    }

    /** Serialised sidecar for {@code plotKey}, or null when the plot no longer resolves. */
    @Nullable
    private static String snapshotSidecar(ServerLevel level, String plotKey) {
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveByKey(plotKey, dims);
        return plot == null ? null : plot.snapshotJson();
    }
}
