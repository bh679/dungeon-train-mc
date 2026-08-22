package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the editor undo / redo bookkeeping in
 * {@link EditorEditHistory} — stack ordering, the redo fork, the two caps, and
 * the staleness check that stands in for invalidation hooks.
 *
 * <p>Constructs {@code Blocks.*} singletons, so it relies on the NeoForge
 * moddev bootstrap enabled by {@code unitTest.enable()} in build.gradle — same
 * setup as {@link EditorMirrorTest}.</p>
 */
final class EditorEditHistoryTest {

    private static final String PLOT = "carriage:standard";
    private static final String OTHER_PLOT = "contents:beds";

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState OAK = Blocks.OAK_PLANKS.defaultBlockState();

    private UUID player;

    @BeforeEach
    void reset() {
        // The history is a static singleton — a leftover stack from a previous
        // test would make ordering assertions meaningless.
        EditorEditHistory.clearAll();
        player = UUID.randomUUID();
    }

    /** One-cell step: {@code before} → {@code after} at {@code x,0,0} in {@code plotKey}. */
    private static EditorEditHistory.Step step(String plotKey, String label, int x,
                                               BlockState before, BlockState after) {
        return new EditorEditHistory.Step(plotKey, label,
            List.of(new EditorEditHistory.Cell(new BlockPos(x, 0, 0), before, null, after, null)),
            List.of());
    }

    // ─── ordering ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Undo pops the newest step first")
    void undoIsLastInFirstOut() {
        EditorEditHistory.push(player, step(PLOT, "one", 1, AIR, STONE));
        EditorEditHistory.push(player, step(PLOT, "two", 2, AIR, STONE));
        EditorEditHistory.push(player, step(PLOT, "three", 3, AIR, STONE));

        assertEquals("three", EditorEditHistory.popUndo(player).orElseThrow().label());
        assertEquals("two", EditorEditHistory.popUndo(player).orElseThrow().label());
        assertEquals("one", EditorEditHistory.popUndo(player).orElseThrow().label());
        assertTrue(EditorEditHistory.popUndo(player).isEmpty(), "stack drained");
    }

    @Test
    @DisplayName("Empty history pops nothing rather than failing")
    void emptyHistoryIsEmptyOptional() {
        assertTrue(EditorEditHistory.popUndo(player).isEmpty());
        assertTrue(EditorEditHistory.popRedo(player).isEmpty());
        assertEquals(0, EditorEditHistory.undoDepth(player));
    }

    @Test
    @DisplayName("A step that changed nothing is not recorded")
    void emptyStepIsNotPushed() {
        EditorEditHistory.Step nothing =
            new EditorEditHistory.Step(PLOT, "no-op", List.of(), List.of());
        assertTrue(nothing.isEmpty());
        assertFalse(EditorEditHistory.push(player, nothing));
        assertEquals(0, EditorEditHistory.undoDepth(player));
    }

    // ─── the redo fork ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Undo then redo restores the step; a new edit clears redo")
    void redoForksOnNewEdit() {
        EditorEditHistory.push(player, step(PLOT, "one", 1, AIR, STONE));

        EditorEditHistory.Step undone = EditorEditHistory.popUndo(player).orElseThrow();
        EditorEditHistory.pushRedo(player, undone.inverted());
        assertEquals(1, EditorEditHistory.redoDepth(player));

        // Redoing puts it back on the undo stack without touching redo depth
        // beyond the pop itself.
        EditorEditHistory.Step redone = EditorEditHistory.popRedo(player).orElseThrow();
        EditorEditHistory.pushUndoPreservingRedo(player, redone.inverted());
        assertEquals(1, EditorEditHistory.undoDepth(player));
        assertEquals(0, EditorEditHistory.redoDepth(player));

        // Undo again, then make a fresh edit — the redo branch is abandoned.
        EditorEditHistory.pushRedo(player,
            EditorEditHistory.popUndo(player).orElseThrow().inverted());
        assertEquals(1, EditorEditHistory.redoDepth(player));
        EditorEditHistory.push(player, step(PLOT, "fresh", 9, AIR, OAK));
        assertEquals(0, EditorEditHistory.redoDepth(player), "new edit forks the timeline");
    }

    @Test
    @DisplayName("inverted() swaps before and after for cells and sidecars")
    void invertedSwapsBothPayloads() {
        EditorEditHistory.Step forward = new EditorEditHistory.Step(PLOT, "swap",
            List.of(new EditorEditHistory.Cell(new BlockPos(1, 2, 3), STONE, null, OAK, null)),
            List.of(new EditorEditHistory.SidecarSnapshot(PLOT, null, "{\"variants\":{}}")));

        EditorEditHistory.Step back = forward.inverted();

        assertSame(OAK, back.cells().get(0).before());
        assertSame(STONE, back.cells().get(0).after());
        assertEquals("{\"variants\":{}}", back.sidecars().get(0).beforeJson());
        assertEquals(null, back.sidecars().get(0).afterJson(), "undoing a created sidecar removes it");
    }

