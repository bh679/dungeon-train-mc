package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.List;

/**
 * What the Train Builder's <b>Open</b> screen lists for a given selection.
 *
 * <p>The sibling of {@link BuilderNewOptions}, and pure for the same reason: the table that decides
 * what the grid shows is also the table the server reads when the pick comes back, so neither side
 * may need a client class to understand it.</p>
 *
 * <p>Open and New ask different questions of the same controls. New's picker answers "what should
 * this new thing start <em>from</em>?"; Open answers "which existing thing am I working on?". So the
 * controls match the New/Save screen exactly while the lists underneath them do not have to.</p>
 *
 * <p>Where they <em>do</em> agree is that a room means different things on either side of the
 * carriage wall, which is the one reading of the controls a builder already has. From inside, a
 * Carriage Room is the room — a contents template. From outside you cannot see a room at all; you
 * see carriages, so that arm lists the stretches of the game and the carriage variants under each,
 * mirroring {@code BuilderNewOptions.copySourceFor}'s mode split rather than contradicting it.</p>
 */
public final class BuilderOpenOptions {

    private BuilderOpenOptions() {}

    /** Which store the grid lists — and therefore what a clicked id means to the server. */
    public enum OpenSource {
        /**
         * The Stage presets, then every whole carriage already saved — the same two lists, in the
         * same order, that New's Whole Carriage picker offers. Values are tagged; read them back
         * with {@link BuilderNewOptions#parsePick}.
         */
        STAGES,
        /** Carriage contents templates, groups and their sub-variants alike. */
        CONTENTS,
        /**
         * The Stages, with every carriage variant underneath each of them — two levels, drilled into
         * the way a contents group is.
         *
         * <p>Every carriage under every stage, not the ones {@code CarriageWeights} links to it. A
         * stage link is a spawn-gating decision a builder makes about a finished carriage; browsing
         * is the step before that, and the shipped {@code templates/weights.json} links none of
         * them, so filtering would put an empty grid under every stage. What the stage does supply
         * is the parts overlay the carriage is shown with — see {@code BuilderWorldSetup.applyOpen}.</p>
         */
        CARRIAGES_BY_STAGE,
        /** Part templates of the separately-chosen part kind. */
        PARTS,
        /**
         * Track-side templates, under the separately-chosen {@link BuilderTrackGroup}.
         *
         * <p>Two levels wherever the group has more than one {@link games.brennan.dungeontrain.track.variant.TrackKind}
         * — the kinds first, then one kind's named templates — drilled into exactly as a contents
         * group is. A single-kind group has nothing to choose at the first level, so it skips it.</p>
         *
         * <p>Replaces the old {@code TRACK_TILES} / {@code TUNNEL_PORTALS} pair, which named two of
         * the eight kinds and left the other six unreachable.</p>
         */
        TRACK_KINDS,
        /**
         * The four {@code PortalRoomMode}s, with the portal rooms of each underneath — two levels,
         * drilled into the way a contents group is. A room one level in opens.
         *
         * <p>The mode leads because it is the coarsest thing about a room and the one a builder
         * decides first: it says what the room does at its own walls, so a Bedrock room and an
         * Endless one are different kinds of place rather than two skins of the same one. All four
         * are always shown, including the ones nothing is authored for yet — an absent tile would
         * say the mode doesn't exist, which is the one thing this screen must not say about a
         * choice the author still has.</p>
         */
        PORTAL_ROOMS
    }

    /**
     * What this selection lists.
     *
     * <p>Neither carriage-less mode has a sub type, so both map straight off the mode. Tracks &amp;
     * Tunnels goes to the track-side templates, and which of those it shows is the
     * {@link BuilderTrackGroup}'s business rather than this method's — see {@link #trackGroupsFor}.</p>
     *
     * <p>Train Dimensions goes to the rooms instead. It used to list the tunnel portal
     * <em>facades</em> — the arch the train drives through, which is a piece of track. What
     * {@link BuilderMode#TRAIN_DIMENSIONS} names is the room on the other side of it, and those did
     * not exist as authorable templates when this screen was written. They do now, so the arm lists
     * what it always said it listed.</p>
     */
    public static OpenSource openSourceFor(BuilderMode mode, BuilderNewOptions.SubType subType) {
        if (!BuilderNewOptions.hasSubTypes(mode)) {
            return mode == BuilderMode.TRAIN_DIMENSIONS
                    ? OpenSource.PORTAL_ROOMS
                    : OpenSource.TRACK_KINDS;
        }
        return switch (subType) {
            case WHOLE_CARRIAGE -> OpenSource.STAGES;
            // Mode-dependent, the same way New's is: from inside the wall a room is a room, from
            // outside it there is no room to point at — only the carriages a room would go in.
            case CARRIAGE_ROOM -> mode == BuilderMode.INSIDE_CARRIAGE
                    ? OpenSource.CONTENTS
                    : OpenSource.CARRIAGES_BY_STAGE;
            case PARTS -> OpenSource.PARTS;
        };
    }

    /**
     * Whether clicking a tile from this source can actually load it.
     *
     * <p>Now true of every source. It was false for the track sources, and then for portal rooms,
     * for as long as there was genuinely nothing to open into — no stamp arm, no request field that
     * could name one, no save arm — and this method was where that gap was recorded. Both sets have
     * since landed ({@link BuilderTrackPlot} / {@link BuilderOpenRequest#trackKind()} /
     * {@code BuilderSave.saveTrack}, and {@code openPortalRoom} / {@code savePortalRoom}), so the
     * exception went with them.</p>
     */
    public static boolean isOpenable(OpenSource source) {
        return true;
    }

