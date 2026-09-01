package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.variant.TrackKind;

/**
 * How a Train Builder template kind is named on the relay.
 *
 * <p>Its own class, and pure, because two different things depend on getting this right and neither
 * can check the other: the upload writes the kind, and the relay refuses to lease anything that is not
 * a {@code carriage} into a train. A kind that mapped wrong would put a portal room in the pool as a
 * cart — so the mapping is stated once, here, and tested.</p>
 *
 * <p>Two different questions are asked of a kind, and keeping them apart is the point of the two
 * predicates below. {@link #canSubmitForReview} asks whether a build may be offered to the operator
 * at all — every kind may, because a room and a length of track are things a person can look at and
 * accept. {@link #canJoinTheTrain} asks the narrower thing: whether a train slot can hold it. Only a
 * carriage can, and the relay's lease says so in SQL literals, so the two can never be conflated into
 * one flag without one of them quietly becoming wrong.</p>
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
     * Whether a build of this kind can be offered to the operator — what My Builds' Submit button does.
     *
     * <p>Every kind can. Submitting is a request for a person to look at a build and say yes or no to
     * it, and a carriage room, a shell part, a length of track and a portal room are all things a
     * person can answer that about; only what happens <em>after</em> a yes differs by kind. The relay
     * agrees: publishing never reads the kind, and the review verdict it records is the same column
     * whatever was submitted.</p>
     *
     * <p>Deliberately not {@link #canJoinTheTrain}. A build being reviewable and a train slot being
     * able to hold it are two different questions with two different answers for five of the six
     * kinds, and the one flag that used to answer both is what left a portal room with a dead
     * button.</p>
     */
    public static boolean canSubmitForReview(BuilderPhotoPaths.Kind kind) {
        return kind != null;
    }

    /**
     * As above, from the relay's own kind string (what a profile listing carries).
     *
     * <p>A kind this build of the mod does not know is refused rather than guessed at, the same way
     * {@link #kindOf} refuses one: a newer relay naming a kind this version has never heard of is a
     * build this version cannot say anything true about.</p>
     */
    public static boolean canSubmitForReview(String kindId) {
        return kindOf(kindId) != null;
    }

    /**
     * Whether a train slot can hold a build of this kind.
     *
     * <p>Only a whole carriage. Everything else the builder authors — a room, a shell part, a length of
     * track, a portal room — is a piece of something rather than a thing a train slot can hold. The
     * relay enforces the same rule on its side, in the lease query's literals, so this is a statement
     * about what the pool will serve rather than a preference either side could relax alone.</p>
     *
     * <p>No longer the submit gate — see {@link #canSubmitForReview}. What is still asked of it is
     * what only a carriage has: the stage that decides which stretch of line it belongs to.</p>
     */
    public static boolean canJoinTheTrain(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CARRIAGE;
    }

    /** As {@link #canJoinTheTrain}, from the relay's own kind string (what a profile listing carries). */
    public static boolean canJoinTheTrain(String kindId) {
        return CARRIAGE.equals(kindId);
    }

    /**
     * The in-world editor category a template of this kind is edited in, or {@code null} when the
     * editor has no home for it.
     *
     * <p>Here rather than beside the enter commands in {@code EditorTemplateJump} because both sides
     * ask it: the client, to decide whether the jump has to switch category at all, and the server,
     * to look the build up in the dirty scan's per-category set before a download writes over it.
     * A second copy would be a mapping that could drift.</p>
     *
     * <p>Null for a carriage group: a run of carriages is authored in the Train Builder and the
     * editor has no category that holds one.</p>
     */
    public static String categoryIdFor(BuilderPhotoPaths.Kind kind, String subKind) {
        if (kind == null) return null;
        return switch (kind) {
            // Parts are stamped as part of the carriages plots — PlotCategory.PARTS.owner() says so.
            case CARRIAGE, PART -> PlotCategory.CARRIAGES.id();
            case CONTENTS -> PlotCategory.CONTENTS.id();
            case TRACK -> TrackKind.PORTAL_ROOM == TrackKind.fromId(subKind)
                    ? PlotCategory.PORTALS.id()
                    : PlotCategory.TRACKS.id();
            case PORTAL_ROOM -> PlotCategory.PORTALS.id();
            case CARRIAGE_GROUP -> null;
        };
    }
}
