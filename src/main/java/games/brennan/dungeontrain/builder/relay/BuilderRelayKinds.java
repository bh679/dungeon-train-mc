package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;

/**
 * How a Train Builder template kind is named on the relay.
 *
 * <p>Its own class, and pure, because two different things depend on getting this right and neither
 * can check the other: the upload writes the kind, and the relay refuses to lease anything that is not
 * a {@code carriage} into a train. A kind that mapped wrong would put a portal room in the pool as a
 * cart — so the mapping is stated once, here, and tested.</p>
 */
public final class BuilderRelayKinds {

    /** The only kind a train can be assembled from — see {@link #canJoinTheTrain}. */
    public static final String CARRIAGE = "carriage";
    /**
     * A whole run of carriages saved as one build.
     *
     * <p>Not submittable to the train <em>yet</em>: nothing places a group on a train, and the relay
     * only ever leases a {@code carriage}. It still uploads, because a builder's profile is the record
     * of what they have made and a group is the thing Train Outside actually authors.</p>
     */
    public static final String CARRIAGE_GROUP = "carriage_group";
    public static final String CONTENTS = "contents";
    public static final String PART = "part";
    public static final String TRACK = "track";
    public static final String PORTAL_ROOM = "portal_room";

    private BuilderRelayKinds() {}

    /** The relay's name for the store a build was written to. */
    public static String idOf(BuilderPhotoPaths.Kind kind) {
        if (kind == null) return CARRIAGE;
        return switch (kind) {
            case CARRIAGE -> CARRIAGE;
            case CARRIAGE_GROUP -> CARRIAGE_GROUP;
            case CONTENTS -> CONTENTS;
            case PART -> PART;
            case TRACK -> TRACK;
            case PORTAL_ROOM -> PORTAL_ROOM;
        };
    }

    /**
     * The store a relay kind string names — the exact inverse of {@link #idOf}.
     *
     * <p>Needed by the download path, which is handed a kind by the relay and has to decide which
     * store to write the template into. Stated here beside its inverse rather than as a second switch
     * at the point of use, for the same reason {@link #idOf} is: the two must stay each other's
     * mirror, and a pair in one file can be tested as a round trip.</p>
     *
     * @return null for a kind this build of the mod does not know, which the caller must refuse
     *         rather than guess at — filing a build in the wrong store puts it where nothing looks
     */
    public static BuilderPhotoPaths.Kind kindOf(String kindId) {
        if (kindId == null) return null;
        return switch (kindId) {
            case CARRIAGE -> BuilderPhotoPaths.Kind.CARRIAGE;
            case CARRIAGE_GROUP -> BuilderPhotoPaths.Kind.CARRIAGE_GROUP;
            case CONTENTS -> BuilderPhotoPaths.Kind.CONTENTS;
            case PART -> BuilderPhotoPaths.Kind.PART;
            case TRACK -> BuilderPhotoPaths.Kind.TRACK;
            case PORTAL_ROOM -> BuilderPhotoPaths.Kind.PORTAL_ROOM;
            default -> null;
        };
    }

    /**
     * The builder mode a template of this kind is edited in — which arm of the Train Builder has to
     * be standing before the thing can be opened.
     *
     * <p>Asked by the download path, and only there. Everywhere else the mode comes first and decides
     * what may be authored; a download arrives the other way round, holding a kind and needing the
     * mode that goes with it. The four arms are exhaustive: a carriage and a run of them are built
     * from outside, a room and a shell part from inside one, a rail in Tracks &amp; Tunnels, and a
     * portal room in Train Dimensions.</p>
     */
    public static BuilderMode modeFor(BuilderPhotoPaths.Kind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case CARRIAGE, CARRIAGE_GROUP -> BuilderMode.TRAIN_OUTSIDE;
            case CONTENTS, PART -> BuilderMode.INSIDE_CARRIAGE;
            case TRACK -> BuilderMode.TRACKS_TUNNELS;
            case PORTAL_ROOM -> BuilderMode.TRAIN_DIMENSIONS;
        };
    }

    /**
     * Whether a build of this kind can be submitted to the train.
     *
     * <p>Only a whole carriage. Everything else the builder authors — a room, a shell part, a length of
     * track, a portal room — is a piece of something rather than a thing a train slot can hold, so it
     * lives in its author's profile and stops there. The relay enforces the same rule on its side; this
     * is what keeps the game from offering a button that could only ever fail.</p>
     */
    public static boolean canJoinTheTrain(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CARRIAGE;
    }

    /** As {@link #canJoinTheTrain}, from the relay's own kind string (what a profile listing carries). */
    public static boolean canJoinTheTrain(String kindId) {
        return CARRIAGE.equals(kindId);
    }
}
