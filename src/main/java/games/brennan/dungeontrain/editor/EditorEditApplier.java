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
        /** Nothing left on the stack. */
        NOTHING,
        /** The plot was rewritten since the step was recorded; its history has been dropped. */
        STALE,
        /** A sidecar write failed — see the log. */
        FAILED
    }

    public record Result(Outcome outcome, String label, String plotKey) {
        static Result of(Outcome outcome) { return new Result(outcome, "", ""); }
    }

    /** Undo the player's most recent editor step, wherever they are standing. */
    public static Result undo(ServerPlayer player) {
        return step(player, /*redoing*/ false);
    }

    /** Redo the step this player most recently undid. */
    public static Result redo(ServerPlayer player) {
        return step(player, /*redoing*/ true);
    }

    private static Result step(ServerPlayer player, boolean redoing) {
        // Every editor plot lives in the overworld, so the step's cells are
        // overworld positions regardless of where the player is now — the Nether,
        // the End, or a Sable sub-level. Resolving from player.serverLevel()
        // would read and write the wrong dimension from any of those.
        ServerLevel level = player.server.overworld();

        Optional<EditorEditHistory.Step> popped = redoing
            ? EditorEditHistory.popRedo(player.getUUID())
            : EditorEditHistory.popUndo(player.getUUID());
        if (popped.isEmpty()) return Result.of(Outcome.NOTHING);
        EditorEditHistory.Step step = popped.get();
        String plotKey = step.plotKey();

        // The step records what it left in the world. If that is no longer what
        // is standing there, the plot has been re-stamped or filled from
        // elsewhere and applying this step would write stale geometry over
        // whatever replaced it.
        if (step.isStale(level::getBlockState)) {
            EditorEditHistory.clearPlot(player.getUUID(), plotKey);
            return new Result(Outcome.STALE, step.label(), plotKey);
        }

        if (!apply(level, step)) return new Result(Outcome.FAILED, step.label(), plotKey);

        // The inverse becomes the step for the opposite direction.
        if (redoing) {
            EditorEditHistory.pushUndoPreservingRedo(player.getUUID(), step.inverted());
        } else {
            EditorEditHistory.pushRedo(player.getUUID(), step.inverted());
        }
        return new Result(Outcome.DONE, step.label(), plotKey);
    }

    /**
     * Write a step's {@code before} side back: block cells into the world,
     * variant sidecars into their caches, menu state into its config files.
     * Returns false when a sidecar or file restore failed — the block cells are
     * still applied, because a half-undone plot the author can see beats one
     * that silently did nothing.
     */
    private static boolean apply(ServerLevel level, EditorEditHistory.Step step) {
        boolean[] ok = {true};
        // Suppression spans the whole application, not just the block writes.
        // The sidecar and file restores are writes too, and if anything had
        // armed a capture earlier in this tick they would otherwise be diffed
        // into a fresh step — an undo that records itself, so the next Ctrl+Z
        // would undo the undo and the history would never move backwards.
        EditorEditRecorder.withRecordingSuppressed(() -> {
            for (EditorEditHistory.Cell cell : step.cells()) {
                SilentBlockOps.setBlockSilent(level, cell.worldPos(), cell.before(), cell.beforeNbt());
            }
            for (EditorEditHistory.SidecarSnapshot snapshot : step.sidecars()) {
                if (!restoreSidecar(level, snapshot)) ok[0] = false;
            }
            for (EditorEditHistory.FileSnapshot snapshot : step.files()) {
                if (!EditorConfigDiff.restore(snapshot)) ok[0] = false;
            }
        });
        return ok[0];
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
