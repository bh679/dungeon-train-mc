package games.brennan.dungeontrain.builder.relay;

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
            case CONTENTS -> CONTENTS;
            case PART -> PART;
            case TRACK -> TRACK;
            case PORTAL_ROOM -> PORTAL_ROOM;
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
