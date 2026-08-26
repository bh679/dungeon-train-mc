package games.brennan.dungeontrain.narrative;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The leaderboard boards a {@link LeaderboardBookFactory leaderboard book} can be about — one entry
 * per category the relay serves at {@code GET /leaderboard?cat=}.
 *
 * <p>{@link #id()} is the wire value and is the contract with {@code leaderboard.js}; changing one
 * silently empties that board rather than failing loudly, so they are kept identical on both sides
 * and covered by a test.</p>
 *
 * <h2>Scope is a flag, not a separate board</h2>
 * <p>Most metrics come in a pair: the best a player has ever managed in ONE life, and the same
 * counter added up across EVERY life. Those two are not different subjects — they are the same
 * subject asked over a different span. So they share a {@link #base} name and a single
 * {@link #headerKey() header}, and the span is said once, in one place, by {@link Scope}:</p>
 *
 * <ul>
 *   <li>{@link Scope#RUN} — one life. Title gains {@value Scope#RUN_TITLE_SUFFIX}; the heading is
 *       wrapped by {@code dungeontrain.leaderboard.scope.run}.</li>
 *   <li>{@link Scope#TOTAL} — every life. Title gains {@value Scope#TOTAL_TITLE_SUFFIX}; the heading
 *       is wrapped by {@code dungeontrain.leaderboard.scope.total}.</li>
 *   <li>{@link Scope#NONE} — the boards with no span to speak of (notes, votes, translations,
 *       donations). Nothing is appended.</li>
 * </ul>
 *
 * <p>Both are subject to {@link #labelsSpan()}: a {@code RUN} board with no {@code TOTAL} twin keeps
 * its scope for what it measures, but says nothing about it on the cover.</p>
 *
 * <p>The point is that a wording change lands once instead of twice, and the two halves of a pair
 * cannot drift apart: "Furthest Distance, One Life" and "Furthest Distance, All Lives" are the same
 * five words plus the flag. It also cuts the translated headings from one per board to one per
 * subject, with the two span sentences translated once each.</p>
 *
 * <p><b>A lone board says nothing about its span.</b> The span is a qualifier, and on a subject with
 * only one board there is nothing to qualify against — "Most Books Read, All Lives" spends five
 * words answering a question nobody asked. So the label is applied only where the subject has both
 * halves, and {@link SpanLabel} is the per-board override for the cases where that reads wrong.</p>
 *
 * <p>The scope sentence is a FORMAT with a {@code %s}, not a suffix glued on in code, so a
 * translator can put the span first where the language wants it there.</p>
 *
 * <h2>Localisation</h2>
 * <p>{@link #title()} is English: {@code WrittenBookContent} takes a plain string, so there is
 * nowhere for a translation to happen and the suffix is concatenated. The heading is a
 * {@link net.minecraft.network.chat.Component} and resolves on the reader's own client.</p>
 */
public enum LeaderboardCategory {

    // id                    base                  base title              scope         format
    PLAYTIME_TOTAL("playtime_total", "playtime", "Longest Aboard", Scope.TOTAL, Format.DURATION),
    PLAYTIME_RUN("playtime_run", "playtime", "Longest Aboard", Scope.RUN, Format.DURATION),
    CARRIAGES_TOTAL("carriages_total", "carriages", "Furthest Carriage", Scope.TOTAL, Format.COUNT),
    CARRIAGES_RUN("carriages_run", "carriages", "Furthest Carriage", Scope.RUN, Format.COUNT),
    DISTANCE_RUN("distance_run", "distance", "Furthest Distance", Scope.RUN, Format.DISTANCE),
    DISTANCE_TOTAL("distance_total", "distance", "Furthest Distance", Scope.TOTAL, Format.DISTANCE),
    PACIFIST_CARRIAGES("pacifist_carriages", "pacifist_carriages", "Furthest Pacifist", Scope.RUN, Format.COUNT),
    FRIENDS_RUN("friends_run", "friends", "Friendliest Passenger", Scope.RUN, Format.COUNT),
    FRIENDS_TOTAL("friends_total", "friends", "Friendliest Passenger", Scope.TOTAL, Format.COUNT),
    LIVES("lives", "lives", "Most Lives Spent", Scope.TOTAL, Format.COUNT),
    CHESTS_OPENED("chests_opened", "chests", "Most Chests Opened", Scope.TOTAL, Format.COUNT),
    CHESTS_RUN("chests_run", "chests", "Most Chests Opened", Scope.RUN, Format.COUNT),
    BOOKS_WRITTEN("books_written", "books_written", "Most Books Written", Scope.TOTAL, Format.COUNT),
    BOOKS_READ("books_read", "books_read", "Most Books Read", Scope.TOTAL, Format.COUNT),
    ADVANCEMENTS("advancements", "advancements", "Most Advancements", Scope.TOTAL, Format.COUNT),
    ECHOES_KILLED_RUN("echoes_killed_run", "echoes_killed", "Echoes Put Down", Scope.RUN, Format.COUNT),
    ECHOES_KILLED_TOTAL("echoes_killed_total", "echoes_killed", "Echoes Put Down", Scope.TOTAL, Format.COUNT),
    CARRIAGES_NO_CHEST("carriages_no_chest", "carriages_no_chest", "Walk Past The Loot", Scope.RUN, Format.COUNT),
    DEATHNOTES_WRITTEN("deathnotes_written", "deathnotes_written", "Most Curses Written", Scope.NONE, Format.COUNT),
    DEATHNOTES_FOUGHT("deathnotes_fought", "deathnotes_fought", "Most Curses Survived", Scope.NONE, Format.COUNT),
    LOVENOTES_WRITTEN("lovenotes_written", "lovenotes_written", "Most Blessings Written", Scope.NONE, Format.COUNT),
    LOVENOTES_RECEIVED("lovenotes_received", "lovenotes_received", "Most Blessings Received", Scope.NONE, Format.COUNT),
    BOOK_VOTES("book_votes", "book_votes", "Best Loved Writers", Scope.NONE, Format.COUNT),
    TRANSLATIONS("translations", "translations", "Most Words Translated", Scope.NONE, Format.COUNT),
    DONATIONS("donations", "donations", "Kindest Benefactors", Scope.NONE, Format.MONEY);

    /**
     * Which span of play a board measures — the flag that turns a pair of boards into one subject
     * asked twice.
     */
    public enum Scope {
        NONE(null, ""),
        RUN("dungeontrain.leaderboard.scope.run", RUN_TITLE_SUFFIX),
        TOTAL("dungeontrain.leaderboard.scope.total", TOTAL_TITLE_SUFFIX);

        /** Appended to the English cover title of every one-life board. */
        static final String RUN_TITLE_SUFFIX = ", One Life";
        /** Appended to the English cover title of every all-lives board. */
        static final String TOTAL_TITLE_SUFFIX = ", All Lives";

        private final String key;
        private final String titleSuffix;

        Scope(String key, String titleSuffix) {
            this.key = key;
            this.titleSuffix = titleSuffix;
        }

        /**
         * Translation key of the sentence that says this span, a format taking the board's own
         * heading as its {@code %s}. Null for {@link #NONE}, which says nothing.
         */
        public String key() { return key; }

        public String titleSuffix() { return titleSuffix; }
    }

    /**
     * Whether a board says its span out loud.
     *
     * <p>The span is a qualifier, and a qualifier with nothing to qualify against is noise: on a
     * subject that only has one board, "One Life" answers a question nobody was asking. So the
     * default is {@link #AUTO} — say it only where there is a twin to be told apart from — and the
     * other two values are the override for when that reads wrong.</p>
     */
    public enum SpanLabel {
        /** Say the span only when this subject has both halves. The default, and usually right. */
        AUTO,
        /** Say it even alone — for a board whose span a reader would otherwise assume wrongly. */
        ALWAYS,
        /** Never say it, even paired — for a subject whose titles already carry the distinction. */
        NEVER,
    }

    /** How a board's numbers read. The score itself is always an integer on the wire. */
    public enum Format { COUNT, DURATION, MONEY, DISTANCE }

    private final String id;
    private final String base;
    private final String baseTitle;
    private final Scope scope;
    private final SpanLabel spanLabel;
    private final Format format;

    LeaderboardCategory(String id, String base, String baseTitle, Scope scope, Format format) {
        this(id, base, baseTitle, scope, format, SpanLabel.AUTO);
    }

    LeaderboardCategory(String id, String base, String baseTitle, Scope scope, Format format,
                        SpanLabel spanLabel) {
        this.id = id;
        this.base = base;
        this.baseTitle = baseTitle;
        this.scope = scope;
        this.spanLabel = spanLabel;
        this.format = format;
    }

    /**
     * Subjects served by more than one board — the ones where a span actually distinguishes
     * something. Filled after the constants exist, which is what a static block is for.
     */
    private static final Set<String> PAIRED_SUBJECTS;
    static {
        Map<String, Integer> counts = new HashMap<>();
        for (LeaderboardCategory c : values()) counts.merge(c.base, 1, Integer::sum);
        Set<String> paired = new HashSet<>();
        counts.forEach((base, n) -> { if (n > 1) paired.add(base); });
        PAIRED_SUBJECTS = Set.copyOf(paired);
    }

    /** The relay's category id. Must match {@code leaderboard.js}'s CATEGORIES exactly. */
    public String id() { return id; }

    /**
     * The subject this board is about, shared with its opposite-scope twin where it has one. Names
     * the heading key, so a pair reads from one translated string.
     */
    public String base() { return base; }

    /** The cover title without its span — what a pair has in common. */
    public String baseTitle() { return baseTitle; }

    public Scope scope() { return scope; }

    public SpanLabel spanLabel() { return spanLabel; }

    /**
     * Whether this board appends its span to the title and wraps its heading in the span sentence.
     * A lone board says nothing about its span unless {@link SpanLabel#ALWAYS} asks it to.
     */
    public boolean labelsSpan() {
        if (scope == Scope.NONE || spanLabel == SpanLabel.NEVER) return false;
        return spanLabel == SpanLabel.ALWAYS || PAIRED_SUBJECTS.contains(base);
    }

    /** Whether more than one board covers this subject. */
    public boolean isPaired() { return PAIRED_SUBJECTS.contains(base); }

    /** The book's cover title: the subject, and the span when there is one worth saying. */
    public String title() { return baseTitle + (labelsSpan() ? scope.titleSuffix() : ""); }

    public Format format() { return format; }

    /**
     * Translation key for the line introducing the board on the book's first page. Keyed on the
     * SUBJECT, not the board — the one-life and all-lives halves of a pair share it, and
     * {@link #scopeKey()} is what tells them apart.
     */
    public String headerKey() { return "dungeontrain.leaderboard." + base + ".header"; }

    /**
     * Translation key of the sentence wrapping {@link #headerKey()} to say which span this board
     * covers, or {@code null} when the board has no span to say.
     */
    public String scopeKey() { return labelsSpan() ? scope.key() : null; }

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
