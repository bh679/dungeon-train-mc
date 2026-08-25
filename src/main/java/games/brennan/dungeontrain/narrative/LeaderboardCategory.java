package games.brennan.dungeontrain.narrative;

import java.util.Locale;
import java.util.Optional;

/**
 * The leaderboard boards a {@link LeaderboardBookFactory leaderboard book} can be about — one entry
 * per category the relay serves at {@code GET /leaderboard?cat=}.
 *
 * <p>{@link #id()} is the wire value and is the contract with {@code leaderboard.js}; changing one
 * silently empties that board rather than failing loudly, so they are kept identical on both sides
 * and covered by a test. {@link #title()} is the book's cover title and {@link #headerKey()} names
 * the translatable line at the top of page one — see {@link LeaderboardBookFactory} for why the
 * title is English and the page is not.</p>
 */
public enum LeaderboardCategory {

    PLAYTIME_TOTAL("playtime_total", "Longest Time Aboard", Format.DURATION),
    PLAYTIME_RUN("playtime_run", "Longest Single Run", Format.DURATION),
    CARRIAGES_TOTAL("carriages_total", "Carriages Reached", Format.COUNT),
    CARRIAGES_RUN("carriages_run", "Furthest In One Run", Format.COUNT),
    DISTANCE_RUN("distance_run", "Furthest From The Start", Format.DISTANCE),
    DISTANCE_TOTAL("distance_total", "Distance Covered, All Lives", Format.DISTANCE),
    PACIFIST_CARRIAGES("pacifist_carriages", "Furthest Without Harm", Format.COUNT),
    FRIENDS_RUN("friends_run", "Most Friends In One Run", Format.COUNT),
    FRIENDS_TOTAL("friends_total", "Most Friends Made", Format.COUNT),
    LIVES("lives", "Most Lives Spent", Format.COUNT),
    CHESTS_OPENED("chests_opened", "Most Chests Opened", Format.COUNT),
    BOOKS_WRITTEN("books_written", "Most Books Written", Format.COUNT),
    BOOKS_READ("books_read", "Most Books Read", Format.COUNT),
    ADVANCEMENTS("advancements", "Most Advancements", Format.COUNT),
    ECHOES_KILLED_RUN("echoes_killed_run", "Most Echoes Put Down", Format.COUNT),
    ECHOES_KILLED_TOTAL("echoes_killed_total", "Echoes Put Down, All Lives", Format.COUNT),
    CARRIAGES_NO_CHEST("carriages_no_chest", "Longest Walk Past The Loot", Format.COUNT),
    DEATHNOTES_WRITTEN("deathnotes_written", "Most Curses Written", Format.COUNT),
    DEATHNOTES_FOUGHT("deathnotes_fought", "Most Curses Survived", Format.COUNT),
    LOVENOTES_WRITTEN("lovenotes_written", "Most Blessings Written", Format.COUNT),
    LOVENOTES_RECEIVED("lovenotes_received", "Most Blessings Received", Format.COUNT),
    BOOK_VOTES("book_votes", "Best Loved Writers", Format.COUNT),
    TRANSLATIONS("translations", "Most Words Translated", Format.COUNT),
    DONATIONS("donations", "Kindest Benefactors", Format.MONEY);

    /** How a board's numbers read. The score itself is always an integer on the wire. */
    public enum Format { COUNT, DURATION, MONEY, DISTANCE }

    private final String id;
    private final String title;
    private final Format format;

    LeaderboardCategory(String id, String title, Format format) {
        this.id = id;
        this.title = title;
        this.format = format;
    }

    /** The relay's category id. Must match {@code leaderboard.js}'s CATEGORIES exactly. */
    public String id() { return id; }

    /** The book's cover title. */
    public String title() { return title; }

    public Format format() { return format; }

    /** Translation key for the line introducing the board on the book's first page. */
    public String headerKey() { return "dungeontrain.leaderboard." + id + ".header"; }

    /** Translation key for the closing line telling the reader where they stand. */
    public static final String YOU_KEY = "dungeontrain.leaderboard.you";

    /** Translation key for the closing line shown to a reader who is not on the board. */
    public static final String YOU_UNRANKED_KEY = "dungeontrain.leaderboard.you_unranked";

    /** Closing line for a reader who IS on the board, but too far down for the relay to place exactly. */
    public static final String YOU_BEYOND_KEY = "dungeontrain.leaderboard.you_beyond";

    public static Optional<LeaderboardCategory> byId(String id) {
        if (id == null) return Optional.empty();
        for (LeaderboardCategory c : values()) {
            if (c.id.equals(id)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * A score as it appears in the book's right-hand column. Deliberately plain digits with no
     * thousands separator: the separator is locale-dependent and the column is not — a book page
     * built for one reader is read by whoever picks it up next.
     */
    public String render(long score) {
        return switch (format) {
            case DURATION -> duration(score);
            case MONEY -> "$" + score;
            // No space before the unit: the column is right-justified against a fixed margin, and
            // every pixel it does not spend is one a long name gets to keep.
            case DISTANCE -> score + "m";
            case COUNT -> Long.toString(score);
        };
    }

    /**
     * Seconds as the two largest useful units — {@code 3d 4h}, {@code 5h 12m}, {@code 42m},
     * {@code 30s}. Two units keeps the column narrow enough to leave room for a name.
     */
    static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return String.format(Locale.ROOT, "%dd %dh", days, hours);
        if (hours > 0) return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
        if (minutes > 0) return String.format(Locale.ROOT, "%dm", minutes);
        return seconds + "s";
    }
}
