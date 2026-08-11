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

    /** Wide enough that a carriage reads at a glance; the column count follows from it. */
    private static final int TARGET_CELL_WIDTH = 150;
    private static final int MIN_CELL_WIDTH = 72;
    private static final int MAX_COLUMNS = 5;
    private static final int SIDE_MARGIN = 16;

    /**
     * @param screenWidth screen width in GUI pixels
     * @param topY        first Y the grid may occupy (below the type controls)
     * @param bottomY     first Y the grid may NOT occupy (above the Back button)
     * @param itemCount   how many templates are being shown
     */
    static BuilderTemplateGridLayout of(int screenWidth, int topY, int bottomY, int itemCount) {
        int available = Math.max(MIN_CELL_WIDTH, screenWidth - 2 * SIDE_MARGIN);

        // How many TARGET_CELL_WIDTH cells fit, clamped so an ultrawide doesn't produce a row of
        // twelve thumbnails and a phone-sized viewport still gets one readable column.
        int columns = Math.max(1, Math.min(MAX_COLUMNS, (available + GAP) / (TARGET_CELL_WIDTH + GAP)));
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
