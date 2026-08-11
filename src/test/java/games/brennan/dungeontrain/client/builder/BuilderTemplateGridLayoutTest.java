package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guard for the Open screen's scrolling template grid.
 *
 * <p>At GUI scale 1 a 1080p window is ~1920×1080 GUI pixels; at scale 4 it is ~480×270. Unlike the
 * fixed 2×2 picker, this grid also has to cope with an unknown item count — nought, one, and more
 * than fits — so the scroll arithmetic is as much the subject here as the tiling.</p>
 */
final class BuilderTemplateGridLayoutTest {

    private static final int TOP = 120;
    private static final int BOTTOM = 400;

    private static final int[][] VIEWPORTS = {
            {1920, 1080}, {960, 540}, {854, 480}, {640, 360}, {480, 270}, {320, 240}
    };

    @Test
    @DisplayName("Cells never overlap, at any viewport size")
    void cellsDoNotOverlap() {
        for (int[] size : VIEWPORTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24);
            String where = " at " + size[0] + "x" + size[1];

            // Adjacent columns in the same row.
            assertTrue(layout.xFor(0) + layout.cellWidth() <= layout.xFor(1),
                    "columns overlap" + where);
            // The first cell of row 1 sits below the last cell of row 0.
            assertTrue(layout.yFor(0, 0) + layout.cellHeight() <= layout.yFor(layout.columns(), 0),
                    "rows overlap" + where);
        }
    }

    @Test
    @DisplayName("The grid stays on-screen and centred")
    void gridIsCentredAndOnScreen() {
        for (int[] size : VIEWPORTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24);
            int right = layout.xFor(layout.columns() - 1) + layout.cellWidth();
            String where = " at width " + size[0];

            assertTrue(layout.originX() >= 0, "grid runs off the left" + where);
            assertTrue(right <= size[0], "grid runs off the right" + where);
            // Equal slack either side, give or take integer rounding.
            assertTrue(Math.abs(layout.originX() - (size[0] - right)) <= 1,
                    "grid is not centred" + where);
        }
    }

    @Test
    @DisplayName("Cells keep a 16:9 aspect")
    void cellsAreSixteenByNine() {
        for (int[] size : VIEWPORTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 12);
            assertEquals(layout.cellWidth() * 9 / 16, layout.cellHeight(),
                    "cell is not 16:9 at width " + size[0]);
        }
    }

    @Test
    @DisplayName("Always three per row, at every viewport size")
    void alwaysThreeColumns() {
        for (int[] size : VIEWPORTS) {
            assertEquals(3, BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24).columns(),
                    "not 3 columns at width " + size[0]);
        }
        // The extremes too: an ultrawide and a viewport narrower than the grid's own minimum.
        assertEquals(3, BuilderTemplateGridLayout.of(3440, TOP, BOTTOM, 40).columns());
        assertEquals(3, BuilderTemplateGridLayout.of(200, TOP, BOTTOM, 40).columns());
    }

    @Test
    @DisplayName("Cell size is stable once the grid hits its width cap")
    void cellSizeIsStableOnWideViewports() {
        // The point of the fixed column count: the same template is the same size on any wide
        // window, and resizing doesn't reflow the library under the cursor.
        int at1920 = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 24).cellWidth();
        int at3440 = BuilderTemplateGridLayout.of(3440, TOP, BOTTOM, 24).cellWidth();
        assertEquals(at1920, at3440, "cell width should be capped, not grow with the window");
    }

    @Test
    @DisplayName("Content that fits does not scroll")
    void shortGridDoesNotScroll() {
        // One row on a wide viewport: nothing to scroll past.
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 2);
        assertEquals(0, layout.maxScroll());
        assertEquals(0, layout.clampScroll(500), "clamped to zero when there is no overflow");
    }

    @Test
    @DisplayName("An empty grid has no scroll and no hit targets")
    void emptyGridIsInert() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 0);
        assertEquals(0, layout.maxScroll());
        assertEquals(-1, layout.indexAt(500, TOP + 10, 0, 0));
    }

    @Test
    @DisplayName("Overflowing content scrolls, and only as far as its own end")
    void longGridScrollsWithinBounds() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60);
        assertTrue(layout.maxScroll() > 0, "60 items should overflow a 280px viewport");

        assertEquals(0, layout.clampScroll(-40), "cannot scroll above the first row");
        assertEquals(layout.maxScroll(), layout.clampScroll(layout.maxScroll() + 9999),
                "cannot scroll past the last row");

        // At full scroll the last cell's bottom is at or above the viewport floor — i.e. the grid
        // stops when its end is reached rather than sailing off into empty space.
        int lastBottom = layout.yFor(59, layout.maxScroll()) + layout.cellHeight();
        assertTrue(lastBottom <= layout.bottomY(), "scrolled past the end of the content");
        assertTrue(lastBottom >= layout.bottomY() - layout.cellHeight(),
                "stopped short of the end of the content");
    }

    @Test
    @DisplayName("Hit-testing ignores everything outside the viewport")
    void clicksOutsideTheViewportMiss() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(960, TOP, BOTTOM, 30);
        int insideX = layout.xFor(0) + 2;

        assertEquals(-1, layout.indexAt(insideX, TOP - 1, 0, 30),
                "a click above the grid must not land on a cell");
        assertEquals(-1, layout.indexAt(insideX, BOTTOM, 0, 30),
                "a click below the grid must not land on a cell");
        assertEquals(0, layout.indexAt(insideX, TOP + 2, 0, 30), "the first cell should be hit");
    }

    @Test
    @DisplayName("Scrolling moves which cell is under the cursor")
    void hitTestFollowsScroll() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60);
        int x = layout.xFor(0) + 2;
        int y = TOP + 2;

        assertEquals(0, layout.indexAt(x, y, 0, 60));
        // Scrolled by exactly one row, the same point is over the cell one row further down.
        int oneRow = layout.cellHeight() + BuilderTemplateGridLayout.GAP;
        assertEquals(layout.columns(), layout.indexAt(x, y, oneRow, 60));
    }

    @Test
    @DisplayName("The drill-in button sits inside its own cell, clear of the caption strip")
    void moreButtonIsInsideItsCell() {
        for (int[] size : VIEWPORTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24);
            String where = " at width " + size[0];
            int bx = layout.moreX(1);
            int by = layout.moreY(1, 0);
            int s = layout.moreSize();
            int cellX = layout.xFor(1);
            int cellY = layout.yFor(1, 0);

            assertTrue(bx >= cellX && bx + s <= cellX + layout.cellWidth(),
                    "button escapes its cell horizontally" + where);
            assertTrue(by >= cellY, "button escapes its cell upward" + where);
            // Above the caption strip, so it never sits on top of the template name.
            assertTrue(by + s <= cellY + layout.cellHeight() - BuilderTemplateGridLayout.LABEL_STRIP_H,
                    "button overlaps the caption strip" + where);
        }
    }

    @Test
    @DisplayName("The drill-in button answers for its own cell only, and follows scroll")
    void moreButtonHitTestIsExact() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(960, TOP, BOTTOM, 30);
        int inX = layout.moreX(0) + 1;
        int inY = layout.moreY(0, 0) + 1;

        assertTrue(layout.isOverMore(0, inX, inY, 0));
        assertFalse(layout.isOverMore(1, inX, inY, 0), "a neighbour must not claim this click");
        // The cell's own hit test still answers for that point — the caller resolves the overlap by
        // testing the button first; both being true here is the thing that makes the order matter.
        assertEquals(0, layout.indexAt(inX, inY, 0, 30));

        // Just outside on each axis.
        assertFalse(layout.isOverMore(0, layout.moreX(0) - 1, inY, 0));
        assertFalse(layout.isOverMore(0, inX, layout.moreY(0, 0) - 1, 0));
        assertFalse(layout.isOverMore(0, layout.moreX(0) + layout.moreSize(), inY, 0));

        // Scrolled away, the same screen point is no longer over cell 0's button.
        int oneRow = layout.cellHeight() + BuilderTemplateGridLayout.GAP;
        assertFalse(layout.isOverMore(0, inX, inY, oneRow));
        assertTrue(layout.isOverMore(layout.columns(), inX, inY, oneRow),
                "the cell scrolled into that slot should own the button");
    }

    @Test
    @DisplayName("The drill-in button is not clickable through the chrome above the grid")
    void moreButtonRespectsTheViewport() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60);
        // Scroll so a row's button would land above the viewport, then aim at where it would be.
        int scrolled = layout.maxScroll();
        int y = layout.moreY(0, scrolled);
        assertTrue(y < TOP, "expected cell 0's button to be scrolled above the viewport");
        assertFalse(layout.isOverMore(0, layout.moreX(0) + 1, y + 1, scrolled));
    }

    @Test
    @DisplayName("Visibility tracks the viewport as the grid scrolls")
    void visibilityFollowsScroll() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60);
        assertTrue(layout.isVisible(0, 0), "the first cell is visible unscrolled");
        assertFalse(layout.isVisible(59, 0), "the last of 60 cells is not visible unscrolled");
        assertTrue(layout.isVisible(59, layout.maxScroll()),
                "the last cell is visible once scrolled to the end");
        assertFalse(layout.isVisible(0, layout.maxScroll()),
                "the first cell has scrolled away at the end");
    }
}
