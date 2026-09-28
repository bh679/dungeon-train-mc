package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Where a Loot page's item icons go: a grid of slot-sized cells filling the page under its header
 * line, most valuable item first. A build with more items than one page holds gets more Loot pages,
 * each a {@link #page} of this grid — every item is shown somewhere.
 *
 * <p>Pure, so it can be tested without a screen.</p>
 *
 * @param x       the grid's left edge
 * @param y       the grid's top edge
 * @param columns cells across
 * @param rows    cells down
 * @param items   how many items there are in all
 * @param first   the index of the first item on this page
 */
public record LootGrid(int x, int y, int columns, int rows, int items, int first) {

    /** A cell: a 16px item icon with a pixel of room each side. */
    public static final int CELL = 18;

    /** The first page of the grid for {@code items} items inside a rectangle. */
    public static LootGrid of(InventoryEditorLayout.Rect area, int items) {
        int columns = Math.max(0, area.w() / CELL);
        int rows = Math.max(0, area.h() / CELL);
        return new LootGrid(area.x(), area.y(), columns, rows, Math.max(0, items), 0);
    }

    /** How many items one page holds. */
    public int capacity() {
        return columns * rows;
    }

    /** How many pages the items need; at least one. */
    public int pageCount() {
        return capacity() == 0 ? 1 : Math.max(1, (items + capacity() - 1) / capacity());
    }

    /** Page {@code n} of the grid, from 0, clamped to the pages there are. */
    public LootGrid page(int n) {
        int p = Math.max(0, Math.min(n, pageCount() - 1));
        return new LootGrid(x, y, columns, rows, items, p * capacity());
    }

    /** How many items this page draws. */
    public int shown() {
        return Math.max(0, Math.min(capacity(), items - first));
    }

    /** The left edge of the {@code index}th cell on this page. */
    public int cellX(int index) {
        return x + (columns == 0 ? 0 : index % columns) * CELL;
    }

    /** The top edge of the {@code index}th cell on this page. */
    public int cellY(int index) {
        return y + (columns == 0 ? 0 : index / columns) * CELL;
    }

    /** The cell on this page under the point, counted from this page's first, or -1. */
    public int hit(double mx, double my) {
        if (columns == 0 || mx < x || my < y) return -1;
        int col = (int) ((mx - x) / CELL);
        int row = (int) ((my - y) / CELL);
        if (col >= columns || row >= rows) return -1;
        int index = row * columns + col;
        return index < shown() ? index : -1;
    }
}
