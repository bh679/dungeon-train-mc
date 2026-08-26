package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a leaderboard book — one board, the top players, and a closing line telling the reader
 * where they stand.
 *
 * <h2>Shape</h2>
 * <p>Each entry takes {@value #LINES_PER_ENTRY} lines: the rank and name on one, the score
 * right-aligned on the next. That costs half the ranks a shared line would fit and buys back the
 * names — a name has the whole {@value BookColumnLayout#PAGE_WIDTH_PX}px to itself instead of
 * whatever a five-figure score left over, so the great majority arrive intact rather than cut to
 * eleven characters.</p>
 *
 * <p>{@value #PAGES} pages of {@value #LINES_PER_PAGE} lines. Page one spends two of its lines on a
 * heading and one on the blank beneath it, so it carries ranks 1–{@value #FIRST_PAGE_ROWS} and the
 * rest carry {@value #ROWS_PER_PAGE} each — {@value #MAX_ROWS} in all when the board is deep enough
 * to fill them. A shorter board simply ends early rather than padding out blank pages.</p>
 *
 * <h2>Localisation</h2>
 * <p>Pages are {@link Component}s so the heading and the closing line resolve on the reader's own
 * client. The rows themselves are literal — a name and a number need no translating, and keeping
 * them literal is also what keeps the score column aligned, since {@link BookColumnLayout} measures
 * the exact string it emits. The book's <em>title</em> is English: {@code WrittenBookContent} takes
 * a plain string, so there is nowhere for a translation to happen.</p>
 */
public final class LeaderboardBookFactory {

    /** Pages per book. */
    static final int PAGES = 8;

    /** Lines a written-book page fits at default font size. */
    static final int LINES_PER_PAGE = 13;

    /** Lines one ranked entry occupies: the name, then the score under it. */
    static final int LINES_PER_ENTRY = 2;

    /** Page one gives two lines to the heading and one to the blank beneath it. */
    static final int FIRST_PAGE_ROWS = (LINES_PER_PAGE - 3) / LINES_PER_ENTRY;

    /** Ranks every page after the first carries. The odd leftover line stays blank. */
    static final int ROWS_PER_PAGE = LINES_PER_PAGE / LINES_PER_ENTRY;

    /** Ranks a full book holds. */
    static final int MAX_ROWS = FIRST_PAGE_ROWS + (PAGES - 1) * ROWS_PER_PAGE;

    /** Credited author. Nobody wrote this; something counted it. */
    private static final String AUTHOR = "The Tallyman";

    private LeaderboardBookFactory() {}

    /**
     * Roll a leaderboard book for {@code reader} from whatever boards have been fetched, or empty
     * when none have. {@code seed} picks the board, so the same stack is always about the same
     * thing no matter who opens it or how often.
     */
    public static Optional<ItemStack> roll(long seed, UUID reader) {
        List<LeaderboardCategory> available = LeaderboardPool.populated();
        if (available.isEmpty()) return Optional.empty();
        LeaderboardCategory category = available.get((int) Math.floorMod(mix(seed), available.size()));
        return build(category, reader);
    }

    /** The book for one specific board, or empty when that board has no rows yet. */
    public static Optional<ItemStack> build(LeaderboardCategory category, UUID reader) {
        List<LeaderboardPool.Entry> entries = LeaderboardPool.board(category).entries();
        if (entries.isEmpty()) return Optional.empty();
        List<Component> pages = pages(category, entries,
            reader == null ? Optional.empty() : LeaderboardPool.standing(reader, category));
        return Optional.of(BookFactory.buildPlainBookComponents(category.title(), AUTHOR, pages));
    }

    /**
     * Lay the board out across pages. Package-private and free of item/NBT concerns so the page
     * shape can be tested without building a stack.
     */
    static List<Component> pages(LeaderboardCategory category,
                                 List<LeaderboardPool.Entry> entries,
                                 Optional<LeaderboardPool.Standing> mine) {
        // Two lines per rank, in order, so a page is just a slice of this list taken in pairs.
        List<String> rows = new ArrayList<>();
        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            LeaderboardPool.Entry e = entries.get(i);
            rows.add(BookColumnLayout.truncate((i + 1) + ". " + e.name(), BookColumnLayout.PAGE_WIDTH_PX));
            rows.add(BookColumnLayout.rightAlign(category.render(e.score())));
        }

        List<Component> pages = new ArrayList<>();
        int at = 0;
        for (int page = 0; page < PAGES && at < rows.size(); page++) {
            int room = (page == 0 ? FIRST_PAGE_ROWS : ROWS_PER_PAGE) * LINES_PER_ENTRY;
            int end = Math.min(rows.size(), at + room);
            Component body = Component.literal(String.join("\n", rows.subList(at, end)));
            pages.add(page == 0 ? heading(category).append("\n\n").append(body) : body);
            at = end;
        }
        if (pages.isEmpty()) pages.add(heading(category));

        // The reader's own standing closes the book. It goes on its own page rather than squeezed
        // under the last rows: a reader ranked 4,000th should not have to hunt for it at the bottom
        // of a column of strangers, and a full board leaves no room down there anyway.
        pages.add(mine
            .map(s -> s.isExact()
                ? Component.translatable(LeaderboardCategory.YOU_KEY,
                        Component.literal(Integer.toString(s.rank())),
                        Component.literal(category.render(s.score())))
                // On the board but past the relay's rank-scan horizon. Say that, rather than dress a
                // horizon up as a position.
                : Component.translatable(LeaderboardCategory.YOU_BEYOND_KEY,
                        Component.literal(category.render(s.score())),
                        Component.literal(Integer.toString(s.beyond()))))
            .orElseGet(() -> Component.translatable(LeaderboardCategory.YOU_UNRANKED_KEY)));
        return pages;
    }

    /**
     * The board's heading: the subject's own line, wrapped by the sentence that says which span of
     * play it covers. A pair of boards shares the subject line and differs only in the wrapper, so
     * the wording is written once. A board with no span ({@code Scope.NONE}) is just the line.
     */
    static MutableComponent heading(LeaderboardCategory category) {
        MutableComponent subject = Component.translatable(category.headerKey());
        String scopeKey = category.scopeKey();
        return scopeKey == null ? subject : Component.translatable(scopeKey, subject);
    }

    /** Splittable-mix, so neighbouring container slots do not all roll the same board. */
    private static long mix(long seed) {
        long state = seed ^ 0x4C45414445524245L; // "LEADERBE"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
