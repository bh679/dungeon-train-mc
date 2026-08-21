package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the preview flow veto's backing set ({@link EditorPreviewLiquids}).
 *
 * <p>The property that matters is <b>no stale vetoes</b>. The set is rebuilt whole on every 1 Hz
 * preview pass, so a plot the author walked away from must drop out on its own — if entries could
 * linger, they would silently freeze real fluid at those coordinates forever. The mirror of that:
 * the veto must actually be in force for every cell the current pass stamped, including cells whose
 * block did not change that pass.</p>
 */
final class EditorPreviewLiquidsTest {

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    private static final BlockPos CELL = new BlockPos(12, 231, 4);
    private static final BlockPos NEIGHBOUR = new BlockPos(13, 231, 4);

    @BeforeEach
    @AfterEach
    void reset() {
        EditorPreviewLiquids.clear();
    }

    @Test
    @DisplayName("A stamped cell is vetoed; its neighbours are not")
    void marksOnlyStampedCells() {
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL), "clean start");

        EditorPreviewLiquids.replace(OVERWORLD, Set.of(CELL.asLong()));
        assertTrue(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL));
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, NEIGHBOUR),
            "veto must be exact — a neighbouring cell is ordinary world");
        assertEquals(1, EditorPreviewLiquids.count(OVERWORLD));
    }

    @Test
    @DisplayName("An empty pass clears the level — a plot walked away from stops vetoing")
    void emptyPassClearsStaleEntries() {
        EditorPreviewLiquids.replace(OVERWORLD, Set.of(CELL.asLong(), NEIGHBOUR.asLong()));
        assertEquals(2, EditorPreviewLiquids.count(OVERWORLD));

        // The ticker publishes whatever the pass collected; a pass that visits no plot publishes
        // nothing, which is how the veto lapses without explicit teardown.
        EditorPreviewLiquids.replace(OVERWORLD, Set.of());
        assertEquals(0, EditorPreviewLiquids.count(OVERWORLD));
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL));
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, NEIGHBOUR));
    }

    @Test
    @DisplayName("Replace swaps the whole set rather than accumulating")
    void replaceIsNotAdditive() {
        EditorPreviewLiquids.replace(OVERWORLD, Set.of(CELL.asLong()));
        EditorPreviewLiquids.replace(OVERWORLD, Set.of(NEIGHBOUR.asLong()));
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL),
            "the cycle moved off the liquid entry — the old cell must stop being vetoed");
        assertTrue(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, NEIGHBOUR));
        assertEquals(1, EditorPreviewLiquids.count(OVERWORLD));
    }

    @Test
    @DisplayName("Levels are isolated — same coordinates in another dimension are untouched")
    void levelsAreIsolated() {
        EditorPreviewLiquids.replace(OVERWORLD, Set.of(CELL.asLong()));
        assertTrue(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL));
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(NETHER, CELL));

        // Clearing one level must not clear the other: both tick independently.
        EditorPreviewLiquids.replace(NETHER, Set.of(NEIGHBOUR.asLong()));
        EditorPreviewLiquids.replace(OVERWORLD, Set.of());
        assertFalse(EditorPreviewLiquids.isPreviewLiquid(OVERWORLD, CELL));
        assertTrue(EditorPreviewLiquids.isPreviewLiquid(NETHER, NEIGHBOUR),
            "the other level's preview must survive an empty pass elsewhere");
    }
}
