package games.brennan.dungeontrain.client.credits;

import java.util.List;

/**
 * How much of a long credits list one card shows at a time.
 *
 * <p>A card starts on a <b>short</b> list — the builders' top five, the translators above 1% — and
 * a <i>See more</i> link swaps in the <b>full</b> list. Either list is cut into pages of
 * {@link #PAGE_SIZE}, with Prev / Next when there is more than one. Pure, so the arithmetic has a
 * test and the screen only draws.</p>
 *
 * @param expanded  whether <i>See more</i> has been pressed
 * @param page      zero-based page within whichever list is showing
 */
public record CreditsPaging(boolean expanded, int page) {

    public static final int PAGE_SIZE = 10;
    /** How many builders the collapsed card shows. */
    public static final int BUILDERS_COLLAPSED = 5;
    /** A translator's strongest language share must exceed this to make the collapsed card. */
    public static final double TRANSLATOR_MIN_SHARE = 0.01;

    public static final CreditsPaging START = new CreditsPaging(false, 0);

    /** What one card draws: the rows, and which controls go under them. */
    public record View<T>(List<T> rows, boolean seeMore, boolean seeLess, boolean prev, boolean next,
                          int page, int pages) {
        /** True when nothing of the full list is left to show: no See more ahead and no Next. */
        public boolean atEnd() {
            return !seeMore && !next;
        }
    }

    /** The visible slice of {@code shortList} / {@code fullList} for this state. */
    public <T> View<T> view(List<T> shortList, List<T> fullList) {
        List<T> source = expanded ? fullList : shortList;
        int pages = Math.max(1, (source.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.max(0, Math.min(page, pages - 1));
        int from = p * PAGE_SIZE;
        int to = Math.min(source.size(), from + PAGE_SIZE);
        List<T> rows = source.subList(from, to);
        boolean seeMore = !expanded && fullList.size() > shortList.size();
        // See less is the way back — offered whenever See more was the way here.
        return new View<>(rows, seeMore, expanded, p > 0, p < pages - 1, p, pages);
    }

    /** After <i>See more</i>: the full list, from its first page. */
    public CreditsPaging expand() {
        return new CreditsPaging(true, 0);
    }

    /** After <i>See less</i>: back to the short list, from its first page. */
    public CreditsPaging collapse() {
        return START;
    }

    public CreditsPaging nextPage() {
        return new CreditsPaging(expanded, page + 1);
    }

    public CreditsPaging prevPage() {
        return new CreditsPaging(expanded, Math.max(0, page - 1));
    }
}
