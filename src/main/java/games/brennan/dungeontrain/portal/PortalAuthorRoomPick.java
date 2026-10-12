package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.relay.BookAuthorsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Which author room a pair stands, once the lottery has landed on an author-room parent.
 *
 * <p>Every author room is a sub-variant of one parent whose own template is never stamped — it only
 * holds the split. Landing on the parent is one outcome; what kind of library it is comes next, from
 * the parent's Books weights ({@link PortalRoomBooks#resolveShare}); and only then is a room chosen,
 * among the sub-variants whose book range <i>fits</i> what was rolled:</p>
 * <ul>
 *   <li><b>Self</b> — the reader's own entry, drawn from their self page, and the room whose range
 *       holds its count — or comes nearest to it, since a reader's own shelf is exempt from a room's
 *       floor and a first-time writer fits no range exactly. No entry (nothing written, no consent,
 *       page not in yet) stays Self with a room by weight: the Self lock already rotates such a reader
 *       to somebody else's shelf, and an own-books boost must not lose its Self answer to a cold page.</li>
 *   <li><b>Player / Signature</b> — an author drawn first from the directory, then a room whose range
 *       holds their count. A cold directory falls back to the old order: room by weight, author later
 *       from inside that room's range.</li>
 *   <li><b>Stats</b> — the room whose range holds the full tally, or comes nearest to it.</li>
 * </ul>
 * <p>Every draw among rooms — the ones that fit, or all of them when nothing fits — goes by each room's
 * <b>own Books weight for the rolled share</b>: a room with Player 10 stands a Player library ten times
 * as often as one with Player 1. A room weighted 0 for a share never stands it, unless no room is
 * weighted for it at all, when they draw evenly so a pair never goes without a room.</p>
 *
 * <p>Pure: the candidates and the two author pages are passed in, so the whole decision is testable
 * without a server. Seeded from {@code (worldSeed, pairKey)} so the same inputs give the same room.</p>
 */
public final class PortalAuthorRoomPick {

    private PortalAuthorRoomPick() {}

    /** A sub-variant the pick may land on, with its own Books — its range, and its weight per share. */
    public record Candidate(String name, PortalRoomBooks books) {}

    /** The two author pages the pick can read, already cached — empty when cold. */
    public interface Authors {
        /** The reader's own entries, or empty. */
        List<BookAuthorsClient.Author> self();

        /** The {@code share} directory over {@code band}, or empty when it is not in yet. */
        List<BookAuthorsClient.Author> directory(PortalRoomBooks.Share share, PortalRoomBooks band);
    }

    /**
     * What the pair stands.
     *
     * @param roomName the sub-variant
     * @param share    whose books it holds — pinned onto the room's settings
     * @param author   the author it was fitted to, or {@code null} when none was chosen up front
     */
    public record Choice(String roomName, PortalRoomBooks.Share share, BookAuthorsClient.Author author) {}

    /** The parent's split for this pair, or Self when the own-books boost chose it. */
    public static PortalRoomBooks.Share share(PortalRoomBooks parentBooks, int pairKey, boolean pinnedToSelf) {
        if (pinnedToSelf) return PortalRoomBooks.Share.SELF;
        return parentBooks == null ? PortalRoomBooks.Share.PLAYER : parentBooks.resolveShare(pairKey);
    }

    /**
     * The room for {@code share} among {@code candidates}; null only when there are none.
     *
     * @param tallySize how many books a Stats room stocks — {@link PortalRoomStatShelves#FULL_SET}
     */
    public static Choice choose(long worldSeed, int pairKey, PortalRoomBooks.Share share,
                                List<Candidate> candidates, Authors authors, int tallySize) {
        if (candidates == null || candidates.isEmpty()) return null;
        Random rng = new Random(mix(worldSeed, pairKey));
        List<Candidate> pool = standing(candidates, share);

        if (share.isStats()) {
            return new Choice(weighted(nearest(pool, tallySize), share, rng).name(), share, null);
        }
        if (share.isSelf()) {
            List<BookAuthorsClient.Author> own = authors.self();
            if (own == null || own.isEmpty()) return new Choice(weighted(pool, share, rng).name(), share, null);
            BookAuthorsClient.Author mine = own.get(rng.nextInt(own.size()));
            return new Choice(weighted(nearest(pool, mine.count()), share, rng).name(), share, mine);
        }
        Choice fitted = fitToAuthor(pool, authors.directory(share, band(pool)), share, rng);
        if (fitted != null) return fitted;
        return new Choice(weighted(pool, share, rng).name(), share, null);
    }

