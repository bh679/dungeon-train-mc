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
 * than exactly four, so whatever doesn't fit scrolls, and the player can say how many go in a row.
 * Cells keep the same 16:9 aspect as the picker tiles, because they hold the same kind of
 * picture.</p>
 *
 * <p>The grid spreads across the whole window bar its margins, so the cells grow with the screen
 * rather than sitting in a fixed block on a large monitor. Within that width, the tiles-per-row
 * count trades cell size against how much of a library is on screen at once.</p>
 */
record BuilderTemplateGridLayout(int columns, int cellWidth, int cellHeight,
                                 int originX, int topY, int bottomY, int gap, int maxScroll) {

    static final int GAP = 6;

    /**
     * Three per row until the player says otherwise.
     *
     * <p>Never responsive, which is the part worth keeping: a column count that moved with the
     * window would reflow the whole library under the cursor on every resize. The width responds to
     * the screen; the count only ever moves when asked — see {@link BuilderTilesPerRowButton}.</p>
     */
    static final int DEFAULT_COLUMNS = 3;

    /**
     * The range the player may ask for.
     *
     * <p>Two is as far down as this is worth going — one per row is a list, not a grid. Six is the
     * top for now rather than for a reason that survived: it was set when the grid was a fixed 480px
     * block, where a seventh column would have been caption strip with a sliver of picture above it.
     * Now that the grid fills the window there is room for more on a large monitor, and the real
     * floor is {@link #MIN_CELL_WIDTH} by way of {@link #maxColumnsFor}.</p>
     */
    static final int MIN_COLUMNS = 2;
    static final int MAX_COLUMNS = 6;

    private static final int MIN_CELL_WIDTH = 56;
    private static final int SIDE_MARGIN = 16;

    /**
     * How many columns this width can actually hold.
     *
     * <p>{@link #MIN_CELL_WIDTH} is a floor on the cell, not on the grid, so past a certain count
     * the cells stop shrinking and the <em>block</em> starts growing instead — at 320 GUI pixels
     * (scale 4 on a small window) six columns would each clamp up to 56px and lay out a 366px grid
     * on a 320px screen. So the count saturates at whatever fits rather than running off the edge,
     * and the button reads back the saturated value so it can't claim a column that isn't there.</p>
     *
     * <p>Measured against the raw screen width, not {@link #availableWidth}: the side margins are a
     * nicety and staying on screen is the invariant, so a count that only fits by eating into them
     * is still allowed. Taking the margins as hard here would refuse counts that visibly fit — three
     * columns of minimum-width cells are 180px, which sits inside a 200px screen with room to
     * spare.</p>
     */
    static int maxColumnsFor(int screenWidth) {
        // n columns of floored cells span n*MIN_CELL_WIDTH + (n-1)*GAP; solve that for <= screenWidth.
        int fits = (screenWidth + GAP) / (MIN_CELL_WIDTH + GAP);
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, fits));
    }

    /**
     * The width the grid may spread into: the whole window bar its margins.
     *
     * <p>Uncapped, so the grid fills the screen and the cells grow with the resolution. It used to
     * stop at 480px, which kept a template the same size on every machine — but that ceiling made
     * the tiles-per-row choice trade tile size against itself inside a fixed block, and left most of
     * a large monitor empty on a screen whose whole job is showing photographs. Filling the window
     * and then choosing how many go in a row is the trade worth offering.</p>
     */
    private static int availableWidth(int screenWidth) {
        return Math.max(0, screenWidth - 2 * SIDE_MARGIN);
    }

    /**
     * @param screenWidth screen width in GUI pixels
     * @param topY        first Y the grid may occupy (below the type controls)
     * @param bottomY     first Y the grid may NOT occupy (above the Back button)
     * @param itemCount   how many templates are being shown
     * @param columns     tiles per row the player has asked for; clamped to what the width holds
     */
    static BuilderTemplateGridLayout of(int screenWidth, int topY, int bottomY, int itemCount,
                                        int columns) {
        int available = availableWidth(screenWidth);
        int viewportHeight = Math.max(0, bottomY - topY);

        int clamped = Math.max(MIN_COLUMNS, Math.min(maxColumnsFor(screenWidth), columns));
        int cellWidth = Math.max(MIN_CELL_WIDTH, (available - (clamped - 1) * GAP) / clamped);
        // A wide, short window would otherwise work out a cell taller than the strip it scrolls in,
        // so no row would ever be fully visible and the grid would be all scrollbar and no picture.
        // Unreachable while the width was capped at 480; removing that cap is what opens it up.
        cellWidth = Math.min(cellWidth, Math.max(MIN_CELL_WIDTH, viewportHeight * 16 / 9));
        int cellHeight = Math.max(1, cellWidth * 9 / 16);

        int gridWidth = clamped * cellWidth + GAP * (clamped - 1);
        int originX = Math.max(0, (screenWidth - gridWidth) / 2);

        int rows = itemCount <= 0 ? 0 : (itemCount + clamped - 1) / clamped;
        int contentHeight = rows == 0 ? 0 : rows * cellHeight + (rows - 1) * GAP;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);

        return new BuilderTemplateGridLayout(clamped, cellWidth, cellHeight,
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