    // ─── staleness ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A step is stale once the world no longer holds what it left behind")
    void stalenessDetectsARewrittenPlot() {
        BlockPos pos = new BlockPos(4, 5, 6);
        EditorEditHistory.Step placed = new EditorEditHistory.Step(PLOT, "place",
            List.of(new EditorEditHistory.Cell(pos, AIR, null, STONE, null)), List.of());

        Map<BlockPos, BlockState> world = new java.util.HashMap<>(Map.of(pos, STONE));
        assertFalse(placed.isStale(world::get), "world still matches the recorded after-state");

        // A re-stamp (or an outside /fill) replaces the cell.
        world.put(pos, OAK);
        assertTrue(placed.isStale(world::get), "plot was rewritten underneath the step");
    }

    @Test
    @DisplayName("A sidecar-only step is never stale")
    void sidecarOnlyStepIsNeverStale() {
        EditorEditHistory.Step sidecarOnly = new EditorEditHistory.Step(PLOT, "variant add",
            List.of(),
            List.of(new EditorEditHistory.SidecarSnapshot(PLOT, "{}", "{\"variants\":{}}")));
        assertFalse(sidecarOnly.isStale(p -> {
            throw new AssertionError("no block should be read for a sidecar-only step");
        }));
    }

    // ─── caps and isolation ────────────────────────────────────────────────

    @Test
    @DisplayName("The undo stack caps out, dropping the oldest step")
    void capacityEvictsOldest() {
        for (int i = 0; i <= EditorEditHistory.MAX_STEPS; i++) {
            EditorEditHistory.push(player, step(PLOT, "edit-" + i, i, AIR, STONE));
        }
        assertEquals(EditorEditHistory.MAX_STEPS, EditorEditHistory.undoDepth(player));

        // Drain to the bottom — "edit-0" fell off, so the oldest survivor is edit-1.
        String oldest = null;
        for (Optional<EditorEditHistory.Step> s = EditorEditHistory.popUndo(player);
             s.isPresent(); s = EditorEditHistory.popUndo(player)) {
            oldest = s.get().label();
        }
        assertEquals("edit-1", oldest);
    }

    @Test
    @DisplayName("An over-cap step is refused and takes the plot's history with it")
    void overSizedStepDropsHistory() {
        EditorEditHistory.push(player, step(PLOT, "earlier", 1, AIR, STONE));

        List<EditorEditHistory.Cell> huge =
            new ArrayList<>(EditorEditHistory.MAX_CELLS_PER_STEP + 1);
        for (int i = 0; i <= EditorEditHistory.MAX_CELLS_PER_STEP; i++) {
            huge.add(new EditorEditHistory.Cell(new BlockPos(i, 0, 0), AIR, null, STONE, null));
        }

        assertFalse(EditorEditHistory.push(player,
            new EditorEditHistory.Step(PLOT, "enormous", huge, List.of())));
        assertEquals(0, EditorEditHistory.undoDepth(player),
            "a partial undo is worse than none — the whole plot's history goes");
    }

    @Test
    @DisplayName("One stack per player: steps from different plots interleave in order")
    void oneStackPerPlayerAcrossPlots() {
        EditorEditHistory.push(player, step(PLOT, "carriage edit", 1, AIR, STONE));
        EditorEditHistory.push(player, step(OTHER_PLOT, "contents edit", 1, AIR, STONE));
        EditorEditHistory.push(player, step(PLOT, "carriage again", 2, AIR, STONE));

        assertEquals(3, EditorEditHistory.undoDepth(player),
            "one stack, regardless of which plot each step belongs to");
        // Undo walks back through them in the order they were made, not grouped by plot.
        assertEquals("carriage again", EditorEditHistory.popUndo(player).orElseThrow().label());
        assertEquals("contents edit", EditorEditHistory.popUndo(player).orElseThrow().label());
        assertEquals("carriage edit", EditorEditHistory.popUndo(player).orElseThrow().label());
    }

    @Test
    @DisplayName("Clearing one plot spares steps from the others")
    void clearPlotIsSurgical() {
        EditorEditHistory.push(player, step(PLOT, "stale plot", 1, AIR, STONE));
        EditorEditHistory.push(player, step(OTHER_PLOT, "innocent bystander", 1, AIR, STONE));
        EditorEditHistory.push(player, step(PLOT, "stale plot again", 2, AIR, STONE));

        EditorEditHistory.clearPlot(player, PLOT);

        assertEquals(1, EditorEditHistory.undoDepth(player),
            "a rebuilt plot must not cost the author edits made elsewhere");
        assertEquals("innocent bystander", EditorEditHistory.popUndo(player).orElseThrow().label());
    }

    @Test
    @DisplayName("Players keep separate histories")
    void playersAreIsolated() {
        UUID other = UUID.randomUUID();
        EditorEditHistory.push(player, step(PLOT, "mine", 1, AIR, STONE));
        EditorEditHistory.push(other, step(PLOT, "theirs", 1, AIR, STONE));

        assertEquals(1, EditorEditHistory.undoDepth(player));
        assertEquals(1, EditorEditHistory.undoDepth(other));

        EditorEditHistory.clearPlayer(player);
        assertEquals(0, EditorEditHistory.undoDepth(player));
        assertEquals(1, EditorEditHistory.undoDepth(other), "other player untouched");
    }
}
