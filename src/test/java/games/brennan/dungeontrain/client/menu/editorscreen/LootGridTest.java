package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Loot pages' icon grid: cells across and down, and enough pages that every item shows. */
class LootGridTest {

    private static final InventoryEditorLayout.Rect AREA =
        new InventoryEditorLayout.Rect(10, 20, 4 * LootGrid.CELL + 5, 2 * LootGrid.CELL + 3);

    @Test
    @DisplayName("everything fits on one page")
    void fits() {
        LootGrid g = LootGrid.of(AREA, 6);
        assertEquals(4, g.columns());
        assertEquals(2, g.rows());
        assertEquals(1, g.pageCount());
        assertEquals(6, g.shown());
        assertEquals(10 + LootGrid.CELL, g.cellX(5));
        assertEquals(20 + LootGrid.CELL, g.cellY(5));
    }

    @Test
    @DisplayName("too many for one page: more pages, the last holding the rest")
    void pages() {
        LootGrid g = LootGrid.of(AREA, 20);
        assertEquals(3, g.pageCount(), "8 + 8 + 4");
        assertEquals(8, g.page(0).shown());
        assertEquals(8, g.page(1).first());
        assertEquals(4, g.page(2).shown());
        assertEquals(16, g.page(9).first(), "clamped to the last page");
        LootGrid last = g.page(2);
        assertEquals(3, last.hit(last.cellX(3) + 2, last.cellY(3) + 2));
        assertEquals(-1, last.hit(last.cellX(4) + 2, last.cellY(4) + 2), "past the last item");
        assertEquals(-1, g.hit(0, 0));
    }
}
