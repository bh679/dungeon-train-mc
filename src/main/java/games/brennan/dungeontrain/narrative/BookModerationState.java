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

    /** Released. The only state the shared pool serves, and the only one that shows nothing at all. */
    APPROVED(null),

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

    /** Whether this state is shown to the writer at all — i.e. anything but {@link #APPROVED}. */
    public boolean isWithheld() {
        return this != APPROVED;
    }

    /**
     * The state a relay {@code status} names. Null, blank or unrecognised → {@link #APPROVED}; see the
     * class note on why the unknown case fails open.
     */
    public static BookModerationState fromStatus(String status) {
        if (status == null) return APPROVED;
        return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "pending" -> READING;
            case "flagged", "needs_human_review" -> UNDECIDED;
            case "rejected" -> DISLIKED;
            default -> APPROVED;
        };
    }
}
