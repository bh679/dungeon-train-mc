package games.brennan.dungeontrain.builder;

/**
 * What the Train Builder's <b>Open</b> screen lists for a given selection.
 *
 * <p>The sibling of {@link BuilderNewOptions}, and pure for the same reason: the table that decides
 * what the grid shows is also the table the server reads when the pick comes back, so neither side
 * may need a client class to understand it.</p>
 *
 * <p>Open and New ask different questions of the same controls. New's picker answers "what should
 * this new thing start <em>from</em>?" — which is why its Whole Carriage arm lists <em>stages</em>,
 * and why its Carriage Room arm changes meaning depending on which side of the carriage wall you
 * came in from. Open answers "which existing file am I editing?", and a room is a room whichever
 * mode you reached it through. So the controls match the New/Save screen exactly while the lists
 * underneath them deliberately do not.</p>
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
        /** Part templates of the separately-chosen part kind. */
        PARTS,
        /** Track tile templates. Listed for reference only — see {@link #isOpenable}. */
        TRACK_TILES,
        /** Tunnel portal templates. Listed for reference only — see {@link #isOpenable}. */
        TUNNEL_PORTALS
    }

    /**
     * What this selection lists.
     *
     * <p>The two track modes have no sub type, so they map straight off the mode — Tracks &amp;
     * Tunnels to the track tiles, Train Dimensions to the portals behind them, matching the mapping
     * {@link BuilderMode} documents.</p>
     */
    public static OpenSource openSourceFor(BuilderMode mode, BuilderNewOptions.SubType subType) {
        if (!BuilderNewOptions.hasSubTypes(mode)) {
            return mode == BuilderMode.TRAIN_DIMENSIONS
                    ? OpenSource.TUNNEL_PORTALS
                    : OpenSource.TRACK_TILES;
        }
        return switch (subType) {
            case WHOLE_CARRIAGE -> OpenSource.STAGES;
            // Unlike New, this does not depend on the mode. New's outside arm lists carriages
            // because it is asking which carriage a *new* room will belong to; Open is naming the
            // room itself, and that id lives in one store either way.
            case CARRIAGE_ROOM -> OpenSource.CONTENTS;
            case PARTS -> OpenSource.PARTS;
        };
    }

    /**
     * Whether clicking a tile from this source can actually load it.
     *
     * <p>False for tracks and tunnels. Not an oversight and not a permission check — there is
     * genuinely nothing to open into: {@link BuilderWorldSetup#applyNew} stamps nothing when the
     * mode's {@code carriageCount()} is zero, {@link BuilderNewRequest} has no field that could name
     * a track kind or tunnel variant, and {@code BuilderSave} has no arm that would write one back.
     * Those three together are the builder editor those modes are still waiting on.</p>
     *
     * <p>They are still <em>listed</em>, because "this mode has eleven templates and you can't edit
     * them here yet" is a true and useful thing for the screen to say, and an empty grid would say
     * something false. When the loop lands, this method is the only thing that changes.</p>
     */
    public static boolean isOpenable(OpenSource source) {
        return source == OpenSource.STAGES
                || source == OpenSource.CONTENTS
                || source == OpenSource.PARTS;
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
     * <p>Null for the track sources too: nothing ever writes those photos, which is why
     * {@link BuilderPhotoPaths.Kind} deliberately has no entry to return.</p>
     */
    public static BuilderPhotoPaths.Kind photoKindFor(OpenSource source, String value) {
        return switch (source) {
            case STAGES -> isSavedBuild(value) ? BuilderPhotoPaths.Kind.CARRIAGE : null;
            case CONTENTS -> BuilderPhotoPaths.Kind.CONTENTS;
            case PARTS -> BuilderPhotoPaths.Kind.PART;
            case TRACK_TILES, TUNNEL_PORTALS -> null;
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
