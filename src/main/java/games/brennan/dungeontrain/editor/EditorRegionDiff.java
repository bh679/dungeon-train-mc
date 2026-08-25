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
import java.util.Objects;
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
 * <p>That last property is why <b>another mod's</b> writes ride this path too:
 * {@link games.brennan.dungeontrain.compat.EffortlessBuildingHistory} records
 * Effortless Building's builds by opening a capture around its packet handlers,
 * with no knowledge of how that mod places blocks. See {@link #open} /
 * {@link #close} for the split form it uses.</p>
 *
 * <p>Diffing rather than storing the whole region keeps a step proportional to
 * what changed: re-stamping a plot that was already saved records nothing at
 * all.</p>
 *
 * <p>The step also carries the <b>config-file</b> diff for the op, taken over
 * from {@link EditorEditRecorder#takePendingConfig}. Whole-plot ops routinely
 * write JSON as well as blocks — a clear drops its variant entries, a transform
 * moves its container pools — and left to the recorder those files would land in
 * a separate end-of-tick step, so undoing one authored action would take two
 * Ctrl+Z presses.</p>
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
        Capture capture = open(player, label, sidecarPlotKey);
        // Deliberately not a try/finally: an op that throws has left the plot
        // half-written, and pushing that as a step the author can Ctrl+Z into
        // would invent an intent they never had. The capture is simply dropped —
        // exactly what happened before this was split into open / close.
        op.run();
        close(player, capture);
    }

    // ─── Split capture, for callers that cannot wrap ───────────────────────

    /**
     * A capture opened over a plot and not yet closed — the plot's cells, the
     * config tree and (optionally) a variant sidecar, all as they stood before
     * the operation ran.
     *
     * <p>Opaque by design: nothing outside this class reads its contents, which
     * is what keeps {@link Snapshot} private.</p>
     */
    public static final class Capture {
        private final ServerLevel level;
        private final EditorPlotScope scope;
        private final String label;
        @Nullable private final String sidecarPlotKey;
        @Nullable private final String sidecarBefore;
        private final Map<String, String> filesBefore;
        private final Map<BlockPos, Snapshot> before;

        private Capture(ServerLevel level, EditorPlotScope scope, String label,
                        @Nullable String sidecarPlotKey, @Nullable String sidecarBefore,
                        Map<String, String> filesBefore, Map<BlockPos, Snapshot> before) {
            this.level = level;
            this.scope = scope;
            this.label = label;
            this.sidecarPlotKey = sidecarPlotKey;
            this.sidecarBefore = sidecarBefore;
            this.filesBefore = filesBefore;
            this.before = before;
        }

        /** Names the plot this capture covers, for a caller's log lines. */
        public String plotKey() {
            return scope.key();
        }
    }

    /**
     * Take the "before" half of a region capture.
     *
     * <p>The wrapping form {@link #record(ServerPlayer, String, String, Runnable)}
     * is the one to reach for. This pair exists for callers that physically
     * cannot wrap the operation — chiefly a Mixin on another mod's method, where
     * the two halves must be driven from separate {@code HEAD} and
     * {@code RETURN} injectors.</p>
     *
     * <p>Returns null when there is nothing to record against: the player is
     * outside every plot, or the plot is larger than
     * {@link EditorEditHistory#MAX_CELLS_PER_STEP} — in which case that plot's
     * history is dropped, so a later undo cannot apply a step from before an
     * unrecorded change. A null capture is safe to hand straight to
     * {@link #close}, so callers need no branch of their own.</p>
     */
    @Nullable
    public static Capture open(ServerPlayer player, String label, @Nullable String sidecarPlotKey) {
        ServerLevel level = player.serverLevel();
        Optional<EditorPlotScope> maybeScope = EditorPlotScope.resolveAt(player, level);
        if (maybeScope.isEmpty()) return null;
        EditorPlotScope scope = maybeScope.get();

        if (scope.volume() > EditorEditHistory.MAX_CELLS_PER_STEP) {
            EditorEditHistory.clearPlot(player.getUUID(), scope.key());
            return null;
        }

        String sidecarBefore = sidecarPlotKey == null ? null : snapshotSidecar(level, sidecarPlotKey);
        // Taken from the recorder rather than scanned here: every editor command
        // already arms one up front, and leaving it armed would push the file
        // half as a second step at end of tick — two Ctrl+Z presses for one
        // action. Nothing armed (a menu-driven op) means scanning now.
        Map<String, String> filesBefore = EditorEditRecorder.takePendingConfig(player);
        if (filesBefore == null) filesBefore = EditorConfigDiff.scan();
        return new Capture(level, scope, label, sidecarPlotKey, sidecarBefore,
            filesBefore, scan(level, scope));
    }

    /**
     * Close {@code capture}, pushing everything that changed since {@link #open}
     * as one undo step.
     *
     * <p>A null capture is a no-op, and a capture over which nothing changed
     * pushes nothing — {@link EditorEditHistory#push} drops empty steps. Both
     * matter for the Mixin callers, whose {@code RETURN} injector fires on paths
     * that did no work at all.</p>
     */
    public static void close(ServerPlayer player, @Nullable Capture capture) {
        if (capture == null) return;
        ServerLevel level = capture.level;

        List<EditorEditHistory.Cell> cells = diff(level, capture.before);
        List<EditorEditHistory.FileSnapshot> files =
            EditorConfigDiff.diff(capture.filesBefore, EditorConfigDiff.scan());
        List<EditorEditHistory.SidecarSnapshot> sidecars = List.of();
        if (capture.sidecarPlotKey != null) {
            String sidecarAfter = snapshotSidecar(level, capture.sidecarPlotKey);
            if (capture.sidecarBefore != null && !capture.sidecarBefore.equals(sidecarAfter)) {
                sidecars = List.of(new EditorEditHistory.SidecarSnapshot(
                    capture.sidecarPlotKey, capture.sidecarBefore, sidecarAfter));
            }
        }
        EditorEditHistory.push(player.getUUID(),
            new EditorEditHistory.Step(capture.scope.key(), capture.label, cells, sidecars, files));
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
            CompoundTag afterNbt = after.hasBlockEntity() ? EditorEditRecorder.readNbt(level, pos) : null;
            // Not just the state: two chests that trade places under a plot
            // transform leave every state identical and every inventory moved,
            // and a state-only comparison would record none of it.
            if (after == snapshot.state() && Objects.equals(afterNbt, snapshot.nbt())) continue;
            out.add(new EditorEditHistory.Cell(pos, snapshot.state(), snapshot.nbt(), after, afterNbt));
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
