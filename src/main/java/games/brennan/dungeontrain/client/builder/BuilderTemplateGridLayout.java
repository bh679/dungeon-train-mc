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
