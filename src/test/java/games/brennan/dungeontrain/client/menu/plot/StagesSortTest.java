package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.plot.StagesSort.Column;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Stages panel's sort: a permutation over the server's rows, never a reordered list.
 *
 * <p>Clicks are sent back by server index, so the one thing that must hold is that the row drawn
 * at display position {@code r} is {@code order[r]} — every case here asserts the permutation
 * itself, not a re-sorted copy.</p>
 */
class StagesSortTest {

    /** name, min, max, phaseMask — the four fields the sort reads (plus modelId = name). */
    private static Variant stage(String name, int min, int max, int phaseMask) {
        return new Variant(name, 1, min, max, phaseMask, "stages", name, name, false, false, List.of(), List.of());
    }

    // Server order: oak(131–160, 4 phases) · spruce(436–∞, 4) · nether(0–∞, 2) · Birch(161–175, 4)
    private static final List<Variant> STAGES = List.of(
        stage("wood_oak", 131, 160, 0b1111),
        stage("spruce", 436, -1, 0b1111),
        stage("nether", 0, -1, 0b0110),
        stage("Birch", 161, 175, 0b1111));

    private static final ToIntFunction<String> BLOCKS =
        id -> Map.of("wood_oak", 11, "spruce", 10, "nether", 32, "Birch", 0).get(id);

    private static int[] order(Column column, boolean descending) {
        return StagesSort.order(STAGES, BLOCKS, column, descending);
    }

    @AfterEach
    void reset() {
        StagesSort.clear();
    }

    @Test
    @DisplayName("no column keeps the server's order")
    void noColumnIsIdentity() {
        assertArrayEquals(new int[] {0, 1, 2, 3}, order(null, false));
        assertArrayEquals(new int[] {0, 1, 2, 3}, order(null, true));
    }

    @Test
    @DisplayName("Name sorts case-insensitively, and descending is the exact reverse")
    void nameSort() {
        assertArrayEquals(new int[] {3, 2, 1, 0}, order(Column.NAME, false)); // Birch nether spruce wood_oak
        assertArrayEquals(new int[] {0, 1, 2, 3}, order(Column.NAME, true));
    }

    @Test
    @DisplayName("Min sorts by the lower bound")
    void minSort() {
        assertArrayEquals(new int[] {2, 0, 3, 1}, order(Column.MIN, false)); // 0 131 161 436
    }

    @Test
    @DisplayName("an open-ended Max (∞) sorts last ascending and first descending")
    void maxSortPutsInfinityAtTheOpenEnd() {
        assertArrayEquals(new int[] {0, 3, 1, 2}, order(Column.MAX, false)); // 160 175 ∞ ∞ (server order kept)
        assertArrayEquals(new int[] {1, 2, 3, 0}, order(Column.MAX, true));
    }

    @Test
    @DisplayName("Blocks sorts by the strip's block count")
    void blocksSort() {
        assertArrayEquals(new int[] {3, 1, 0, 2}, order(Column.BLOCKS, false)); // 0 10 11 32
    }

    @Test
    @DisplayName("Phases sorts by how many phases are on, ties in server order")
    void phasesSortIsStable() {
        assertArrayEquals(new int[] {2, 0, 1, 3}, order(Column.PHASES, false)); // 2, then 4 4 4 as served
        assertArrayEquals(new int[] {0, 1, 3, 2}, order(Column.PHASES, true));  // 4 4 4 as served, then 2
    }

    @Test
    @DisplayName("a title click sorts ascending; the same title again flips to descending")
    void clickTogglesDirectionOnTheSameColumn() {
        assertNull(StagesSort.column());
        StagesSort.click(Column.MIN);
        assertEquals(Column.MIN, StagesSort.column());
        assertFalse(StagesSort.descending());
        StagesSort.click(Column.MIN);
        assertTrue(StagesSort.descending());
        StagesSort.click(Column.MIN);
        assertFalse(StagesSort.descending());
        // A different title starts ascending again.
        StagesSort.click(Column.MIN);
        StagesSort.click(Column.NAME);
        assertEquals(Column.NAME, StagesSort.column());
        assertFalse(StagesSort.descending());
    }

    @Test
    @DisplayName("clear returns to the server's order")
    void clearForgetsTheSort() {
        StagesSort.click(Column.MAX);
        StagesSort.click(Column.MAX);
        StagesSort.clear();
        assertNull(StagesSort.column());
        assertFalse(StagesSort.descending());
        assertArrayEquals(new int[] {0, 1, 2, 3}, StagesSort.order(STAGES, BLOCKS));
    }
}
