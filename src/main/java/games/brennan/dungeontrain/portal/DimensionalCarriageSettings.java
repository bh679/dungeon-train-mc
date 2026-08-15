package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

/**
 * Everything a {@code dimensional_carriage} variant says about its own boundary: what it does at its walls,
 * — for {@link DimensionalCarriageMode#ENDLESS_REPETITION} — whether the copies it makes are identical or
 * rolled afresh, whether the room is furnished from the ordinary contents pool, and — for either
 * endless mode — how many extra ways back to the train it scatters through its copies.
 *
 * <h2>All four live in the one {@code mode} tag</h2>
 * <p>On disk that reads {@code "mode": "endless_repetition/dynamic/fit/random:12"}, or just
 * {@code "mode": "endless_repetition"} when the three trailing settings are at their defaults.
 * {@code TemplateMeta.mode} is documented as an <i>opaque per-kind tag</i> — what it contains is the
 * owning kind's business — so encoding several settings in it is exactly what that field is for, and
 * it keeps a record shared by carriages and contents from growing fields only dimensional carriages will ever
 * read.</p>
 *
 * <p>They are still separate controls in the editor: a Walls row, a Contents row, and — when the
 * walls repeat — a Copies row and an Exits row. Only the storage is shared.</p>
 *
 * <p><b>Trailing segments are optional on the way in.</b> Every tag written before Contents or Exits
 * existed has one, two or three segments and still parses, to a room that behaves exactly as it
 * did.</p>
 *
 * <h2>Exits is the one setting whose default depends on another</h2>
 * <p>An absent Exits segment does not mean "off", it means "whatever this mode wants" — see
 * {@link DimensionalCarriageMode#defaultExits}. That is what lets the shipped {@code labrynth} room, tagged
 * {@code endless_repetition/dynamic} long before any of this existed, pick up its extra corridors
 * without anybody editing the weights file. It is also why {@link #withMode} has to re-derive: a
 * value that was only ever the old mode's default is not a choice the author made.</p>
 *
 * @param mode     what the room does at its walls
 * @param copies   what its copies are, when it makes any
 * @param contents whether it is furnished from the contents pool, and how
 * @param exits    how many extra corridors back to the train it lays, and how far apart
 */
public record DimensionalCarriageSettings(DimensionalCarriageMode mode, DimensionalCarriageCopies copies,
                                 DimensionalCarriageContents contents, DimensionalCarriageExits exits) {

    /** Separates the mode from the settings that follow it in the stored tag. */
    private static final String SEPARATOR = "/";

    public static final DimensionalCarriageSettings DEFAULT = new DimensionalCarriageSettings(
        DimensionalCarriageMode.DEFAULT, DimensionalCarriageCopies.DEFAULT, DimensionalCarriageContents.DEFAULT, null);

    public DimensionalCarriageSettings {
        if (mode == null) mode = DimensionalCarriageMode.DEFAULT;
        if (copies == null) copies = DimensionalCarriageCopies.DEFAULT;
        if (contents == null) contents = DimensionalCarriageContents.DEFAULT;
        // After the mode has been settled, never before: a null here means "this room said nothing
        // about its exits", and what that resolves to is the mode's business.
        if (exits == null) exits = mode.defaultExits();
    }

    /** The boundary settings alone, unfurnished — the pair this record was before Contents existed. */
    public DimensionalCarriageSettings(DimensionalCarriageMode mode, DimensionalCarriageCopies copies) {
        this(mode, copies, DimensionalCarriageContents.DEFAULT, null);
    }

    /** The three settings this record carried before Exits existed, at the mode's own default. */
    public DimensionalCarriageSettings(DimensionalCarriageMode mode, DimensionalCarriageCopies copies,
                              DimensionalCarriageContents contents) {
        this(mode, copies, contents, null);
    }

    /**
     * Read a stored tag. Total: anything unrecognised in any segment falls back to that segment's
     * default, so a hand-edited typo stamps a normal room rather than failing a pair's stamp. A tag
     * with fewer segments than there are settings — every tag written before a setting was added —
     * takes the default for the ones it does not name.
     */
    public static DimensionalCarriageSettings parse(String tag) {
        if (tag == null) return DEFAULT;
        String[] parts = tag.split(SEPARATOR, -1);
        String exitsSegment = segment(parts, 3);
        return new DimensionalCarriageSettings(
            DimensionalCarriageMode.parse(segment(parts, 0)),
            DimensionalCarriageCopies.parse(segment(parts, 1)),
            DimensionalCarriageContents.parse(segment(parts, 2)),
            // Null rather than DimensionalCarriageExits.parse(null): an absent segment must reach the
            // constructor as "unsaid" so the mode's default applies, not as a value of its own.
            exitsSegment == null ? null : DimensionalCarriageExits.parse(exitsSegment));
    }

    /** Segment {@code index} of a split tag, or null when the tag is shorter than that. */
    private static String segment(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    /** The settings a named room variant is authored with. */
    public static DimensionalCarriageSettings of(String roomName) {
        return parse(TrackVariantWeights.modeFor(TrackKind.DIMENSIONAL_CARRIAGE, roomName));
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
        DimensionalCarriageCopies effectiveCopies = copiesApply() ? copies : DimensionalCarriageCopies.DEFAULT;
        DimensionalCarriageExits effectiveExits = effectiveExits();
        if (!effectiveExits.equals(mode.defaultExits())) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id();
        }
        if (contents != DimensionalCarriageContents.DEFAULT) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id();
        }
        if (effectiveCopies == DimensionalCarriageCopies.DEFAULT) return mode.id();
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
    public DimensionalCarriageExits effectiveExits() {
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
    public DimensionalCarriageSettings withMode(DimensionalCarriageMode newMode) {
        boolean inherited = exits.equals(mode.defaultExits());
        return new DimensionalCarriageSettings(newMode, copies, contents, inherited ? null : exits);
    }

    public DimensionalCarriageSettings withCopies(DimensionalCarriageCopies newCopies) {
        return new DimensionalCarriageSettings(mode, newCopies, contents, exits);
    }

    public DimensionalCarriageSettings withContents(DimensionalCarriageContents newContents) {
        return new DimensionalCarriageSettings(mode, copies, newContents, exits);
    }

    public DimensionalCarriageSettings withExits(DimensionalCarriageExits newExits) {
        return new DimensionalCarriageSettings(mode, copies, contents, newExits);
    }
}
