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
 * of stacks per player, holding {@link Step}s that describe exactly how to put
 * the edit back.
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
 * <p><b>One stack per player, not per plot.</b> Ctrl+Z means "undo the last thing
 * I did" wherever the author happens to be standing — they may have changed a
 * weight and walked off before noticing. Each {@link Step} carries its own
 * {@code plotKey}, so it still knows which plot it belongs to; that is a label
 * and the scope for {@link #clearPlot}, not a lookup key.</p>
 *
 * <p>State is in-memory and per-session, matching {@link EditorPlotSnapshots}:
 * a server restart drops it, and the plot re-stamp that follows would have
 * invalidated it anyway.</p>
 */
public final class EditorEditHistory {

    /** Deepest undo stack kept per player. Older steps fall off the bottom. */
    public static final int MAX_STEPS = 64;

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
     * One config file's serialised before / after, keyed by path relative to the
     * editor's active write directory. {@code null} means the file did not
     * exist, so undoing a file's creation deletes it rather than leaving an
     * empty document behind.
     */
    public record FileSnapshot(String relPath,
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
                       List<Cell> cells, List<SidecarSnapshot> sidecars, List<FileSnapshot> files) {

        public Step {
            cells = List.copyOf(cells);
            sidecars = List.copyOf(sidecars);
            files = List.copyOf(files);
        }

        /** Convenience for the block-only and sidecar-only producers. */
        public Step(String plotKey, String label, List<Cell> cells, List<SidecarSnapshot> sidecars) {
            this(plotKey, label, cells, sidecars, List.of());
        }

        /** A step with nothing in it is never pushed — the op ran but changed nothing. */
        public boolean isEmpty() {
            return cells.isEmpty() && sidecars.isEmpty() && files.isEmpty();
        }

        /**
         * Has the world moved on since this step was recorded? True when any cell
         * no longer holds the state the step left there.
         *
         * <p>Steps with no block cells — a sidecar or menu change — are never
         * stale: that state is read back through the same store the edit wrote
         * to, so there is no third party to race with.</p>
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
            List<FileSnapshot> flippedFiles = new ArrayList<>(files.size());
            for (FileSnapshot f : files) {
                flippedFiles.add(new FileSnapshot(f.relPath(), f.afterJson(), f.beforeJson()));
            }
            return new Step(plotKey, label, flipped, flippedSidecars, flippedFiles);
        }
    }

    // ─── Stacks ────────────────────────────────────────────────────────────

    /** One player's pair of stacks. Undo pops here and pushes the inverse onto redo. */
    private static final class Stacks {
        private final Deque<Step> undo = new ArrayDeque<>();
        private final Deque<Step> redo = new ArrayDeque<>();
    }

    /** player → stacks. Guarded by this class's monitor. */
    private static final Map<UUID, Stacks> HISTORY = new HashMap<>();

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
        Stacks stacks = stacksFor(player);
        stacks.undo.addLast(step);
        stacks.redo.clear();
        trim(stacks.undo);
        return true;
    }

    /**
     * Take the newest undoable step for this plot off the stack. The caller
     * applies it and then hands the inverse to {@link #pushRedo}; leaving that
     * to the caller keeps a failed application from losing the step.
     */
    public static synchronized Optional<Step> popUndo(UUID player) {
        Stacks stacks = HISTORY.get(player);
        if (stacks == null || stacks.undo.isEmpty()) return Optional.empty();
        return Optional.of(stacks.undo.removeLast());
    }

    /** Take the newest redoable step off the stack. */
    public static synchronized Optional<Step> popRedo(UUID player) {
        Stacks stacks = HISTORY.get(player);
        if (stacks == null || stacks.redo.isEmpty()) return Optional.empty();
        return Optional.of(stacks.redo.removeLast());
    }

    /** Push a step onto the redo stack — the inverse of one just undone. */
    public static synchronized void pushRedo(UUID player, Step step) {
        Stacks stacks = stacksFor(player);
        stacks.redo.addLast(step);
        trim(stacks.redo);
    }

    /**
     * Push a step back onto the undo stack without clearing redo — used when a
     * redo is applied, so the step becomes undoable again.
     */
    public static synchronized void pushUndoPreservingRedo(UUID player, Step step) {
        Stacks stacks = stacksFor(player);
        stacks.undo.addLast(step);
        trim(stacks.undo);
    }

    public static synchronized int undoDepth(UUID player) {
        Stacks stacks = HISTORY.get(player);
        return stacks == null ? 0 : stacks.undo.size();
    }

    public static synchronized int redoDepth(UUID player) {
        Stacks stacks = HISTORY.get(player);
        return stacks == null ? 0 : stacks.redo.size();
    }

    /**
     * Drop every step belonging to one plot — the staleness and over-cap path.
     *
     * <p>Filters within the player's single stack rather than dropping a whole
     * keyed entry: a stale carriage plot must not cost the author the track-side
     * edits they interleaved with it.</p>
     */
    public static synchronized void clearPlot(UUID player, String plotKey) {
        Stacks stacks = HISTORY.get(player);
        if (stacks == null) return;
        stacks.undo.removeIf(step -> step.plotKey().equals(plotKey));
        stacks.redo.removeIf(step -> step.plotKey().equals(plotKey));
        if (stacks.undo.isEmpty() && stacks.redo.isEmpty()) HISTORY.remove(player);
    }

    /** Drop every plot's history for one player — logout, editor exit. */
    public static synchronized void clearPlayer(UUID player) {
        HISTORY.remove(player);
    }

    /** Wipe everything — category switch ({@code clearAllPlots}) and server stop. */
    public static synchronized void clearAll() {
        HISTORY.clear();
    }

    private static Stacks stacksFor(UUID player) {
        return HISTORY.computeIfAbsent(player, p -> new Stacks());
    }

    /** Keep a stack at {@link #MAX_STEPS}, dropping from the old end. */
    private static void trim(Deque<Step> stack) {
        while (stack.size() > MAX_STEPS) {
            stack.removeFirst();
        }
    }
}
