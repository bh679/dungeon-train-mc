package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

/**
 * Everything a {@code portal_room} variant says about its own boundary: what it does at its walls,
 * — for {@link PortalRoomMode#ENDLESS_REPETITION} — whether the copies it makes are identical or
 * rolled afresh, whether the room is furnished from the ordinary contents pool, for either
 * endless mode how many extra ways back to the train it scatters through its copies, and whether
 * every book inside it is by one author.
 *
 * <h2>All five live in the one {@code mode} tag</h2>
 * <p>On disk that reads {@code "mode": "endless_repetition/dynamic/fit/random:12/signature"}, or just
 * {@code "mode": "endless_repetition"} when the four trailing settings are at their defaults.
 * {@code TemplateMeta.mode} is documented as an <i>opaque per-kind tag</i> — what it contains is the
 * owning kind's business — so encoding several settings in it is exactly what that field is for, and
 * it keeps a record shared by carriages and contents from growing fields only portal rooms will ever
 * read.</p>
 *
 * <p>They are still separate controls in the editor: a Walls row, a Contents row, a Books row, and —
 * when the walls repeat — a Copies row and an Exits row. Only the storage is shared.</p>
 *
 * <p><b>Trailing segments are optional on the way in.</b> Every tag written before Contents, Exits
 * or Books existed has one to four segments and still parses, to a room that behaves exactly as it
 * did.</p>
 *
 * <h2>Exits is the one setting whose default depends on another</h2>
 * <p>An absent Exits segment does not mean "off", it means "whatever this mode wants" — see
 * {@link PortalRoomMode#defaultExits}. That is what lets the shipped {@code labrynth} room, tagged
 * {@code endless_repetition/dynamic} long before any of this existed, pick up its extra corridors
 * without anybody editing the weights file. It is also why {@link #withMode} has to re-derive: a
 * value that was only ever the old mode's default is not a choice the author made.</p>
 *
 * @param mode     what the room does at its walls
 * @param copies   what its copies are, when it makes any
 * @param contents whether it is furnished from the contents pool, and how
 * @param exits    how many extra corridors back to the train it lays, and how far apart
 * @param books    whether every book found inside is by one author, and how that author is picked
 */
