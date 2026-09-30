package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * The body cut into pages.
 *
 * <p>The first page is always the model and its data sheet — path, size, blocks, weight, stage,
 * levels. The room's rows come after, as many per page as the whole body holds less the pager's
 * slot, so a long list of walls sub-options takes over the space the model had rather than
 * squeezing under its sheet. The Blocks pages — every block the build uses — follow the rows,
 * the Loot pages, when the build has loot, come after them, and the
 * Submitted answers page, when its author answered anything on submitting it, comes last. With
 * none of those there is one page and no pager.</p>
 *
 * <p>Pure, so it can be tested without a screen.</p>
 *
 * @param count   how many rows there are
 * @param perPage rows on each row page — the body's slots, less the pager's
 * @param blockPages Blocks pages, between the rows and the Loot pages
 */
public record DetailPages(int count, int perPage, int blockPages, int lootPages, int submitPages) {
    public static final DetailPages NONE = new DetailPages(0, 0, 0, 0, 0);

    public static DetailPages of(int count, int bodySlots) {
        return of(count, bodySlots, 0);
    }

    /** As {@link #of(int, int)}, with {@code lootPages} Loot pages after the rows. */
    public static DetailPages of(int count, int bodySlots, int lootPages) {
        return of(count, bodySlots, lootPages, 0);
    }

    /** As above, with {@code submitPages} Submitted answers pages (0 or 1) after the Loot pages. */
    public static DetailPages of(int count, int bodySlots, int lootPages, int submitPages) {
        return new DetailPages(Math.max(0, count), Math.max(0, bodySlots - 1), 0, Math.max(0, lootPages),
            Math.max(0, Math.min(1, submitPages)));
    }

    /** These pages with {@code n} Blocks pages between the rows and the Loot pages. */
    public DetailPages withBlockPages(int n) {
        return new DetailPages(count, perPage, Math.max(0, n), lootPages, submitPages);
    }

    /** True when there is anything past the model page. */
    public boolean paged() {
        return hasRows() || blockPages > 0 || lootPages > 0 || submitPages > 0;
    }

    private boolean hasRows() {
        return count > 0 && perPage > 0;
    }

    /** How many row pages follow the model page. */
    public int rowPages() {
        return hasRows() ? (count + perPage - 1) / perPage : 0;
    }

    /** The first Blocks page: straight after the last row page. */
    public int firstBlockPage() {
        return 1 + rowPages();
    }

    /** The first Loot page: straight after the last Blocks page. */
    public int firstLootPage() {
        return firstBlockPage() + blockPages;
    }

    /** The Submitted answers page: straight after the last Loot page. */
    public int firstSubmitPage() {
        return firstLootPage() + lootPages;
    }

    /** The model page, the row pages, the Loot pages, then the Submitted answers page; at least one. */
    public int pageCount() {
        return 1 + rowPages() + blockPages + lootPages + submitPages;
    }

    public int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    public boolean isLootPage(int page) {
        int p = clamp(page);
        return lootPages > 0 && p >= firstLootPage() && p < firstSubmitPage();
    }

    public boolean isBlockPage(int page) {
        int p = clamp(page);
        return blockPages > 0 && p >= firstBlockPage() && p < firstLootPage();
    }

    /** Which Blocks page {@code page} is, from 0; meaningless off one. */
    public int blockIndex(int page) {
        return Math.max(0, clamp(page) - firstBlockPage());
    }

    public boolean isSubmitPage(int page) {
        return submitPages > 0 && clamp(page) >= firstSubmitPage();
    }

    /** Which Loot page {@code page} is, from 0; meaningless off one. */
    public int lootIndex(int page) {
        return Math.max(0, clamp(page) - firstLootPage());
    }

    /** True when {@code page} is one of rows rather than the model or a Loot page. */
    public boolean isRowPage(int page) {
        int p = clamp(page);
        return p >= 1 && p < firstBlockPage();
    }

    /** The first row index on {@code page}; meaningless off a row page. */
    public int first(int page) {
        return Math.max(0, clamp(page) - 1) * perPage;
    }

    /** One past the last row index on {@code page}. */
    public int end(int page) {
        return isRowPage(page) ? Math.min(count, first(page) + perPage) : 0;
    }

    /** True when the pager is drawn — only when there is a page to turn to. */
    public boolean hasPager() {
        return paged();
    }
}
