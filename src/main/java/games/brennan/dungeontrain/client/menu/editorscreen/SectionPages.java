package games.brennan.dungeontrain.client.menu.editorscreen;

import java.util.List;

/**
 * A pane's pages as its sections laid end to end — the one paging model behind the template
 * detail pane and the stage detail pane.
 *
 * <p>Each section is some rows cut into pages of {@code perPage}. A section shown
 * {@code always} keeps one page even with nothing in it, so the pager can say so (the stage
 * pane's sections); one that is not disappears when empty (the template pane's row, Blocks, Loot
 * and Submitted pages). Pure, so the split can be pinned: which section a page belongs to, and
 * which of that section's rows it shows.</p>
 *
 * @param <K> the section kind — each pane's own enum
 */
public record SectionPages<K>(List<Section<K>> sections) {

    /**
     * One section: how many rows it has and how many a page of it holds.
     *
     * @param always keep one page even with no rows
     */
    public record Section<K>(K kind, int rows, int perPage, boolean always) {
        public Section {
            rows = Math.max(0, rows);
            perPage = Math.max(1, perPage);
        }

        /** A section that always has a page, even empty. */
        public static <K> Section<K> always(K kind, int rows, int perPage) {
            return new Section<>(kind, rows, perPage, true);
        }

        /** A section that is there only while it has rows. */
        public static <K> Section<K> when(K kind, int rows, int perPage) {
            return new Section<>(kind, rows, perPage, false);
        }

        public int pages() {
            int full = (rows + perPage - 1) / perPage;
            return always ? Math.max(1, full) : full;
        }
    }

    public SectionPages {
        sections = List.copyOf(sections);
    }

    public int pageCount() {
        int n = 0;
        for (Section<K> s : sections) n += s.pages();
        return n;
    }

    /** True when the pager is drawn — only when there is a page to turn to. */
    public boolean hasPager() {
        return pageCount() > 1;
    }

    public int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** The section {@code page} falls in; the last one past the end. */
    public Section<K> sectionOf(int page) {
        int p = clamp(page);
        Section<K> last = sections.get(sections.size() - 1);
        for (Section<K> s : sections) {
            if (p < s.pages()) return s;
            p -= s.pages();
            if (s.pages() > 0) last = s;
        }
        return last;
    }

    public K kindOf(int page) {
        return sectionOf(page).kind();
    }

    /** The first row of its section shown on {@code page}. */
    public int firstRow(int page) {
        int p = clamp(page);
        for (Section<K> s : sections) {
            if (p < s.pages()) return p * s.perPage();
            p -= s.pages();
        }
        return 0;
    }

    /** One past the last row of its section shown on {@code page}. */
    public int endRow(int page) {
        Section<K> s = sectionOf(page);
        return Math.min(s.rows(), firstRow(page) + s.perPage());
    }

    /** How many pages the section of {@code kind} has; 0 when the pane has none. */
    public int pagesOf(K kind) {
        for (Section<K> s : sections) {
            if (s.kind() == kind) return s.pages();
        }
        return 0;
    }

    /** The first page of a section kind, or 0 when the pane has none. */
    public int firstPageOf(K kind) {
        int p = 0;
        for (Section<K> s : sections) {
            if (s.kind() == kind) return p;
            p += s.pages();
        }
        return 0;
    }

    /** True when {@code page} shows a section of {@code kind}. */
    public boolean isOf(int page, K kind) {
        return pagesOf(kind) > 0 && kindOf(page) == kind;
    }

    /** Which page of its section {@code page} is, from 0. */
    public int indexWithin(int page) {
        return clamp(page) - firstPageOf(kindOf(page));
    }
}