    /**
     * Whether this source has a second level the grid can look inside.
     *
     * <p>Only half the question for {@link OpenSource#CONTENTS}, where it is the individual entry
     * that is or isn't a group; for {@link OpenSource#CARRIAGES_BY_STAGE} and
     * {@link OpenSource#PORTAL_ROOMS} every top-level entry is a heading — a stage, a room mode —
     * and so every one of them drills in. {@link OpenSource#TRACK_KINDS} is the fourth case again:
     * it drills in only when the chosen group has more than one kind, which is the group's question
     * and so is asked of {@link BuilderTrackGroup#isSingleKind()} rather than here. Here rather than
     * in the screen so the rule is testable without a client.</p>
     */
    public static boolean drillsIn(OpenSource source) {
        return source == OpenSource.CONTENTS
                || source == OpenSource.CARRIAGES_BY_STAGE
                || source == OpenSource.PORTAL_ROOMS
                || source == OpenSource.TRACK_KINDS;
    }

    /**
     * The groups the track modes offer, in menu order.
     *
     * <p>Tracks &amp; Tunnels authors the line and everything bolted to it, so it offers all four.
     * Train Dimensions is the rooms behind a portal, which is one kind of thing, so it offers no
     * choice at all — an empty list, and the screen shows no group row.</p>
     */
    public static List<BuilderTrackGroup> trackGroupsFor(BuilderMode mode) {
        return mode == BuilderMode.TRACKS_TUNNELS
                ? List.of(BuilderTrackGroup.values())
                : List.of();
    }

    /**
     * The kind a track mode lands on when no group has been chosen — what Train Dimensions always
     * shows, and what Tracks &amp; Tunnels shows before the builder touches the group control.
     */
    public static TrackKind defaultTrackKind(BuilderMode mode) {
        return mode == BuilderMode.TRAIN_DIMENSIONS
                ? TrackKind.TUNNEL_PORTAL
                : BuilderTrackGroup.TRACKS.soleKind();
    }

    /** Convenience for the screen: whether anything in this mode/sub type can be opened. */
    public static boolean isOpenable(BuilderMode mode, BuilderNewOptions.SubType subType) {
        return isOpenable(openSourceFor(mode, subType));
    }

    /**
     * Which store one grid entry's photo lives in, or null when that entry has no photo of its own.
     *
     * <p>Per entry rather than per source because the Whole Carriage list holds two kinds of thing.
     * A saved build is a template with a picture beside it; a <b>Stage is not a template at all</b> —
     * it names a stretch of the game, so there is no file to photograph and the tile falls back to
     * the mode art.</p>
     *
     */
    public static BuilderPhotoPaths.Kind photoKindFor(OpenSource source, String value) {
        return photoKindFor(source, value, false);
    }

    /**
     * As {@link #photoKindFor(OpenSource, String)}, for a grid that may be one level in.
     *
     * <p>{@link OpenSource#CARRIAGES_BY_STAGE} is the source where the level changes the answer, and
     * it changes it completely: at the top the entries are Stages, which are not templates and have
     * no photo, and one level in they are carriage templates that do. {@link OpenSource#TRACK_KINDS}
     * splits the same way when its group has a kind level — a {@code TrackKind} is a category, not a
     * file, so only the named templates under it have a picture.</p>
     */
    public static BuilderPhotoPaths.Kind photoKindFor(OpenSource source, String value,
                                                      boolean insideGroup) {
        return switch (source) {
            case STAGES -> isSavedBuild(value) ? BuilderPhotoPaths.Kind.CARRIAGE : null;
            case CONTENTS -> BuilderPhotoPaths.Kind.CONTENTS;
            case CARRIAGES_BY_STAGE -> insideGroup ? BuilderPhotoPaths.Kind.CARRIAGE : null;
            case PARTS -> BuilderPhotoPaths.Kind.PART;
            // Like CARRIAGES_BY_STAGE, the level changes the answer: a heading has no file to
            // photograph, and one level in the entries are templates that have one.
            case PORTAL_ROOMS -> insideGroup ? BuilderPhotoPaths.Kind.PORTAL_ROOM : null;
            case TRACK_KINDS -> insideGroup ? BuilderPhotoPaths.Kind.TRACK : null;
        };
    }

    /**
     * The bare template id behind a grid value.
     *
     * <p>Only the Whole Carriage list tags its values, because it is the only one showing two lists
     * at once — an id is unique within a store but not across them, and {@code quartz} really is both
     * a Stage and one of {@code maze}'s sub-variants.</p>
     */
    public static String bareId(OpenSource source, String value) {
        return source == OpenSource.STAGES
                ? BuilderNewOptions.parsePick(value).id()
                : (value == null ? "" : value);
    }

    /**
     * Whether this Whole Carriage entry is a saved build rather than a Stage preset.
     *
     * <p>The two do different work when clicked: a saved build is <em>opened</em> — loaded and named,
     * so Save writes back to it — while a Stage starts an unnamed draft shaped for that stretch of the
     * game, which is exactly what New does with the same pick. That difference is why a Stage tile
     * must never set {@code builderName}.</p>
     */
    public static boolean isSavedBuild(String value) {
        return BuilderNewOptions.parsePick(value).kind() == BuilderNewOptions.PickKind.WHOLE_CARRIAGE;
    }

    /** Whether the part-kind control belongs on the screen for this selection. */
    public static boolean showsPartKind(BuilderMode mode, BuilderNewOptions.SubType subType) {
        return openSourceFor(mode, subType) == OpenSource.PARTS;
    }
}
