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

    /**
     * One loot book in this many is about a RETIRED board (a past game version's or a past year's
     * one-life board) when any are on hand. The current boards stay the common find: a retired list
     * is a curiosity, and a chest that mostly turned up last year's numbers would read as stale.
     */
    static final int RETIRED_ONE_IN = 4;

    /** The author line's cap — the era label rides on the cover next to the name. */
    private static final int MAX_AUTHOR_CHARS = 48;

    private LeaderboardBookFactory() {}

    /**
     * Roll a leaderboard book for {@code reader} from whatever boards have been fetched, or empty
     * when none have. {@code seed} picks the board, so the same stack is always about the same
     * thing no matter who opens it or how often.
     */
    public static Optional<ItemStack> roll(long seed, UUID reader) {
        List<LeaderboardCategory> available = LeaderboardPool.populated();
        List<LeaderboardPool.BoardKey> retired = LeaderboardPool.retiredBooksWanted()
            ? LeaderboardPool.populatedRetired() : List.of();
        Optional<LeaderboardPool.BoardKey> pick = pick(mix(seed), available, retired);
        return pick.flatMap(k -> build(k.category(), k.era(), reader));
    }

    /**
     * Which board a mixed seed lands on: a retired one every {@link #RETIRED_ONE_IN} rolls when any
     * are on hand (always, when only retired boards are), otherwise a current one. Pure, so the
     * share is testable. Two different bit ranges decide "retired?" and "which", so the two choices
     * are independent across neighbouring seeds.
     */
    static Optional<LeaderboardPool.BoardKey> pick(long mixed, List<LeaderboardCategory> current,
                                                   List<LeaderboardPool.BoardKey> retired) {
        boolean wantRetired = !retired.isEmpty()
            && (current.isEmpty() || Math.floorMod(mixed >>> 40, RETIRED_ONE_IN) == 0);
        if (wantRetired) return Optional.of(retired.get((int) Math.floorMod(mixed, retired.size())));
        if (current.isEmpty()) return Optional.empty();
        return Optional.of(new LeaderboardPool.BoardKey(
            current.get((int) Math.floorMod(mixed, current.size())), LeaderboardPool.CURRENT));
    }

    /**
     * The book for one specific board, or empty when that board has no rows yet.
     *
     * <p>The stack leaves here stamped {@link LeaderboardBookTag} — the identity every consumer of a
     * finished board relies on, and half of the burn gate. It is stamped HERE rather than at each
     * call site so the two producers (a chest placeholder resolving, a Stat Room shelf being stocked)
     * cannot drift apart into one kind of board that burns and one that does not.</p>
     */
    public static Optional<ItemStack> build(LeaderboardCategory category, UUID reader) {
        return build(category, LeaderboardPool.CURRENT, reader);
    }

    /**
     * The book for one board in one era ({@link LeaderboardPool#CURRENT} for the live board), or
     * empty when that board has no rows yet. A retired era's book keeps the same cover title — the
     * 32-character title has no room for an era — and says which era it is on page one's heading
     * and in the author line.
     */
    public static Optional<ItemStack> build(LeaderboardCategory category, String era, UUID reader) {
        List<LeaderboardPool.Entry> entries = LeaderboardPool.board(category, era).entries();
        if (entries.isEmpty()) return Optional.empty();
        List<Component> pages = pages(category, era, entries,
            reader == null ? Optional.empty() : LeaderboardPool.standing(reader, category, era));
        ItemStack stack = BookFactory.buildPlainBookComponents(category.title(), author(era), pages);
        LeaderboardBookTag.stamp(stack);
        return Optional.of(stack);
    }

    /**
     * "The Tallyman" for a current board. For a retired one the era rides on the cover after the
     * name, by VERSION rather than by the relay's free-text label: "The Tallyman, v0.1013",
     * "The Tallyman, before v0.1013" for the founding era, "The Tallyman, 2025" for a year.
     */
    static String author(String era) {
        if (era == null || era.isEmpty()) return AUTHOR;
        String out = AUTHOR + ", " + eraShortName(era);
        return out.length() > MAX_AUTHOR_CHARS ? out.substring(0, MAX_AUTHOR_CHARS) : out;
    }

    /** The era as a few plain characters for the cover: a year, a version, or the id when unknown. */
    static String eraShortName(String era) {
        Optional<LeaderboardPool.Era> known = LeaderboardPool.era(era);
        if (known.isEmpty()) return era;
        LeaderboardPool.Era e = known.get();
        if (e.isYear()) return e.label();
        if (e.isFounding()) {
            return LeaderboardPool.nextVersionFloor(era).map(v -> "before v" + v).orElse(e.label());
        }
        return e.minVersion().isEmpty() ? e.label() : "v" + e.minVersion();
    }

    /**
     * Lay the board out across pages. Package-private and free of item/NBT concerns so the page
     * shape can be tested without building a stack.
     */
    static List<Component> pages(LeaderboardCategory category,
                                 List<LeaderboardPool.Entry> entries,
                                 Optional<LeaderboardPool.Standing> mine) {
        return pages(category, LeaderboardPool.CURRENT, entries, mine);
    }

    static List<Component> pages(LeaderboardCategory category, String era,
                                 List<LeaderboardPool.Entry> entries,
                                 Optional<LeaderboardPool.Standing> mine) {
        boolean retired = era != null && !era.isEmpty();
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
            pages.add(page == 0 ? heading(category, era).append("\n\n").append(body) : body);
            at = end;
        }
        if (pages.isEmpty()) pages.add(heading(category, era));

        // The reader's own standing closes the book. It goes on its own page rather than squeezed
        // under the last rows: a reader ranked 4,000th should not have to hunt for it at the bottom
        // of a column of strangers, and a full board leaves no room down there anyway.
        MutableComponent closing = mine
            .map(s -> s.isExact()
                ? Component.translatable(LeaderboardCategory.YOU_KEY,
                        Component.literal(Integer.toString(s.rank())),
                        Component.literal(category.render(s.score())))
                // On the board but past the relay's rank-scan horizon. Say that, rather than dress a
                // horizon up as a position.
                : Component.translatable(LeaderboardCategory.YOU_BEYOND_KEY,
                        Component.literal(category.render(s.score())),
                        Component.literal(Integer.toString(s.beyond()))))
            // "Not yet" is a promise a closed list cannot keep, so a retired board's unranked
            // line is the plain statement.
            .orElseGet(() -> Component.translatable(retired ? ERA_YOU_UNRANKED_KEY : LeaderboardCategory.YOU_UNRANKED_KEY));
        // A retired list says so where the reader's own line is: it is the one page a reader is sure
        // to reach, and "you are #4" on a board nobody can climb any more needs the caveat beside it.
        pages.add(retired
            ? closing.append("\n\n").append(Component.translatable(ERA_CLOSED_KEY))
            : closing);
        return pages;
    }

    /** Translation keys for the era wording. The heading wrapper takes the heading and the era's name. */
    static final String ERA_KEY = "dungeontrain.leaderboard.era";
    /** A version era with a known successor: "Dungeon Train v0.1013 up to v0.1020". */
    static final String ERA_VERSION_RANGE_KEY = "dungeontrain.leaderboard.era.version.range";
    /** The founding era, which has no floor of its own: "Dungeon Train before v0.1013". */
    static final String ERA_VERSION_BEFORE_KEY = "dungeontrain.leaderboard.era.version.before";
    /** A version era whose successor the relay did not list: "Dungeon Train v0.1013 onward". */
    static final String ERA_VERSION_ONWARD_KEY = "dungeontrain.leaderboard.era.version.onward";
    static final String ERA_YEAR_KEY = "dungeontrain.leaderboard.era.year";
    static final String ERA_CLOSED_KEY = "dungeontrain.leaderboard.era.closed";
    static final String ERA_YOU_UNRANKED_KEY = "dungeontrain.leaderboard.era.you_unranked";

    /** The heading for one era's board: the plain heading, wrapped with the era's name when retired. */
    static MutableComponent heading(LeaderboardCategory category, String era) {
        MutableComponent plain = heading(category);
        if (era == null || era.isEmpty()) return plain;
        return Component.translatable(ERA_KEY, plain, eraName(era));
    }

    /**
     * How an era is named in the book: "The year 2025" for a year; for a game version, the span of
     * Dungeon Train versions whose runs it holds — from the era's own floor up to (not including)
     * the next era's, or "before v…" for the founding era. Versions, not the relay's label: a
     * reader wants to know which game the numbers came from, and "0.1013" says that exactly. An
     * era the relay has not described (the list is stale, or never landed) is named by its id.
     */
    static MutableComponent eraName(String era) {
        Optional<LeaderboardPool.Era> known = LeaderboardPool.era(era);
        if (known.isEmpty()) return Component.literal(era);
        LeaderboardPool.Era e = known.get();
        if (e.isYear()) return Component.translatable(ERA_YEAR_KEY, Component.literal(e.label()));
        Optional<String> next = LeaderboardPool.nextVersionFloor(era);
        if (e.isFounding()) {
            return next.map(v -> Component.translatable(ERA_VERSION_BEFORE_KEY, Component.literal(v)))
                .orElseGet(() -> Component.literal(e.label()));
        }
        if (e.minVersion().isEmpty()) return Component.literal(e.label());
        return next.map(v -> Component.translatable(ERA_VERSION_RANGE_KEY, Component.literal(e.minVersion()), Component.literal(v)))
            .orElseGet(() -> Component.translatable(ERA_VERSION_ONWARD_KEY, Component.literal(e.minVersion())));
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
