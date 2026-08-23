package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;

/**
 * How the train feels about one player-written book, as far as the player who wrote it is concerned.
 *
 * <p>The relay withholds everything but {@code approved} from the shared pool, which used to mean a
 * writer's book simply vanished the moment they signed it — no way to find it, and no way to tell
 * whether it had been refused, was still in the screening queue, or had never been looked at. A
 * writer can now find their own withheld books on their own library shelves
 * ({@code /books/pool?mine=1}), and this is what turns the relay's flag into the two things the game
 * shows them: a colour on the page, and which set of chat lines to draw from.</p>
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
    APPROVED(null, null),

    /**
     * Submitted, and nothing has read it yet — the relay's {@code pending}, which is where a book
     * waits under {@code SCREENING_MODE=queue}. Yellow: this is the hopeful one.
     */
    READING(ChatFormatting.YELLOW, "reading"),

    /**
     * Read and withheld, with no verdict either way — the relay's {@code flagged} and
     * {@code needs_human_review}. Gold: further along than {@link #READING}, still not a no.
     */
    UNDECIDED(ChatFormatting.GOLD, "undecided"),

    /** Refused. It will never reach another player — but the writer keeps it. Red. */
    DISLIKED(ChatFormatting.RED, "disliked");

    private final ChatFormatting tint;
    private final String messageKey;

    BookModerationState(ChatFormatting tint, String messageKey) {
        this.tint = tint;
        this.messageKey = messageKey;
    }

    /** The colour this book's pages and item name are shown in, or {@code null} to leave them alone. */
    public ChatFormatting tint() {
        return tint;
    }

    /** The {@code chat.dungeontrain.unapproved_book.<key>.N} family for this state, or {@code null}. */
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
