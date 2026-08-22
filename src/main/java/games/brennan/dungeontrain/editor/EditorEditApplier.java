package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * Applies {@link EditorEditHistory.Step}s back onto the world — the undo and
 * redo halves of the editor history.
 *
 * <p><b>One direction of travel.</b> A step always describes an edit as
 * {@code before → after}, and applying it always writes the {@code before}
 * side. Redo reuses that single path by storing the step
 * {@link EditorEditHistory.Step#inverted() inverted}, so there is one apply
 * routine and one staleness rule rather than two of each.</p>
 *
 * <p>Writes go through {@link SilentBlockOps#setBlockSilent}: no particles, no
 * drops, no block events — so an undo re-enters neither
 * {@link EditorEditRecorder} nor {@link EditorMirrorLiveHandler}, and
 * block-entity contents round-trip (an undone chest break comes back full).
 * Mirror images do not need re-deriving here because the recorder already
 * folded them into the step.</p>
 */
public final class EditorEditApplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorEditApplier() {}

    /** What happened, for the caller to phrase back to the player. */
    public enum Outcome {
        /** Applied. {@link Result#label} names the step. */
        DONE,
        /** The player is not standing in an editor plot. */
        NOT_IN_PLOT,
        /** Nothing left on the stack. */
        NOTHING,
        /** The plot was rewritten since the step was recorded; its history has been dropped. */
        STALE,
        /** A sidecar write failed — see the log. */
        FAILED
    }

    public record Result(Outcome outcome, String label) {
        static Result of(Outcome outcome) { return new Result(outcome, ""); }
    }

    /** Undo the player's most recent step in the plot they are standing in. */
    public static Result undo(ServerPlayer player) {
        return step(player, /*redoing*/ false);
    }

    /** Redo the step most recently undone in the plot the player is standing in. */
    public static Result redo(ServerPlayer player) {
        return step(player, /*redoing*/ true);
    }

    private static Result step(ServerPlayer player, boolean redoing) {
        ServerLevel level = player.serverLevel();
        Optional<EditorPlotScope> scope = EditorPlotScope.resolveAt(player, level);
        if (scope.isEmpty()) return Result.of(Outcome.NOT_IN_PLOT);
        String plotKey = scope.get().key();

        Optional<EditorEditHistory.Step> popped = redoing
            ? EditorEditHistory.popRedo(player.getUUID(), plotKey)
            : EditorEditHistory.popUndo(player.getUUID(), plotKey);
        if (popped.isEmpty()) return Result.of(Outcome.NOTHING);
        EditorEditHistory.Step step = popped.get();

        // The step records what it left in the world. If that is no longer what
        // is standing there, the plot has been re-stamped or filled from
        // elsewhere and applying this step would write stale geometry over
        // whatever replaced it.
        if (step.isStale(level::getBlockState)) {
            EditorEditHistory.clearPlot(player.getUUID(), plotKey);
            return Result.of(Outcome.STALE);
        }

        if (!apply(level, step)) return new Result(Outcome.FAILED, step.label());

        // The inverse becomes the step for the opposite direction.
        if (redoing) {
            EditorEditHistory.pushUndoPreservingRedo(player.getUUID(), step.inverted());
        } else {
            EditorEditHistory.pushRedo(player.getUUID(), step.inverted());
        }
        return new Result(Outcome.DONE, step.label());
    }

    /**
     * Write a step's {@code before} side into the world. Returns false when a
     * sidecar restore failed — the block cells are still applied, because a
     * half-undone plot the author can see beats one that silently did nothing.
     */
    private static boolean apply(ServerLevel level, EditorEditHistory.Step step) {
        boolean ok = true;
        EditorEditRecorder.withRecordingSuppressed(() -> {
            for (EditorEditHistory.Cell cell : step.cells()) {
                SilentBlockOps.setBlockSilent(level, cell.worldPos(), cell.before(), cell.beforeNbt());
            }
        });
        for (EditorEditHistory.SidecarSnapshot snapshot : step.sidecars()) {
            if (!restoreSidecar(level, snapshot)) ok = false;
        }
        return ok;
    }

    /**
     * Put one variant sidecar back to its recorded JSON.
     *
     * <p>The restored document is written and the cache dropped, then the plot
     * is re-resolved and saved. That second save looks redundant but is what
     * makes each sidecar apply its own rules to the restored state: the
     * "no entries and default mirror means delete the file" collapse, and the
     * dev-mode write-through into {@code src/} that every ordinary edit
     * performs.</p>
     */
    private static boolean restoreSidecar(ServerLevel level, EditorEditHistory.SidecarSnapshot snapshot) {
        if (snapshot.beforeJson() == null) return true;
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveByKey(snapshot.plotKey(), dims);
        if (plot == null) {
            LOGGER.warn("[DungeonTrain] Editor undo: sidecar plot {} no longer resolves — skipped",
                snapshot.plotKey());
            return false;
        }
        try {
            plot.restoreJson(snapshot.beforeJson());
            BlockVariantPlot reloaded = BlockVariantPlot.resolveByKey(snapshot.plotKey(), dims);
            if (reloaded != null) reloaded.save();
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Editor undo: sidecar restore failed for {}: {}",
                snapshot.plotKey(), e.toString());
            return false;
        }
    }
}