public record PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                                 PortalRoomContents contents, PortalRoomExits exits,
                                 PortalRoomBooks books) {

    /** Separates the mode from the settings that follow it in the stored tag. */
    private static final String SEPARATOR = "/";

    public static final PortalRoomSettings DEFAULT = new PortalRoomSettings(
        PortalRoomMode.DEFAULT, PortalRoomCopies.DEFAULT, PortalRoomContents.DEFAULT, null,
        PortalRoomBooks.DEFAULT);

    public PortalRoomSettings {
        if (mode == null) mode = PortalRoomMode.DEFAULT;
        if (copies == null) copies = PortalRoomCopies.DEFAULT;
        if (contents == null) contents = PortalRoomContents.DEFAULT;
        // After the mode has been settled, never before: a null here means "this room said nothing
        // about its exits", and what that resolves to is the mode's business.
        if (exits == null) exits = mode.defaultExits();
        if (books == null) books = PortalRoomBooks.DEFAULT;
    }

    /** The boundary settings alone, unfurnished — the pair this record was before Contents existed. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies) {
        this(mode, copies, PortalRoomContents.DEFAULT, null, PortalRoomBooks.DEFAULT);
    }

    /** The three settings this record carried before Exits existed, at the mode's own default. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents) {
        this(mode, copies, contents, null, PortalRoomBooks.DEFAULT);
    }

    /** The four settings this record carried before Books existed, with no author lock. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits) {
        this(mode, copies, contents, exits, PortalRoomBooks.DEFAULT);
    }

    /**
     * Read a stored tag. Total: anything unrecognised in any segment falls back to that segment's
     * default, so a hand-edited typo stamps a normal room rather than failing a pair's stamp. A tag
     * with fewer segments than there are settings — every tag written before a setting was added —
     * takes the default for the ones it does not name.
     */
    public static PortalRoomSettings parse(String tag) {
        if (tag == null) return DEFAULT;
        String[] parts = tag.split(SEPARATOR, -1);
        String exitsSegment = segment(parts, 3);
        return new PortalRoomSettings(
            PortalRoomMode.parse(segment(parts, 0)),
            PortalRoomCopies.parse(segment(parts, 1)),
            PortalRoomContents.parse(segment(parts, 2)),
            // Null rather than PortalRoomExits.parse(null): an absent segment must reach the
            // constructor as "unsaid" so the mode's default applies, not as a value of its own.
            exitsSegment == null ? null : PortalRoomExits.parse(exitsSegment),
            PortalRoomBooks.parse(segment(parts, 4)));
    }

    /** Segment {@code index} of a split tag, or null when the tag is shorter than that. */
    private static String segment(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    /** The settings a named room variant is authored with. */
    public static PortalRoomSettings of(String roomName) {
        return parse(TrackVariantWeights.modeFor(TrackKind.PORTAL_ROOM, roomName));
    }

    /**
     * The tag to store.
     *
     * <p>Trailing settings are only written when they would change something — so a room that never
     * repeats and is not furnished round-trips as the bare mode id it always was, and a tag written
     * before Contents or Exits existed is re-written unchanged.</p>
     *
     * <p>The segments are positional, so a later one cannot be written without the earlier ones in
     * front of it. Where an earlier setting means nothing here its default id is written as the
     * placeholder, which that setting's own {@code parse} reads back as the same default.</p>
     *
     * <p>"Would change something" for Exits means <i>differs from this mode's default</i>, not
     * differs from a fixed value — an Endless Repetition room that lays corridors is saying what its
     * mode already says, and writing it out would put a segment in every such tag for nothing.</p>
     */
    public String toTag() {
        PortalRoomCopies effectiveCopies = copiesApply() ? copies : PortalRoomCopies.DEFAULT;
        PortalRoomExits effectiveExits = effectiveExits();
        if (books != PortalRoomBooks.DEFAULT) {
            // Exits is written out as whatever it effectively is, even when that is the mode's own
            // default: a later segment cannot be written without the earlier ones in front of it,
            // and parse reads that placeholder back as the same value it stood in for.
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id();
        }
        if (!effectiveExits.equals(mode.defaultExits())) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id();
        }
        if (contents != PortalRoomContents.DEFAULT) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id();
        }
        if (effectiveCopies == PortalRoomCopies.DEFAULT) return mode.id();
        return mode.id() + SEPARATOR + effectiveCopies.id();
    }

    /** True when the Copies control applies at all — only Endless Repetition makes copies of a room. */
    public boolean copiesApply() {
        return mode.tilesWholeRoom();
    }

    /** True when the Exits control applies at all — only an endless room has anywhere to put one. */
    public boolean exitsApply() {
        return mode.exitsApply();
    }

    /**
     * What this room actually does about extra corridors: {@link #exits} where the control applies,
     * and the mode's own default where it does not.
     *
     * <p>Read this rather than {@link #exits} anywhere the answer drives block writes. A room whose
     * walls were changed from Endless Repetition to Bedrock Lock still carries whatever Exits value
     * it had, and honouring it would stamp corridors into a sealed room that has no copies for them
     * to stand in.</p>
     */
    public PortalRoomExits effectiveExits() {
        return exitsApply() ? exits : mode.defaultExits();
    }

    /**
     * The same settings at a different wall mode.
     *
     * <p><b>Exits is re-derived when it was only ever the old mode's default.</b> Switching the walls
     * from Endless Repetition to Endless Open should land on Endless Open's answer — no corridors —
     * rather than carry across a value the author never chose, and the only way to tell a choice from
     * an inherited default is that the inherited one still equals what it was inherited from. An
     * author who has actually set Exits keeps it across the switch.</p>
     */
    public PortalRoomSettings withMode(PortalRoomMode newMode) {
        boolean inherited = exits.equals(mode.defaultExits());
        return new PortalRoomSettings(newMode, copies, contents, inherited ? null : exits, books);
    }

    public PortalRoomSettings withCopies(PortalRoomCopies newCopies) {
        return new PortalRoomSettings(mode, newCopies, contents, exits, books);
    }

    public PortalRoomSettings withContents(PortalRoomContents newContents) {
        return new PortalRoomSettings(mode, copies, newContents, exits, books);
    }

    public PortalRoomSettings withExits(PortalRoomExits newExits) {
        return new PortalRoomSettings(mode, copies, contents, newExits, books);
    }

    public PortalRoomSettings withBooks(PortalRoomBooks newBooks) {
        return new PortalRoomSettings(mode, copies, contents, exits, newBooks);
    }
}
