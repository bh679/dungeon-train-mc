package games.brennan.dungeontrain.narrative;

/**
 * How the train feels about one player-written book, as far as the player who wrote it is concerned.
 *
 * <p>The relay withholds everything but {@code approved} from the shared pool, which used to mean a
 * writer's book simply vanished the moment they signed it — no way to find it, and no way to tell
 * whether it had been refused, was still in the screening queue, or had never been looked at. A
 * writer can now find their own withheld books on their own library shelves
 * ({@code /books/pool?mine=1}), and this is what names the state the vote page reports.</p>
 *
 * <p>The book itself is left alone — same title, same author, same pages. The news belongs on the
 * train's own page, not written over the author's.</p>
 *
 * <h3>Why the states collapse the way they do</h3>
 * <p>The relay distinguishes {@code flagged} (the first-pass classifier withheld it, nobody has read
 * it since) from {@code needs_human_review} (an automated reviewer read it and escalated). That
 * distinction matters enormously to a moderation queue and not at all to the person waiting: both
 * mean "read, no verdict". They share {@link #UNDECIDED}.</p>
 *
 * <p><b>Unknown fails OPEN, to {@link #APPROVED}.</b> That is the opposite of how kid-safety resolves
 * an unknown, and deliberately: the cost of guessing wrong here is not exposure, it is tinting an
 * ordinary community book red and telling its reader the train dislikes it.</p>
 */
public enum BookModerationState {

    /**
     * The relay said nothing about this book — an ordinary community book, by anybody, which is what
     * the overwhelming majority of books a player meets are.
     *
     * <p>Deliberately distinct from {@link #APPROVED}. Only the author's own shelf carries a status
     * at all, so "no status" and "status: approved" are two different facts: somebody's released book
     * versus YOUR released book. The vote page offers different controls for each — a stranger's book
     * can be rated and reported, your own can be withdrawn — so collapsing them would put the wrong
     * buttons on the page.</p>
     */
    PUBLIC(null),

    /** Yours, and released — out on the line where other players can find it. */
    APPROVED("released"),

    /**
     * Submitted, and nothing has read it yet — the relay's {@code pending}, which is where a book
     * waits under {@code SCREENING_MODE=queue}.
     */
    READING("reading"),

    /**
     * Read and withheld, with no verdict either way — the relay's {@code flagged} and
     * {@code needs_human_review}. Further along than {@link #READING}, and still not a no.
     */
    UNDECIDED("undecided"),

    /** Refused. It will never reach another player — but the writer keeps it. */
    DISLIKED("disliked");

    private final String messageKey;

    BookModerationState(String messageKey) {
        this.messageKey = messageKey;
    }

    /**
     * The {@code gui.dungeontrain.book_vote.status.<key>.N} family for this state, or {@code null}.
     *
     * <p>The COLOUR each state is drawn in is not here: the vote page paints with raw values from its
     * own leather-and-ink palette rather than with chat formatting, so it lives there — orange while
     * an answer is still coming, red once it is not.</p>
     */
    public String messageKey() {
        return messageKey;
    }

    /** Whether the train is holding this book back from other players. */
    public boolean isWithheld() {
        return this != PUBLIC && this != APPROVED;
    }

    /**
     * Whether this is the reader's OWN book — anything the relay told us the state of.
     *
     * <p>This is the question the vote page really asks: your own books never show the thumbs (see
     * {@code BookVoteClientEvents}), because the relay weights which books get served by player votes
     * and does not check authorship, so a shelf of your own writing would otherwise be a
     * self-upvoting machine.</p>
     */
    public boolean isOwn() {
        return this != PUBLIC;
    }

    /**
     * Whether there is a verdict here for the author to object to.
     *
     * <p>False for {@link #READING}: nothing has read that book yet, so there is nothing to disagree
     * with — a protest control on it would invite an argument with a decision that has not been made.
     * The page simply says the train is still reading and offers nothing to press.</p>
     */
    public boolean canProtest() {
        return this == UNDECIDED || this == DISLIKED;
    }

    /**
     * The state a relay {@code status} names. Absent/blank → {@link #PUBLIC} (an ordinary book we were
     * told nothing about); an unrecognised value → {@link #APPROVED}, since it still arrived on the
     * reader's own shelf. See the class note on why the unknown case fails open rather than closed.
     */
    public static BookModerationState fromStatus(String status) {
        if (status == null || status.isBlank()) return PUBLIC;
        return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "pending" -> READING;
            case "flagged", "needs_human_review" -> UNDECIDED;
            case "rejected" -> DISLIKED;
            case "approved" -> APPROVED;
            // A state a newer relay knows and this jar does not. Treat it as an ordinary released book
            // of the reader's own: it still came off their own shelf, so the withdraw control belongs
            // on it, but nothing is claimed about a verdict this jar cannot name.
            default -> APPROVED;
        };
    }
}
