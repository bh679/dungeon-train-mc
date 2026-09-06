package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Square tiles in rows, as many columns as the viewport is wide, scrolling vertically.
 *
 * <p>Columns derive from the width and never from a setting, so the grid does not reflow when a
 * tile is selected. Scroll is applied arithmetically to {@code y}, never through the pose stack —
 * the tile painter scissors in absolute coordinates.</p>
 *
 * <p><b>Where the tiles start and where they can be seen are two different things.</b> A grid laid
 * out below another one — the sub-variant grid, which begins under the main grid's content — still
 * scrolls inside the same panel, so {@code y}/{@code height} say where its first row rests while
 * {@code viewportY}/{@code viewportHeight} say which window is on screen. Conflating the two is how
 * sub-variants stopped being drawn (and stopped being clickable) the moment they scrolled up past
 * the point their grid began at, while still sitting inside the panel.</p>
 *
 * @param x              left edge of the content
 * @param y              top edge of the content, where the first row rests unscrolled
 * @param width          content width
 * @param height         content height, for the scroll extent
 * @param tile           tile side, in pixels
 * @param gap            space between tiles
 * @param columns        tiles per row; at least one
 * @param viewportY      top edge of the window a tile has to be inside to be seen or clicked
 * @param viewportHeight that window's height
 */
public record TemplateTileGridLayout(int x, int y, int width, int height, int tile, int gap, int columns,
                                     int viewportY, int viewportHeight) {

    /** Returned by {@link #indexAt} when the point is on no tile. */
    public static final int NONE = -1;

    public static TemplateTileGridLayout of(int x, int y, int width, int height, int tile, int gap) {
        int columns = Math.max(1, (width + gap) / (tile + gap));
        return new TemplateTileGridLayout(x, y, width, height, tile, gap, columns, y, height);
    }

    /**
     * The same grid, seen through a different window — for a grid whose content starts below the
     * panel it scrolls inside.
     */
    public TemplateTileGridLayout withViewport(int windowY, int windowHeight) {
        return new TemplateTileGridLayout(x, y, width, height, tile, gap, columns, windowY, windowHeight);
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
        return Math.max(0, contentHeight(count) - viewportHeight);
    }

    public int clampScroll(int scroll, int count) {
        return Math.max(0, Math.min(scroll, maxScroll(count)));
    }

    /** Whether any part of tile {@code i} is inside the viewport. */
    public boolean isVisible(int i, int scroll) {
        int top = yFor(i, scroll);
        return top + tile > viewportY && top < viewportY + viewportHeight;
    }

    /** Which of {@code count} tiles is under the point, or {@link #NONE}; gaps miss. */
    public int indexAt(double px, double py, int scroll, int count) {
        if (px < x || px >= x + width || py < viewportY || py >= viewportY + viewportHeight) return NONE;
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
