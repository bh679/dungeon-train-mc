package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Paging arithmetic for a grid of icons: {@code count} items laid {@code cols} across and
 * {@code rows} down per page. Pure, so the pane's page bounds can be pinned without a screen.
 *
 * <p>An empty grid is still one page, so the pager never shows {@code 1 / 0}; a grid that fits on
 * one page has no pager at all.</p>
 */
record IconGridPages(int count, int cols, int rows) {

    IconGridPages {
        count = Math.max(0, count);
        cols = Math.max(1, cols);
        rows = Math.max(1, rows);
    }

    /** How many icons a page holds. */
    int perPage() {
        return cols * rows;
    }

    int pageCount() {
        return Math.max(1, (count + perPage() - 1) / perPage());
    }

    boolean hasPager() {
        return pageCount() > 1;
    }

    /** {@code page} held within the pages that exist. */
    int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** The first item index on {@code page}. */
    int first(int page) {
        return clamp(page) * perPage();
    }

    /** One past the last item index on {@code page}. */
    int end(int page) {
        return Math.min(count, first(page) + perPage());
    }
}
