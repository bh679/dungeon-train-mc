package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

/**
 * Everything a {@code portal_room} variant says about its own boundary: what it does at its walls,
 * and — for {@link PortalRoomMode#ENDLESS_REPETITION} — whether the copies it makes are identical or
 * rolled afresh.
 *
 * <h2>Both live in the one {@code mode} tag</h2>
 * <p>On disk that reads {@code "mode": "endless_repetition/dynamic"}, or just
 * {@code "mode": "endless_repetition"} when the sub-mode is at its default. {@code TemplateMeta.mode}
 * is documented as an <i>opaque per-kind tag</i> — what it contains is the owning kind's business —
 * so encoding two settings in it is exactly what that field is for, and it keeps a record shared by
 * carriages and contents from growing a second field only portal rooms will ever read.</p>
 *
 * <p>The two are still separate controls in the editor: a Walls row and, when the walls repeat, a
 * Copies row under it. Only the storage is shared.</p>
 *
 * @param mode   what the room does at its walls
 * @param copies what its copies are, when it makes any
 */
public record PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies) {

    /** Separates the mode from its sub-mode in the stored tag. */
    private static final String SEPARATOR = "/";

    public static final PortalRoomSettings DEFAULT =
        new PortalRoomSettings(PortalRoomMode.DEFAULT, PortalRoomCopies.DEFAULT);

    public PortalRoomSettings {
        if (mode == null) mode = PortalRoomMode.DEFAULT;
        if (copies == null) copies = PortalRoomCopies.DEFAULT;
    }

    /**
     * Read a stored tag. Total: anything unrecognised on either side falls back to that side's
     * default, so a hand-edited typo stamps a normal room rather than failing a pair's stamp.
     */
    public static PortalRoomSettings parse(String tag) {
        if (tag == null) return DEFAULT;
        int slash = tag.indexOf(SEPARATOR);
        if (slash < 0) return new PortalRoomSettings(PortalRoomMode.parse(tag), PortalRoomCopies.DEFAULT);
        return new PortalRoomSettings(
            PortalRoomMode.parse(tag.substring(0, slash)),
            PortalRoomCopies.parse(tag.substring(slash + 1)));
    }

    /** The settings a named room variant is authored with. */
    public static PortalRoomSettings of(String roomName) {
        return parse(TrackVariantWeights.modeFor(TrackKind.PORTAL_ROOM, roomName));
    }

    /**
     * The tag to store.
     *
     * <p>The sub-mode is only written when it would change something — it is at its default, or the
     * mode does not make copies at all — so a room that never repeats round-trips as the bare mode id
     * it always was.</p>
     */
    public String toTag() {
        if (!mode.tilesWholeRoom() || copies == PortalRoomCopies.DEFAULT) return mode.id();
        return mode.id() + SEPARATOR + copies.id();
    }

    /** True when the Copies control applies at all — only Endless Repetition makes copies of a room. */
    public boolean copiesApply() {
        return mode.tilesWholeRoom();
    }

    public PortalRoomSettings withMode(PortalRoomMode newMode) {
        return new PortalRoomSettings(newMode, copies);
    }

    public PortalRoomSettings withCopies(PortalRoomCopies newCopies) {
        return new PortalRoomSettings(mode, newCopies);
    }
}
