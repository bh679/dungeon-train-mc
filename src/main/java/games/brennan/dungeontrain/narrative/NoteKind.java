package games.brennan.dungeontrain.narrative;

import java.util.Collection;
import java.util.Locale;

/**
 * The two kinds of "note" a player can sign into an echo: the {@link #DEATH} curse and its mirror,
 * the {@link #LOVE} note. Both ride <em>one</em> pipeline — sign → arm on the author's death →
 * upload to the relay → the target pulls it → an echo of the author spawns at the carriage depth
 * the author died at. The kind is what differs at the two ends: which book the player writes, and
 * how the echo feels about them when it arrives.
 *
 * <p>Every per-kind constant lives here so the two can't drift apart as the feature grows. The one
 * deliberate exception is the seeded feeling itself — {@code DeathNoteEchoSpawner} maps the kind to
 * {@code FeelingLedger.MIN} / {@code MAX} at the point of use, which keeps this class free of
 * Minecraft <em>and</em> PlayerMob types so the title rules stay unit-testable without a server.</p>
 */
public enum NoteKind {

    /**
     * The curse. The echo arrives with the lowest possible feeling toward the target, which
     * {@code DispositionResolver} reads as "hate" — it hunts them regardless of its own nature.
     */
    DEATH("death", "deathnote", "Death Note", 1, "/random_books/deathnote.json"),

    /**
     * The mirror. The echo arrives with the highest possible feeling toward the target, which
     * {@code DispositionResolver} reads as "love" — it greets rather than attacks at any
     * friendliness, gifts them items, and wades in to defend them even when badly outmatched.
     */
    LOVE("love", "lovenote", "Love Note", 2, "/random_books/lovenote.json");

    /** Wire value on the relay ({@code kind} column / JSON field) and in world-save NBT. */
    private final String id;
    /** The canonical English trigger in {@link DeathNoteTitle#normalize normalized} form. */
    private final String englishTitle;
    /** Display title of the keepable trophy book the echo drops when it dies. */
    private final String trophyTitle;
    /** {@code CUSTOM_MODEL_DATA} value selecting this kind's book model (see written_book.json). */
    private final int modelData;
    /** Resource-path suffix of the instruction book whose title defines this kind's trigger word. */
    private final String instructionBookSuffix;

    NoteKind(String id, String englishTitle, String trophyTitle, int modelData,
             String instructionBookSuffix) {
        this.id = id;
        this.englishTitle = englishTitle;
        this.trophyTitle = trophyTitle;
        this.modelData = modelData;
        this.instructionBookSuffix = instructionBookSuffix;
    }

    public String id() {
        return id;
    }

    public String englishTitle() {
        return englishTitle;
    }

    public String trophyTitle() {
        return trophyTitle;
    }

    public int modelData() {
        return modelData;
    }

    public String instructionBookSuffix() {
        return instructionBookSuffix;
    }

    /**
     * The kind {@code title} names, or {@code null} when it names neither. Each kind matches its
     * canonical English trigger (always accepted, in any locale) plus that kind's translated trigger
     * words — {@code deathTitles} / {@code loveTitles}, derived from the instruction books by
     * {@link DeathNoteTitleLocalization}. All comparisons ignore case and whitespace.
     *
     * <p>{@link #DEATH} is tested first: should a translation ever collide with the curse's trigger,
     * the older mechanic keeps the title rather than silently changing meaning under players.</p>
     */
    public static NoteKind of(String title, Collection<String> deathTitles,
                              Collection<String> loveTitles) {
        if (DeathNoteTitle.matches(title, DEATH.englishTitle, deathTitles)) return DEATH;
        if (DeathNoteTitle.matches(title, LOVE.englishTitle, loveTitles)) return LOVE;
        return null;
    }

    /**
     * The kind named by a stored/wire {@code id}. Anything unrecognised — including {@code null} and
     * {@code ""} — reads as {@link #DEATH}, because that is what every note written before this enum
     * existed was: a pre-kind world save or a relay row from an older schema is a curse.
     */
    public static NoteKind fromId(String id) {
        if (id != null) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            for (NoteKind kind : values()) {
                if (kind.id.equals(normalized)) return kind;
            }
        }
        return DEATH;
    }
}
