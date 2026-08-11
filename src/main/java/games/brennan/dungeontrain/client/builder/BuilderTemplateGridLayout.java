package games.brennan.dungeontrain.client.builder;

/**
 * Pure geometry for the Open screen's scrolling template grid.
 *
 * <p>The sibling of {@link BuilderGridLayout}, and split out for the same reason: this arithmetic
 * has to survive GUI scale 1 (a very wide, short viewport) through scale 4 (a small one) without
 * cells overlapping the controls above them, the Back button below them, or each other — and that
 * is cheap to test here and tedious to eyeball in-game at four scales.</p>
 *
 * <p>The difference from the picker's grid is that this one holds an unknown number of items rather
 * than exactly four, so the column count responds to the width instead of being fixed at two, and
 * whatever doesn't fit scrolls. Cells keep the same 16:9 aspect as the picker tiles, because they
 * hold the same kind of picture.</p>
 */
record BuilderTemplateGridLayout(int columns, int cellWidth, int cellHeight,
                                 int originX, int topY, int bottomY, int gap, int maxScroll) {

    static final int GAP = 6;

    /**
     * Three per row, always.
     *
     * <p>Fixed rather than responsive: the grid sits directly under the 200px-wide type controls, and
     * a column count that changed with the window changed the cell size with it — so the same
     * template was a different size on two machines, and resizing reflowed the whole library under
     * the cursor. Three keeps the cells large enough to read a carriage in and the block roughly the
     * width of the controls above it.</p>
     */
    static final int COLUMNS = 3;

    /** Ceiling on the grid's width so cells don't become billboards on an ultrawide. */
    private static final int MAX_GRID_WIDTH = 480;
    private static final int MIN_CELL_WIDTH = 56;
    private static final int SIDE_MARGIN = 16;

    /**
     * @param screenWidth screen width in GUI pixels
     * @param topY        first Y the grid may occupy (below the type controls)
     * @param bottomY     first Y the grid may NOT occupy (above the Back button)
     * @param itemCount   how many templates are being shown
     */
    static BuilderTemplateGridLayout of(int screenWidth, int topY, int bottomY, int itemCount) {
        int available = Math.min(screenWidth - 2 * SIDE_MARGIN, MAX_GRID_WIDTH);

        int columns = COLUMNS;
        int cellWidth = Math.max(MIN_CELL_WIDTH, (available - (columns - 1) * GAP) / columns);
        int cellHeight = Math.max(1, cellWidth * 9 / 16);

        int gridWidth = columns * cellWidth + GAP * (columns - 1);
        int originX = Math.max(0, (screenWidth - gridWidth) / 2);

        int rows = itemCount <= 0 ? 0 : (itemCount + columns - 1) / columns;
        int contentHeight = rows == 0 ? 0 : rows * cellHeight + (rows - 1) * GAP;
        int viewportHeight = Math.max(0, bottomY - topY);
        int maxScroll = Math.max(0, contentHeight - viewportHeight);

        return new BuilderTemplateGridLayout(columns, cellWidth, cellHeight,
                originX, topY, bottomY, GAP, maxScroll);
    }

    /** Height of the dark caption strip along the bottom of every cell. */
    static final int LABEL_STRIP_H = 14;

    /** Padding between the drill-in button and the cell edges it sits in the corner of. */
    private static final int MORE_INSET = 3;
    private static final int MORE_MIN_SIZE = 8;

    int xFor(int index) {
        return originX + (index % columns) * (cellWidth + gap);
    }

    /** Y of {@code index}'s cell at the given scroll offset. May fall outside the viewport. */
    int yFor(int index, int scrollY) {
        return topY + (index / columns) * (cellHeight + gap) - scrollY;
    }

    /** Clamp a candidate scroll offset into range — the grid never scrolls past its own content. */
    int clampScroll(int scrollY) {
        return Math.max(0, Math.min(scrollY, maxScroll));
    }

    /** Whether {@code index}'s cell has any pixels inside the viewport at this scroll offset. */
    boolean isVisible(int index, int scrollY) {
        int y = yFor(index, scrollY);
        return y + cellHeight > topY && y < bottomY;
    }

    // ---- the drill-in button ----
    //
    // A cell that stands for a group of sub-variants carries a small button in the bottom-right of
    // its picture, just above the caption strip. It is a second hit target inside the cell, so its
    // geometry lives here beside the cell's own rather than being re-derived by the renderer and the
    // click handler separately — two copies of this arithmetic drifting apart means a button that
    // draws in one place and responds in another.

    int moreSize() {
        return Math.max(MORE_MIN_SIZE, cellHeight / 6);
    }

    int moreX(int index) {
        return xFor(index) + cellWidth - moreSize() - MORE_INSET;
    }

    int moreY(int index, int scrollY) {
        return yFor(index, scrollY) + cellHeight - LABEL_STRIP_H - moreSize() - MORE_INSET;
    }

    /**
     * Whether the point is on {@code index}'s drill-in button.
     *
     * <p>Viewport-checked like {@link #indexAt}, so a scrolled-away button can't be clicked through
     * the chrome above or below the grid.</p>
     */
    boolean isOverMore(int index, double mouseX, double mouseY, int scrollY) {
        if (mouseY < topY || mouseY >= bottomY) {
            return false;
        }
        int bx = moreX(index);
        int by = moreY(index, scrollY);
        int size = moreSize();
        return mouseX >= bx && mouseX < bx + size && mouseY >= by && mouseY < by + size;
    }

    /**
     * Which cell contains {@code (mouseX, mouseY)}, or -1.
     *
     * <p>Tests the viewport before the cell so a click on the chrome above or below a scrolled grid
     * can't land on the row that happens to be arithmetically under the cursor.</p>
     */
    int indexAt(double mouseX, double mouseY, int scrollY, int itemCount) {
        if (mouseY < topY || mouseY >= bottomY) {
            return -1;
        }
        for (int i = 0; i < itemCount; i++) {
            int x = xFor(i);
            int y = yFor(i, scrollY);
            if (mouseX >= x && mouseX < x + cellWidth && mouseY >= y && mouseY < y + cellHeight) {
                return i;
            }
        }
        return -1;
    }
}
