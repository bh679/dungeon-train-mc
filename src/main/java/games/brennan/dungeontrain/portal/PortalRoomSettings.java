package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

/**
 * Everything a {@code portal_room} variant says about its own boundary: what it does at its walls,
 * — for {@link PortalRoomMode#ENDLESS_REPETITION} — whether the copies it makes are identical or
 * rolled afresh, and whether the room is furnished from the ordinary contents pool.
 *
 * <h2>All three live in the one {@code mode} tag</h2>
 * <p>On disk that reads {@code "mode": "endless_repetition/dynamic/fit"}, or just
 * {@code "mode": "endless_repetition"} when the two trailing settings are at their defaults.
 * {@code TemplateMeta.mode} is documented as an <i>opaque per-kind tag</i> — what it contains is the
 * owning kind's business — so encoding several settings in it is exactly what that field is for, and
 * it keeps a record shared by carriages and contents from growing fields only portal rooms will ever
 * read.</p>
 *
 * <p>They are still separate controls in the editor: a Walls row, a Contents row, and — when the
 * walls repeat — a Copies row. Only the storage is shared.</p>
 *
 * <p><b>Trailing segments are optional on the way in.</b> Every tag written before Contents existed
 * has one or two segments and still parses, to an {@link PortalRoomContents#OFF} room that behaves
 * exactly as it did.</p>
 *
 * @param mode     what the room does at its walls
 * @param copies   what its copies are, when it makes any
 * @param contents whether it is furnished from the contents pool, and how
 */
public record PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                                 PortalRoomContents contents) {

    /** Separates the mode from the settings that follow it in the stored tag. */
    private static final String SEPARATOR = "/";

    public static final PortalRoomSettings DEFAULT = new PortalRoomSettings(
        PortalRoomMode.DEFAULT, PortalRoomCopies.DEFAULT, PortalRoomContents.DEFAULT);

    public PortalRoomSettings {
        if (mode == null) mode = PortalRoomMode.DEFAULT;
        if (copies == null) copies = PortalRoomCopies.DEFAULT;
        if (contents == null) contents = PortalRoomContents.DEFAULT;
    }

    /** The boundary settings alone, unfurnished — the pair this record was before Contents existed. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies) {
        this(mode, copies, PortalRoomContents.DEFAULT);
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
        return new PortalRoomSettings(
            PortalRoomMode.parse(segment(parts, 0)),
            PortalRoomCopies.parse(segment(parts, 1)),
            PortalRoomContents.parse(segment(parts, 2)));
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
     * before Contents existed is re-written unchanged.</p>
     *
     * <p>Contents cannot be written without Copies in front of it: the segments are positional. When
     * Copies means nothing here its default id is written as the placeholder, which
     * {@link PortalRoomCopies#parse} reads back as the same default.</p>
     */
    public String toTag() {
        PortalRoomCopies effectiveCopies = copiesApply() ? copies : PortalRoomCopies.DEFAULT;
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

    public PortalRoomSettings withMode(PortalRoomMode newMode) {
        return new PortalRoomSettings(newMode, copies, contents);
    }

    public PortalRoomSettings withCopies(PortalRoomCopies newCopies) {
        return new PortalRoomSettings(mode, newCopies, contents);
    }

    public PortalRoomSettings withContents(PortalRoomContents newContents) {
        return new PortalRoomSettings(mode, copies, newContents);
    }
}