    /**
     * The first author, in a seeded order, that some candidate's range holds — and a room for them.
     * Null when no author on the page fits any room.
     */
    private static Choice fitToAuthor(List<Candidate> candidates, List<BookAuthorsClient.Author> page,
                                      PortalRoomBooks.Share share, Random rng) {
        if (page == null || page.isEmpty()) return null;
        List<BookAuthorsClient.Author> order = new ArrayList<>(page);
        java.util.Collections.shuffle(order, rng);
        for (BookAuthorsClient.Author author : order) {
            List<Candidate> fit = fitting(candidates, author.count());
            if (!fit.isEmpty()) return new Choice(weighted(fit, share, rng).name(), share, author);
        }
        return null;
    }

    /**
     * The candidates weighted above 0 for {@code share} — the rooms that stand it. All of them when
     * none is, so the share still gets a room.
     */
    static List<Candidate> standing(List<Candidate> candidates, PortalRoomBooks.Share share) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.books() != null && c.books().weightFor(share) > 0) out.add(c);
        }
        return out.isEmpty() ? candidates : out;
    }

    /** The candidates whose book range holds {@code count}. */
    static List<Candidate> fitting(List<Candidate> candidates, int count) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.books() != null && c.books().accepts(count)) out.add(c);
        }
        return out;
    }

    /**
     * The candidates whose range holds {@code count}, or failing that the ones whose range comes
     * nearest to it — never empty for a non-empty input.
     */
    static List<Candidate> nearest(List<Candidate> candidates, int count) {
        List<Candidate> fit = fitting(candidates, count);
        if (!fit.isEmpty()) return fit;
        long best = Long.MAX_VALUE;
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            long d = distance(c.books(), count);
            if (d < best) {
                best = d;
                out.clear();
            }
            if (d == best) out.add(c);
        }
        return out;
    }

    /** How many books short of, or past, {@code books}' range {@code count} is. 0 when it fits. */
    static long distance(PortalRoomBooks books, int count) {
        if (books == null) return Long.MAX_VALUE;
        long low = books.minBooks() + 1L;   // accepts() is strictly more than the floor
        if (count < low) return low - count;
        if (books.maxBooks() != PortalRoomBooks.NO_MAXIMUM && count > books.maxBooks()) {
            return (long) count - books.maxBooks();
        }
        return 0L;
    }

    /**
     * The widest range any candidate accepts — what the directory is asked for, so one page can
     * name an author for every room.
     */
    static PortalRoomBooks band(List<Candidate> candidates) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        boolean open = false;
        for (Candidate c : candidates) {
            PortalRoomBooks b = c.books();
            if (b == null) continue;
            min = Math.min(min, b.minBooks());
            if (b.maxBooks() == PortalRoomBooks.NO_MAXIMUM) open = true;
            else max = Math.max(max, b.maxBooks());
        }
        if (min == Integer.MAX_VALUE) min = PortalRoomBooks.MIN_BOOK_BOUND;
        int w = PortalRoomBooks.DEFAULT_WEIGHT;
        return new PortalRoomBooks(PortalRoomBooks.Kind.MIX, w, w, w, w,
            min, open ? PortalRoomBooks.NO_MAXIMUM : max);
    }

    /** A draw by each room's weight for {@code share}; all-zero weights draw evenly. */
    static Candidate weighted(List<Candidate> from, PortalRoomBooks.Share share, Random rng) {
        int total = 0;
        for (Candidate c : from) total += weightOf(c, share);
        if (total <= 0) return from.get(rng.nextInt(from.size()));
        int r = rng.nextInt(total);
        for (Candidate c : from) {
            r -= weightOf(c, share);
            if (r < 0) return c;
        }
        return from.get(from.size() - 1);
    }

    private static int weightOf(Candidate c, PortalRoomBooks.Share share) {
        return c.books() == null ? 0 : Math.max(0, c.books().weightFor(share));
    }

    /** Splittable-mix, salted so this roll does not track the room pick's, the share's or the boost's. */
    private static long mix(long worldSeed, int pairKey) {
        long state = worldSeed ^ (pairKey * 0x9E3779B97F4A7C15L) ^ 0x415554484F52L; // "AUTHOR"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        return state ^ (state >>> 31);
    }
}
