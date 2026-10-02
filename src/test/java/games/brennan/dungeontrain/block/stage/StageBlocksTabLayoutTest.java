package games.brennan.dungeontrain.block.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Stage Blocks tab layout holds every placeholder once, and pads each row to the grid width. */
final class StageBlocksTabLayoutTest {

    private static final String GAP = StageBlocksTabLayout.GAP;

    @Test
    @DisplayName("every placeholder is in the grid exactly once")
    void coversEveryPlaceholderOnce() {
        List<String> names = StageBlocksTabLayout.names();
        assertEquals(new HashSet<>(StagePlaceholderBlocks.names()), new HashSet<>(names));
        assertEquals(StagePlaceholderBlocks.names().size(), names.size(), "a placeholder is listed twice");
    }

    @Test
    @DisplayName("no row is wider than the grid, and none starts or ends on a gap")
    void rowsFitTheGrid() {
        for (List<String> row : StageBlocksTabLayout.rows()) {
            assertTrue(!row.isEmpty() && row.size() <= StageBlocksTabLayout.COLUMNS, "bad row: " + row);
            assertTrue(!row.get(0).equals(GAP) && !row.get(row.size() - 1).equals(GAP), "bad row: " + row);
        }
    }

    @Test
    @DisplayName("padding puts every cell where the grid says, blanks included")
    void paddedMatchesGrid() {
        List<String> padded = StageBlocksTabLayout.padded(StageBlocksTabLayout.names(), GAP);
        List<List<String>> rows = StageBlocksTabLayout.rows();
        assertEquals(rows.size() * StageBlocksTabLayout.COLUMNS, padded.size());
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < StageBlocksTabLayout.COLUMNS; c++) {
                String expected = c < rows.get(r).size() ? rows.get(r).get(c) : GAP;
                assertEquals(expected, padded.get(r * StageBlocksTabLayout.COLUMNS + c), "row " + r + " col " + c);
            }
        }
    }

    @Test
    @DisplayName("stone kinds sit two to a row, each reading block, stairs, slab, wall")
    void stoneRowsPairUp() {
        assertTrue(StageBlocksTabLayout.rows().contains(List.of(
            "stage_stone_cobbled", "stage_stone_cobbled_stairs", "stage_stone_cobbled_slab", "stage_stone_cobbled_wall",
            GAP,
            "stage_stone", "stage_stone_stairs", "stage_stone_slab", "stage_stone_wall")));
    }

    @Test
    @DisplayName("wood stairs and slab finish the logs row; terracotta shares a row with concrete")
    void groupedRows() {
        assertTrue(StageBlocksTabLayout.rows().contains(List.of(
            "stage_log", "stage_stripped_log", "stage_wood", "stage_stripped_wood",
            "stage_planks", "stage_leaves", "stage_wood_stairs", "stage_wood_slab")));
        assertTrue(StageBlocksTabLayout.rows().contains(List.of(
            "stage_terracotta_primary", "stage_terracotta_secondary", "stage_terracotta_background",
            "stage_glazed_terracotta", GAP,
            "stage_concrete_primary", "stage_concrete_secondary", "stage_concrete_background")));
    }

    @Test
    @DisplayName("a list that is not the full set is left alone")
    void mismatchedListUntouched() {
        List<String> some = List.of("stage_block_1", "stage_fence");
        assertSame(some, StageBlocksTabLayout.padded(some, GAP));
    }
}
