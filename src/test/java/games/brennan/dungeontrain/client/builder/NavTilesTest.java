package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NavTilesTest {

    /** Column sizes both pickers hand the layout, from a tiny GUI-scale-4 window to a big one. */
    private static final int[][] AREAS = {{60, 150}, {120, 200}, {200, 420}, {400, 900}};

    @Test
    @DisplayName("tiles come in nav order: Whole and Dimensional first, then the advanced three, Buildings last")
    void order() {
        List<NavTiles.Cell> cells = NavTiles.layout(0, 0, 200, 420);
        assertEquals(BuilderMode.NAV_ORDER, cells.stream().map(NavTiles.Cell::mode).toList());
    }

    @Test
    @DisplayName("the primary tiles are the tall ones; every advanced tile is shorter, and all share a height per tier")
    void twoTiers() {
        for (int[] a : AREAS) {
            List<NavTiles.Cell> cells = NavTiles.layout(0, 0, a[0], a[1]);
            int big = cells.get(0).h();
            int small = cells.get(2).h();
            for (NavTiles.Cell c : cells) {
                assertEquals(c.mode().primary() ? big : small, c.h(), c.mode() + " at " + a[0] + "x" + a[1]);
            }
            assertTrue(small < big, "advanced tiles are shorter at " + a[0] + "x" + a[1]);
            assertTrue(big <= a[0] * 9 / 16, "never taller than 16:9 at " + a[0] + "x" + a[1]);
        }
    }

    @Test
    @DisplayName("tiles span the column, stack without overlap, and stay inside the area")
    void fits() {
        for (int[] a : AREAS) {
            List<NavTiles.Cell> cells = NavTiles.layout(10, 20, a[0], a[1]);
            for (int i = 0; i < cells.size(); i++) {
                NavTiles.Cell c = cells.get(i);
                assertEquals(10, c.x());
                assertEquals(a[0], c.w());
                assertTrue(c.y() >= 20 && c.bottom() <= 20 + a[1], c + " leaves the area at " + a[0] + "x" + a[1]);
                if (i > 0) assertTrue(cells.get(i - 1).bottom() <= c.y(), "overlap at " + a[0] + "x" + a[1]);
            }
        }
    }
}
