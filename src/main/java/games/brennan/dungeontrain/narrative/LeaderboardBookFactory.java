package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a leaderboard book — one board, the top players, name left and score right, and a closing
 * line telling the reader where they stand.
 *
 * <h2>Shape</h2>
 * <p>{@value #PAGES} pages of {@value #LINES_PER_PAGE} lines. Page one spends two of its lines on a
 * heading, so it carries ranks 1–{@value #FIRST_PAGE_ROWS} and the rest carry
 * {@value #LINES_PER_PAGE} each — {@value #MAX_ROWS} in all when the board is deep enough to fill
 * them. A shorter board simply ends early rather than padding out blank pages.</p>
 *
 * <h2>Localisation</h2>
 * <p>Pages are {@link Component}s so the heading and the closing line resolve on the reader's own
 * client. The rows themselves are literal — a name and a number need no translating, and keeping
 * them literal is also what keeps the column aligned, since {@link BookColumnLayout} measures the
 * exact string it emits. The book's <em>title</em> is English: {@code WrittenBookContent} takes a
 * plain string, so there is nowhere for a translation to happen.</p>
 */
public final class LeaderboardBookFactory {

    /** Pages per book. */
    static final int PAGES = 8;

    /** Lines a written-book page fits at default font size. */
    static final int LINES_PER_PAGE = 13;

    /** Page one gives two lines to the heading and one to the blank beneath it. */
    static final int FIRST_PAGE_ROWS = LINES_PER_PAGE - 3;

    /** Ranks a full book holds. */
    static final int MAX_ROWS = FIRST_PAGE_ROWS + (PAGES - 1) * LINES_PER_PAGE;

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
        List<String> rows = new ArrayList<>();
        int shown = Math.min(entries.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            LeaderboardPool.Entry e = entries.get(i);
            rows.add(BookColumnLayout.row((i + 1) + ". " + e.name(), category.render(e.score())));
        }

        List<Component> pages = new ArrayList<>();
        int at = 0;
        for (int page = 0; page < PAGES && at < rows.size(); page++) {
            int room = page == 0 ? FIRST_PAGE_ROWS : LINES_PER_PAGE;
            int end = Math.min(rows.size(), at + room);
            Component body = Component.literal(String.join("\n", rows.subList(at, end)));
            pages.add(page == 0
                ? Component.translatable(category.headerKey()).append("\n\n").append(body)
                : body);
            at = end;
        }
        if (pages.isEmpty()) pages.add(Component.translatable(category.headerKey()));

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

    /** Splittable-mix, so neighbouring container slots do not all roll the same board. */
    private static long mix(long seed) {
        long state = seed ^ 0x4C45414445524245L; // "LEADERBE"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
