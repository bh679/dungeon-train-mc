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
 *
 * <p>Since the column count became the player's to set, every one of those invariants has to hold
 * across the whole range rather than at three, which is what {@link #COLUMN_COUNTS} is for. The
 * pairing that matters most is the narrowest viewport against the highest count: that is where the
 * cell hits its own minimum width and the grid would start growing past the screen if the column
 * clamp weren't there.</p>
 */
final class BuilderTemplateGridLayoutTest {

    private static final int TOP = 120;
    private static final int BOTTOM = 400;

    private static final int[][] VIEWPORTS = {
            {1920, 1080}, {960, 540}, {854, 480}, {640, 360}, {480, 270}, {320, 240}
    };

    /** The whole range the tiles-per-row button offers. */
    private static final int[] COLUMN_COUNTS = {2, 3, 4, 5, 6};

    private static final int COLUMNS = BuilderTemplateGridLayout.DEFAULT_COLUMNS;

    @Test
    @DisplayName("Cells never overlap, at any viewport size or column count")
    void cellsDoNotOverlap() {
        for (int[] size : VIEWPORTS) {
            for (int columns : COLUMN_COUNTS) {
                BuilderTemplateGridLayout layout =
                        BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24, columns);
                String where = " at " + size[0] + "x" + size[1] + ", " + columns + " per row";

                // Adjacent columns in the same row.
                assertTrue(layout.xFor(0) + layout.cellWidth() <= layout.xFor(1),
                        "columns overlap" + where);
                // The first cell of row 1 sits below the last cell of row 0.
                assertTrue(layout.yFor(0, 0) + layout.cellHeight() <= layout.yFor(layout.columns(), 0),
                        "rows overlap" + where);
            }
        }
    }

    @Test
    @DisplayName("The grid stays on-screen and centred, at any column count")
    void gridIsCentredAndOnScreen() {
        for (int[] size : VIEWPORTS) {
            for (int columns : COLUMN_COUNTS) {
                BuilderTemplateGridLayout layout =
                        BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24, columns);
                int right = layout.xFor(layout.columns() - 1) + layout.cellWidth();
                String where = " at width " + size[0] + ", " + columns + " per row";

                assertTrue(layout.originX() >= 0, "grid runs off the left" + where);
                assertTrue(right <= size[0], "grid runs off the right" + where);
                // Equal slack either side, give or take integer rounding.
                assertTrue(Math.abs(layout.originX() - (size[0] - right)) <= 1,
                        "grid is not centred" + where);
            }
        }
    }

    @Test
    @DisplayName("Cells keep a 16:9 aspect, at any column count")
    void cellsAreSixteenByNine() {
        for (int[] size : VIEWPORTS) {
            for (int columns : COLUMN_COUNTS) {
                BuilderTemplateGridLayout layout =
                        BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 12, columns);
                assertEquals(layout.cellWidth() * 9 / 16, layout.cellHeight(),
                        "cell is not 16:9 at width " + size[0] + ", " + columns + " per row");
            }
        }
    }

    @Test
    @DisplayName("Three per row is what a player who never touches the button gets")
    void defaultsToThreeColumns() {
        assertEquals(3, COLUMNS, "the default the config ships must stay three");
        for (int[] size : VIEWPORTS) {
            assertEquals(3, BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24, COLUMNS).columns(),
                    "not 3 columns at width " + size[0]);
        }
        // The extremes too: an ultrawide and a viewport narrower than the grid's own minimum.
        assertEquals(3, BuilderTemplateGridLayout.of(3440, TOP, BOTTOM, 40, COLUMNS).columns());
        assertEquals(3, BuilderTemplateGridLayout.of(200, TOP, BOTTOM, 40, COLUMNS).columns());
    }

    @Test
    @DisplayName("The grid fills the window it is given")
    void gridSpansTheAvailableWidth() {
        // A tall viewport, so the cell-height cap doesn't bite and width is the only constraint.
        int bottom = TOP + 2000;
        for (int[] size : VIEWPORTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(size[0], TOP, bottom, 24, COLUMNS);
            int right = layout.xFor(layout.columns() - 1) + layout.cellWidth();
            int spanned = right - layout.originX();
            // Within a column's worth of integer truncation of the whole usable width.
            assertTrue(spanned >= size[0] - 32 - layout.columns(),
                    "the grid leaves " + (size[0] - spanned) + "px unused at width " + size[0]);
            assertTrue(spanned <= size[0], "the grid overruns the window at width " + size[0]);
        }
    }

    @Test
    @DisplayName("More per row means smaller tiles, within the same full-width grid")
    void higherCountsShrinkCells() {
        int bottom = TOP + 2000;
        int previousWidth = Integer.MAX_VALUE;
        int blockWidth = -1;
        for (int columns : COLUMN_COUNTS) {
            BuilderTemplateGridLayout layout =
                    BuilderTemplateGridLayout.of(1920, TOP, bottom, 24, columns);
            assertEquals(columns, layout.columns(), "1920px holds every count in the range");

            assertTrue(layout.cellWidth() < previousWidth,
                    "cells should shrink as the count rises, at " + columns + " per row");
            previousWidth = layout.cellWidth();

            // The count divides the window; it does not change how much of it the grid uses.
            int right = layout.xFor(layout.columns() - 1) + layout.cellWidth();
            int width = right - layout.originX();
            if (blockWidth < 0) {
                blockWidth = width;
            } else {
                assertTrue(Math.abs(width - blockWidth) <= COLUMN_COUNTS.length,
                        "the grid's span moved at " + columns + " per row: " + width
                                + " vs " + blockWidth);
            }
        }
    }

    @Test
    @DisplayName("A cell never grows taller than the strip it scrolls in")
    void cellsNeverExceedTheViewport() {
        // The case removing the width cap opened up: a wide, short window. Without the height cap
        // the cell works out taller than the viewport and no row is ever fully visible.
        for (int height : new int[]{40, 80, 160, 280, 600}) {
            for (int columns : COLUMN_COUNTS) {
                BuilderTemplateGridLayout layout =
                        BuilderTemplateGridLayout.of(3440, TOP, TOP + height, 24, columns);
                assertTrue(layout.cellHeight() <= height || layout.cellWidth() == 56,
                        "cell of " + layout.cellHeight() + "px in a " + height
                                + "px viewport at " + columns + " per row");
            }
        }
    }

    @Test
    @DisplayName("A count too high for the window saturates instead of running off it")
    void narrowViewportsCapTheColumnCount() {
        // 320 GUI pixels is scale 4 on a small window. Five columns of minimum-width cells span
        // 304px and fit; six would span 366px and hang off both edges.
        assertEquals(5, BuilderTemplateGridLayout.maxColumnsFor(320));
        BuilderTemplateGridLayout layout =
                BuilderTemplateGridLayout.of(320, TOP, BOTTOM, 24, 6);
        assertEquals(5, layout.columns(), "the count should saturate at what fits");
        assertTrue(layout.xFor(layout.columns() - 1) + layout.cellWidth() <= 320,
                "the saturated grid must still fit on-screen");

        // A roomy window holds the whole range, so nothing is clamped away there.
        assertEquals(BuilderTemplateGridLayout.MAX_COLUMNS,
                BuilderTemplateGridLayout.maxColumnsFor(1920));
        assertEquals(6, BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 24, 6).columns());
    }

    @Test
    @DisplayName("A count outside the range is clamped into it")
    void countsAreClampedToTheRange() {
        assertEquals(BuilderTemplateGridLayout.MIN_COLUMNS,
                BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 24, 0).columns(),
                "a zero column count would divide by zero if it got through");
        assertEquals(BuilderTemplateGridLayout.MIN_COLUMNS,
                BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 24, -3).columns());
        assertEquals(BuilderTemplateGridLayout.MAX_COLUMNS,
                BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 24, 99).columns());
        // Even on a viewport narrower than the grid's own minimum, there is always a usable count.
        assertTrue(BuilderTemplateGridLayout.maxColumnsFor(100) >= BuilderTemplateGridLayout.MIN_COLUMNS);
    }

    @Test
    @DisplayName("Cells grow with the window rather than stopping at a cap")
    void cellSizeGrowsWithTheWindow() {
        // A tall viewport so the height cap doesn't mask the width behaviour under test.
        int bottom = TOP + 2000;
        int at640 = BuilderTemplateGridLayout.of(640, TOP, bottom, 24, COLUMNS).cellWidth();
        int at1920 = BuilderTemplateGridLayout.of(1920, TOP, bottom, 24, COLUMNS).cellWidth();
        int at3440 = BuilderTemplateGridLayout.of(3440, TOP, bottom, 24, COLUMNS).cellWidth();
        assertTrue(at640 < at1920, "cells should grow from 640 to 1920");
        assertTrue(at1920 < at3440, "cells should keep growing on an ultrawide, not cap out");
    }

    @Test
    @DisplayName("Content that fits does not scroll")
    void shortGridDoesNotScroll() {
        // One row on a wide viewport: nothing to scroll past.
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 2, COLUMNS);
        assertEquals(0, layout.maxScroll());
        assertEquals(0, layout.clampScroll(500), "clamped to zero when there is no overflow");
    }

    @Test
    @DisplayName("An empty grid has no scroll and no hit targets")
    void emptyGridIsInert() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 0, COLUMNS);
        assertEquals(0, layout.maxScroll());
        assertEquals(-1, layout.indexAt(500, TOP + 10, 0, 0));
    }

    @Test
    @DisplayName("Overflowing content scrolls, and only as far as its own end")
    void longGridScrollsWithinBounds() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60, COLUMNS);
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
    @DisplayName("A higher count packs the same list into fewer rows to scroll")
    void higherCountsShortenTheScroll() {
        BuilderTemplateGridLayout few = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 60, 2);
        BuilderTemplateGridLayout many = BuilderTemplateGridLayout.of(1920, TOP, BOTTOM, 60, 6);
        assertTrue(many.maxScroll() < few.maxScroll(),
                "six per row should leave less to scroll through than two");
    }

    @Test
    @DisplayName("Hit-testing ignores everything outside the viewport")
    void clicksOutsideTheViewportMiss() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(960, TOP, BOTTOM, 30, COLUMNS);
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
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60, COLUMNS);
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
            for (int columns : COLUMN_COUNTS) {
                BuilderTemplateGridLayout layout =
                        BuilderTemplateGridLayout.of(size[0], TOP, BOTTOM, 24, columns);
                String where = " at width " + size[0] + ", " + columns + " per row";
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
    }

    @Test
    @DisplayName("The drill-in button answers for its own cell only, and follows scroll")
    void moreButtonHitTestIsExact() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(960, TOP, BOTTOM, 30, COLUMNS);
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
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60, COLUMNS);
        // Scroll so a row's button would land above the viewport, then aim at where it would be.
        int scrolled = layout.maxScroll();
        int y = layout.moreY(0, scrolled);
        assertTrue(y < TOP, "expected cell 0's button to be scrolled above the viewport");
        assertFalse(layout.isOverMore(0, layout.moreX(0) + 1, y + 1, scrolled));
    }

    @Test
    @DisplayName("Visibility tracks the viewport as the grid scrolls")
    void visibilityFollowsScroll() {
        BuilderTemplateGridLayout layout = BuilderTemplateGridLayout.of(640, TOP, BOTTOM, 60, COLUMNS);
        assertTrue(layout.isVisible(0, 0), "the first cell is visible unscrolled");
        assertFalse(layout.isVisible(59, 0), "the last of 60 cells is not visible unscrolled");
        assertTrue(layout.isVisible(59, layout.maxScroll()),
                "the last cell is visible once scrolled to the end");
        assertFalse(layout.isVisible(0, layout.maxScroll()),
                "the first cell has scrolled away at the end");
    }
}
