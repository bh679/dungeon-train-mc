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
        /** Saved whole carriages, then the carriage shells that aren't already among them. */
        CARRIAGES,
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
            case WHOLE_CARRIAGE -> OpenSource.CARRIAGES;
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
        return source == OpenSource.CARRIAGES
                || source == OpenSource.CONTENTS
                || source == OpenSource.PARTS;
    }

    /** Convenience for the screen: whether anything in this mode/sub type can be opened. */
    public static boolean isOpenable(BuilderMode mode, BuilderNewOptions.SubType subType) {
        return isOpenable(openSourceFor(mode, subType));
    }

    /**
     * Which store a clicked id belongs to, for the photo lookup and the open request.
     *
     * <p>Null for the track sources: no photo is ever written for them, so
     * {@link BuilderPhotoPaths.Kind} deliberately has no entry to return.</p>
     */
    public static BuilderPhotoPaths.Kind photoKindFor(OpenSource source) {
        return switch (source) {
            case CARRIAGES -> BuilderPhotoPaths.Kind.CARRIAGE;
            case CONTENTS -> BuilderPhotoPaths.Kind.CONTENTS;
            case PARTS -> BuilderPhotoPaths.Kind.PART;
            case TRACK_TILES, TUNNEL_PORTALS -> null;
        };
    }

    /** Whether the part-kind control belongs on the screen for this selection. */
    public static boolean showsPartKind(BuilderMode mode, BuilderNewOptions.SubType subType) {
        return openSourceFor(mode, subType) == OpenSource.PARTS;
    }
}
