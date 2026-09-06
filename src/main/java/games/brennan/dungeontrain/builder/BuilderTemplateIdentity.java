package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.Optional;

/**
 * Which template a builder world is holding — the {@code (kind, subKind, id)} triple that names it
 * in the stores, the relay and {@link games.brennan.dungeontrain.builder.relay.BuildCredits}.
 *
 * <p>Pure, and stated in the world's own recorded strings rather than a {@code ServerLevel}, so
 * every branch is testable. The rule is {@code BuilderSave.saveInternal}'s dispatch and that method
 * remains its source: a track kind decides first, then the portal-room sub-type token, then a
 * Carriage Room seen from <em>outside</em> the wall (which names the carriage the room is in, not a
 * contents template), then parts, then a whole carriage — or a group, when more than one carriage
 * is parked. {@code BuilderSave} is not routed through here because its switch chooses a save
 * <em>method</em>, capture volume and all, of which the kind is only the label.</p>
 */
public final class BuilderTemplateIdentity {

    private BuilderTemplateIdentity() {}

    /** A template as the stores address it. {@code subKind} is empty for the flat id-spaces. */
    public record Identity(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        public Identity {
            subKind = subKind == null ? "" : subKind;
            id = id == null ? "" : id;
        }
    }

    /**
     * What the world is holding, or empty for a draft — a build with no name is not yet a template,
     * and every store here is addressed by name.
     *
     * @param modeId      the builder mode the world was created in
     * @param subTypeId   the sub-type token, including {@link BuilderOpenRequest#PORTAL_ROOM_SUB_TYPE}
     * @param partKindId  the part id-space, when the sub type is Parts
     * @param trackKindId the track id-space; set for exactly the track-side builds
     * @param buildName   what the build saves as; empty means an unnamed draft
     * @param parked      carriages standing on the track — more than one is a group
     */
    public static Optional<Identity> of(String modeId, String subTypeId, String partKindId,
                                        String trackKindId, String buildName, int parked) {
        if (buildName == null || buildName.isEmpty()) return Optional.empty();

        // A rail is not part of a carriage, so the sub type below has no reading of it. Branch first,
        // exactly as the save does.
        TrackKind trackKind = trackKindId == null || trackKindId.isEmpty()
                ? null : TrackKind.fromId(trackKindId);
        if (trackKind != null) {
            return Optional.of(new Identity(BuilderPhotoPaths.Kind.TRACK, trackKind.id(), buildName));
        }
        if (BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(subTypeId)) {
            return Optional.of(new Identity(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", buildName));
        }

        BuilderNewOptions.SubType subType = subTypeOf(subTypeId);
        BuilderMode mode = BuilderMode.fromId(modeId).orElse(null);
        return switch (subType) {
            // From outside the wall a Carriage Room names the carriage the room is in — the Open
            // screen browsed carriages there and handed one over.
            case CARRIAGE_ROOM -> Optional.of(mode == BuilderMode.TRAIN_OUTSIDE
                    ? new Identity(BuilderPhotoPaths.Kind.CARRIAGE, "", buildName)
                    : new Identity(BuilderPhotoPaths.Kind.CONTENTS, "", buildName));
            case PARTS -> partKindId == null || partKindId.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new Identity(BuilderPhotoPaths.Kind.PART, partKindId, buildName));
            // A stretch of train saved as one template keeps the composition; only Whole Carriage
            // parks more than one, which is what makes the count enough to tell them apart.
            case WHOLE_CARRIAGE -> Optional.of(parked > 1
                    ? new Identity(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", buildName)
                    : new Identity(BuilderPhotoPaths.Kind.CARRIAGE, "", buildName));
        };
    }

    /** An unrecognised token reads as a whole carriage — the same fallback the save makes. */
    private static BuilderNewOptions.SubType subTypeOf(String id) {
        for (BuilderNewOptions.SubType value : BuilderNewOptions.SubType.values()) {
            if (value.id().equals(id)) return value;
        }
        return BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    }
}
