package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Server-side undo / redo history for the in-world template editors — one pair
 * of stacks per (player, plot), holding {@link Step}s that describe exactly how
 * to put the plot back.
 *
 * <p>A step is deliberately format-agnostic about <em>how</em> it was captured.
 * Three producers push into the same stack so Ctrl+Z always means "the last
 * thing I did", whichever mechanism produced it:</p>
 * <ul>
 *   <li>{@link EditorEditRecorder} — a tick's worth of player place / break
 *       edits, plus whatever live mirroring wrote alongside them;</li>
 *   <li>{@link EditorRegionDiff} — a whole-plot operation (Clear, Reset, mirror
 *       rebuild, bulk block swap), captured as a before/after region diff; and</li>
 *   <li>the variant-sidecar edit paths, which carry no block cells at all and
 *       push a {@link SidecarSnapshot} instead.</li>
 * </ul>
 *
 * <p><b>No world access lives here.</b> This class is pure bookkeeping so it can
 * be unit-tested without a level; {@link EditorEditApplier} owns every read and
 * write. The one concession is {@link Step#isStale}, which takes a state reader
 * as a function rather than a {@code ServerLevel}.</p>
 *
 * <p><b>Staleness instead of invalidation hooks.</b> Every cell records the state
 * it left behind, so an undo can tell whether the plot has been rewritten
 * underneath it — a re-stamp on editor re-entry, a mirror rebuild, an outside
 * {@code /fill}. A stale step is refused and its plot's history dropped rather
 * than applied over unrelated geometry. That covers every rewrite path,
 * including ones added later, without threading clear-calls through the ~20
 * {@link EditorPlotSnapshots} capture sites — several of which also run on
 * <em>save</em>, where the history must survive.</p>
 *
 * <p>State is in-memory and per-session, matching {@link EditorPlotSnapshots}:
 * a server restart drops it, and the plot re-stamp that follows would have
 * invalidated it anyway.</p>
 */
public final class EditorEditHistory {

    /** Deepest undo stack kept per (player, plot). Older steps fall off the bottom. */
    public static final int MAX_STEPS_PER_PLOT = 64;

    /**
     * Largest single step recorded. A bigger one drops the plot's whole history
     * rather than being truncated — half an undo is worse than none, because the
     * author cannot tell which half they got.
     */
    public static final int MAX_CELLS_PER_STEP = 20_000;

    private EditorEditHistory() {}

    // ─── Step model ────────────────────────────────────────────────────────

    /**
     * One block cell's before / after, world-space. {@code before} is what undo
     * restores; {@code after} is what the edit left behind, and what
     * {@link Step#isStale} checks the world against.
     *
     * <p>The NBT tags carry block-entity contents so a broken-and-undone chest
     * comes back full. Null for cells whose state has no block entity.</p>
     */
    public record Cell(BlockPos worldPos,
                       BlockState before, @Nullable CompoundTag beforeNbt,
                       BlockState after, @Nullable CompoundTag afterNbt) {}

    /**
     * A variant sidecar's serialised before / after. {@code null} JSON means
     * "no sidecar file" — an add that created the first entry undoes back to
     * absence, not to an empty document.
     */
    public record SidecarSnapshot(String plotKey,
                                  @Nullable String beforeJson,
                                  @Nullable String afterJson) {}

    /**
     * One undoable action. Carries block cells, sidecar snapshots, or both — a
     * bulk block swap changes the plot's geometry <em>and</em> its variant pools,
     * and the author thinks of that as a single thing they did.
     *
     * <p>{@code label} is shown in the action-bar feedback ("Undid: Clear").</p>
     */
    public record Step(String plotKey, String label,
                       List<Cell> cells, List<SidecarSnapshot> sidecars) {

        public Step {
            cells = List.copyOf(cells);
            sidecars = List.copyOf(sidecars);
        }

        /** A step with nothing in it is never pushed — the op ran but changed nothing. */
        public boolean isEmpty() {
            return cells.isEmpty() && sidecars.isEmpty();
        }

        /**
         * Has the world moved on since this step was recorded? True when any cell
         * no longer holds the state the step left there.
         *
         * <p>Sidecar-only steps are never stale: sidecars are read back through
         * the same cache the edit wrote to, so there is no third party to race
         * with.</p>
         */
        public boolean isStale(Function<BlockPos, BlockState> stateReader) {
            for (Cell cell : cells) {
                if (stateReader.apply(cell.worldPos()) != cell.after()) return true;
            }
            return false;
        }

        /** This step with before / after swapped — what redo needs after an undo. */
        public Step inverted() {
            List<Cell> flipped = new ArrayList<>(cells.size());
            for (Cell c : cells) {
                flipped.add(new Cell(c.worldPos(), c.after(), c.afterNbt(), c.before(), c.beforeNbt()));
            }
            List<SidecarSnapshot> flippedSidecars = new ArrayList<>(sidecars.size());
            for (SidecarSnapshot s : sidecars) {
                flippedSidecars.add(new SidecarSnapshot(s.plotKey(), s.afterJson(), s.beforeJson()));
            }
            return new Step(plotKey, label, flipped, flippedSidecars);
        }
    }

    // ─── Stacks ────────────────────────────────────────────────────────────

    /** One plot's pair of stacks. Undo pops here and pushes the inverse onto redo. */
    private static final class Stacks {
        private final Deque<Step> undo = new ArrayDeque<>();
        private final Deque<Step> redo = new ArrayDeque<>();
    }

    /** player → plot key → stacks. Guarded by this class's monitor. */
    private static final Map<UUID, Map<String, Stacks>> HISTORY = new HashMap<>();

    /**
     * Record a step against its plot, clearing the redo stack — a fresh edit
     * after an undo forks the timeline, which is what every editor does.
     *
     * <p>Returns false and drops the plot's history when the step is empty
     * (nothing to record) or over {@link #MAX_CELLS_PER_STEP}; callers that care
     * about the difference should check {@link Step#isEmpty} first.</p>
     */
    public static synchronized boolean push(UUID player, Step step) {
        if (step.isEmpty()) return false;
        if (step.cells().size() > MAX_CELLS_PER_STEP) {
            clearPlot(player, step.plotKey());
            return false;
        }
        Stacks stacks = stacksFor(player, step.plotKey());
        stacks.undo.addLast(step);
        stacks.redo.clear();
        while (stacks.undo.size() > MAX_STEPS_PER_PLOT) {
            stacks.undo.removeFirst();
        }
        return true;
    }

    /**
     * Take the newest undoable step for this plot off the stack. The caller
     * applies it and then hands the inverse to {@link #pushRedo}; leaving that
     * to the caller keeps a failed application from losing the step.
     */
    public static synchronized Optional<Step> popUndo(UUID player, String plotKey) {
        Stacks stacks = existingStacks(player, plotKey);
        if (stacks == null || stacks.undo.isEmpty()) return Optional.empty();
        return Optional.of(stacks.undo.removeLast());
    }

    /** Take the newest redoable step for this plot off the stack. */
    public static synchronized Optional<Step> popRedo(UUID player, String plotKey) {
        Stacks stacks = existingStacks(player, plotKey);
        if (stacks == null || stacks.redo.isEmpty()) return Optional.empty();
        return Optional.of(stacks.redo.removeLast());
    }

    /** Push a step onto the redo stack — the inverse of one just undone. */
    public static synchronized void pushRedo(UUID player, Step step) {
        Stacks stacks = stacksFor(player, step.plotKey());
        stacks.redo.addLast(step);
        while (stacks.redo.size() > MAX_STEPS_PER_PLOT) {
            stacks.redo.removeFirst();
        }
    }

    /**
     * Push a step back onto the undo stack without clearing redo — used when a
     * redo is applied, so the step becomes undoable again.
     */
    public static synchronized void pushUndoPreservingRedo(UUID player, Step step) {
        Stacks stacks = stacksFor(player, step.plotKey());
        stacks.undo.addLast(step);
        while (stacks.undo.size() > MAX_STEPS_PER_PLOT) {
            stacks.undo.removeFirst();
        }
    }

    public static synchronized int undoDepth(UUID player, String plotKey) {
        Stacks stacks = existingStacks(player, plotKey);
        return stacks == null ? 0 : stacks.undo.size();
    }

    public static synchronized int redoDepth(UUID player, String plotKey) {
        Stacks stacks = existingStacks(player, plotKey);
        return stacks == null ? 0 : stacks.redo.size();
    }

    /** Drop one plot's history for one player — the staleness and over-cap path. */
    public static synchronized void clearPlot(UUID player, String plotKey) {
        Map<String, Stacks> byPlot = HISTORY.get(player);
        if (byPlot == null) return;
        byPlot.remove(plotKey);
        if (byPlot.isEmpty()) HISTORY.remove(player);
    }

    /** Drop every plot's history for one player — logout, editor exit. */
    public static synchronized void clearPlayer(UUID player) {
        HISTORY.remove(player);
    }

    /** Wipe everything — category switch ({@code clearAllPlots}) and server stop. */
    public static synchronized void clearAll() {
        HISTORY.clear();
    }

    private static Stacks stacksFor(UUID player, String plotKey) {
        return HISTORY.computeIfAbsent(player, p -> new HashMap<>())
            .computeIfAbsent(plotKey, k -> new Stacks());
    }

    @Nullable
    private static Stacks existingStacks(UUID player, String plotKey) {
        Map<String, Stacks> byPlot = HISTORY.get(player);
        return byPlot == null ? null : byPlot.get(plotKey);
    }
}
