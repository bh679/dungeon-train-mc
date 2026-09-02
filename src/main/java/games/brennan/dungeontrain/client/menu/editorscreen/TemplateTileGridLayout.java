package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Square tiles in rows, as many columns as the viewport is wide, scrolling vertically.
 *
 * <p>Columns derive from the width and never from a setting, so the grid does not reflow when a
 * tile is selected. Scroll is applied arithmetically to {@code y}, never through the pose stack —
 * the tile painter scissors in absolute coordinates.</p>
 *
 * @param x       left edge of the viewport
 * @param y       top edge of the viewport
 * @param width   viewport width
 * @param height  viewport height
 * @param tile    tile side, in pixels
 * @param gap     space between tiles
 * @param columns tiles per row; at least one
 */
public record TemplateTileGridLayout(int x, int y, int width, int height, int tile, int gap, int columns) {

    /** Returned by {@link #indexAt} when the point is on no tile. */
    public static final int NONE = -1;

    public static TemplateTileGridLayout of(int x, int y, int width, int height, int tile, int gap) {
        int columns = Math.max(1, (width + gap) / (tile + gap));
        return new TemplateTileGridLayout(x, y, width, height, tile, gap, columns);
    }

    public int stride() {
        return tile + gap;
    }

    /** Left edge of tile {@code i}. */
    public int xFor(int i) {
        return x + (i % columns) * stride();
    }

    /** Top edge of tile {@code i}, {@code scroll} pixels up from where it would rest. */
    public int yFor(int i, int scroll) {
        return y + (i / columns) * stride() - scroll;
    }

    /** How many rows {@code count} tiles fill. */
    public int rows(int count) {
        return count <= 0 ? 0 : (count + columns - 1) / columns;
    }

    /** The height all {@code count} tiles occupy, gap between rows included, no trailing gap. */
    public int contentHeight(int count) {
        int rows = rows(count);
        return rows == 0 ? 0 : rows * stride() - gap;
    }

    /** The most the grid can scroll and still show the last row. */
    public int maxScroll(int count) {
        return Math.max(0, contentHeight(count) - height);
    }

    public int clampScroll(int scroll, int count) {
        return Math.max(0, Math.min(scroll, maxScroll(count)));
    }

    /** Whether any part of tile {@code i} is inside the viewport. */
    public boolean isVisible(int i, int scroll) {
        int top = yFor(i, scroll);
        return top + tile > y && top < y + height;
    }

    /** Which of {@code count} tiles is under the point, or {@link #NONE}; gaps miss. */
    public int indexAt(double px, double py, int scroll, int count) {
        if (px < x || px >= x + width || py < y || py >= y + height) return NONE;
        int col = (int) ((px - x) / stride());
        if (col >= columns) return NONE;
        if ((px - x) - col * stride() >= tile) return NONE;
        double localY = py - y + scroll;
        if (localY < 0) return NONE;
        int row = (int) (localY / stride());
        if (localY - row * stride() >= tile) return NONE;
        int i = row * columns + col;
        return i < count ? i : NONE;
    }
}
