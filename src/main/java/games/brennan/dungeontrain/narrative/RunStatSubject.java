package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.player.PlayerRunState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * The one thing a {@link RunStatBookFactory Faulthurst stat book} can be about — a single counter
 * from the reader's CURRENT run, and the sentence that says it.
 *
 * <p>Every subject reads a counter {@link PlayerRunState} already keeps, so nothing here adds
 * tracking. What it adds is the two things a counter needs before it can become a sentence: a
 * {@link #floor} below which the number is not worth saying, and a {@link Format} deciding whether
 * the line declines with its count.</p>
 *
 * <h2>The floor is the point</h2>
 * <p>"You've opened 0 chests. Why?" is a worse book than no book. A subject is offered only once its
 * counter clears {@link #floor}, so Faulthurst never remarks on something that hasn't happened. The
 * floors are per-subject because the thresholds differ in kind: one chest is worth noticing, one
 * block travelled is not.</p>
 *
 * <p>{@link #PLAYTIME} is the guaranteed fallback — see {@link #eligible}. Time aboard is the one
 * counter that cannot be zero for a player holding a book, so there is always something to say.</p>
 *
 * <h2>Plurals</h2>
 * <p>{@link Format#COUNT} lines end in a countable noun and so decline: their key gains a CLDR
 * category suffix from {@link PluralRules}, chosen against the READER's language, exactly as
 * {@code FamiliarBookMessage} does. {@link Format#PLAIN} and {@link Format#DURATION} lines take a
 * preformatted string ({@code "2h 14m"}, {@code "47"}) rather than a bare count and have nothing to
 * decline, so they use a flat key.</p>
 */
public enum RunStatSubject {

    // id              key base         format           floor  extractor
    /** Net carriages travelled this life. Absolute: going backwards is still getting somewhere. */
    CARRIAGE("carriage", Format.PLAIN, 1, s -> Math.abs(s.travelledCarriageIndex()), "carriages"),

    /**
     * Seconds spent aboard this life — the "Longest Aboard" boards' figure, gated by
     * {@link games.brennan.dungeontrain.event.PlayerActivityTracker} so idle and paused stretches
     * do not count. The fallback subject — see {@link #eligible}.
     */
    PLAYTIME("playtime", Format.DURATION, 60, s -> s.trainTimeTicks() / Ticks.PER_SECOND, "playtime"),

    CHESTS("chests", Format.COUNT, 1, PlayerRunState::containersOpened, "chests"),
    MOB_KILLS("mob_kills", Format.COUNT, 1, PlayerRunState::mobKills),
    DISTANCE("distance", Format.COUNT, 100, s -> (long) s.distanceBlocks(), "distance"),
    BOOKS_READ("books_read", Format.COUNT, 1, PlayerRunState::booksReadCount, "books_read"),
    BOOKS_WRITTEN("books_written", Format.COUNT, 1, PlayerRunState::booksWrittenCount, "books_written"),
    FRIENDS("friends", Format.COUNT, 1, PlayerRunState::befriendedCount, "friends"),
    ENCOUNTERS("encounters", Format.COUNT, 1, PlayerRunState::encounteredCount),
    ECHOES("echoes", Format.COUNT, 1, PlayerRunState::echoesKilled, "echoes_killed"),
    TAMED("tamed", Format.COUNT, 1, PlayerRunState::tamedCount),
    DAMAGE_TAKEN("damage_taken", Format.PLAIN, 10, s -> (long) s.damageTaken()),
    PLAYER_KILLS("player_kills", Format.COUNT, 1, PlayerRunState::playerKills),
    NO_CHEST("no_chest", Format.COUNT, 3, PlayerRunState::maxCarriagesNoChest, "carriages_no_chest"),
    BACKWARDS("backwards", Format.COUNT, 1, PlayerRunState::cartsBackwardSinceDeath),
    PACIFIST("pacifist", Format.COUNT, 3, PlayerRunState::pacifistCarriages, "pacifist_carriages"),

    // The remaining leaderboard subjects that have a per-run twin at all. Every RUN-scoped board
    // above already had one; these two are boards kept as lifetime tallies whose one-life half is
    // nonetheless a real, countable thing — so Faulthurst can remark on it.
    DEATH_NOTES("death_notes", Format.COUNT, 1, PlayerRunState::deathNotesWritten, "deathnotes_written"),
    LOVE_NOTES("love_notes", Format.COUNT, 1, PlayerRunState::loveNotesWritten, "lovenotes_written");

    /**
     * Vanilla server tick rate — {@link #PLAYTIME} reports seconds, not ticks.
     *
     * <p>One class removed from the constant that uses it, for the same reason
     * {@code LeaderboardCategory.Scope.Suffix} is: an enum constant may not read a static field of
     * its own enum, because the constants are initialised first. A nested holder keeps the number
     * named instead of leaving a bare {@code 20L} in the constant list.</p>
     */
    private static final class Ticks {
        static final long PER_SECOND = 20L;

        private Ticks() {}
    }

    /** Root of every stat-book translation key. */
    public static final String KEY_ROOT = "book.dungeontrain.statbook.";

    /**
     * Whether a subject's line declines with its number.
     *
     * <ul>
     *   <li>{@link #COUNT} — ends in a countable noun ("3 chests"). Declines; keyed per CLDR
     *       category.</li>
     *   <li>{@link #DURATION} — takes {@code "2h 14m"}, rendered by
     *       {@link LeaderboardCategory#duration}. One flat key.</li>
     *   <li>{@link #PLAIN} — takes a bare number the sentence does not pluralise round
     *       ("carriage 14", "47 damage"). One flat key.</li>
     * </ul>
     */
    public enum Format { COUNT, DURATION, PLAIN }

    private final String id;
    private final Format format;
    private final long floor;
    private final ToLongFunction<PlayerRunState> extractor;
    private final String boardBase;

    RunStatSubject(String id, Format format, long floor, ToLongFunction<PlayerRunState> extractor) {
        this(id, format, floor, extractor, null);
    }

    RunStatSubject(String id, Format format, long floor, ToLongFunction<PlayerRunState> extractor,
                   String boardBase) {
        this.id = id;
        this.format = format;
        this.floor = floor;
        this.extractor = extractor;
        this.boardBase = boardBase;
    }

    /** Stable wire id — stamped into {@link RunStatBookTag} and used to build the lang key. */
    public String id() { return id; }

    /**
     * {@link LeaderboardCategory#base() The leaderboard subject} this is the one-life twin of, or
     * {@code null} for the subjects the boards do not measure (damage taken, animals tamed, and the
     * rest — worth remarking on, just not worth ranking).
     *
     * <p><b>This is the coverage contract.</b> Every {@code Scope.RUN} board must have a subject
     * naming it, so a player can be told their own number for anything the game ranks them on;
     * {@code RunStatSubjectTest} fails if a new run board is added without one. Boards kept as
     * lifetime tallies are claimed here too where their one-life half is a real countable thing —
     * the notes a player signs — and left alone where it is not: how many people a note reached is
     * answered by their save, not this one, and "lives spent" in one life is always one.</p>
     */
    public String boardBase() { return boardBase; }

    public Format format() { return format; }

    /** The value below which this subject is not worth a sentence. */
    public long floor() { return floor; }

    /** This subject's counter, read from {@code run}. Never negative. */
    public long value(PlayerRunState run) {
        return run == null ? 0L : Math.max(0L, extractor.applyAsLong(run));
    }

    /** Whether {@code run} has done enough of this for Faulthurst to remark on it. */
    public boolean clearsFloor(PlayerRunState run) {
        return value(run) >= floor;
    }

    /** Base translation key; {@link Format#COUNT} appends a CLDR category to it. */
    public String key() { return KEY_ROOT + "stat." + id; }

    /**
     * The sentence naming this subject's number, in {@code localeCode}'s grammar.
     *
     * <p>Counted subjects go through {@link PluralRules#clause}, which picks the category the
     * reader's language wants for this exact number. The other two formats hand their key a
     * preformatted string, so there is nothing to decline and the key is used flat.</p>
     */
    public MutableComponent line(String localeCode, long value) {
        return switch (format) {
            case COUNT -> PluralRules.clause(localeCode, key(), value);
            case DURATION -> Component.translatable(key(), LeaderboardCategory.duration(value));
            case PLAIN -> Component.translatable(key(), Long.toString(value));
        };
    }

    /**
     * The value as it appears in the sentence — the string {@link RunStatBookTag} remembers so a
     * refresh can tell "nothing has changed" from "re-bake the page". Compared rather than the raw
     * long because that is what the reader actually sees: a playtime ticking from 2h 14m 03s to 2h
     * 14m 41s renders identically and must not churn the stack.
     */
    public String rendered(long value) {
        return format == Format.DURATION ? LeaderboardCategory.duration(value) : Long.toString(value);
    }

    public static Optional<RunStatSubject> byId(String id) {
        if (id == null) return Optional.empty();
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (RunStatSubject s : values()) {
            if (s.id.equals(norm)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Every subject {@code run} has done enough of to be worth a book, or — when it has done none of
     * them — {@link #PLAYTIME} alone.
     *
     * <p>The fallback ignores {@link #floor} deliberately. A player who picks a book up in their
     * first seconds aboard still gets a true sentence ("You've been aboard for 12s"), which is a
     * better book than a subject padded to a number that isn't real.</p>
     */
    public static List<RunStatSubject> eligible(PlayerRunState run) {
        List<RunStatSubject> out = new ArrayList<>();
        for (RunStatSubject s : values()) {
            if (s.clearsFloor(run)) out.add(s);
        }
        return out.isEmpty() ? List.of(PLAYTIME) : out;
    }
}
