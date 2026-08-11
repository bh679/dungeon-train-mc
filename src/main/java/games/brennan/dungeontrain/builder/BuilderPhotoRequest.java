package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.Optional;

/**
 * Which template a photo request is about.
 *
 * <p>Separate and pure because the answer differs per sub type in a way that's easy to get subtly
 * wrong: a Whole Carriage build photographs the <em>shell</em> it was stamped from, while a room or
 * a part photographs the thing that was picked. Point it at the wrong one and the picture lands
 * beside a template it isn't a picture of.</p>
 */
public record BuilderPhotoRequest(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind) {

    /**
     * What the New screen's selection just stamped, or empty when it named nothing on disk.
     *
     * @param shellId the carriage the world was parked with
     * @param picked  the contents id or part name; empty for a whole carriage, whose selection
     *                already resolved to {@code shellId}
     */
    public static Optional<BuilderPhotoRequest> forSelection(BuilderNewOptions.SubType subType,
                                                             String shellId,
                                                             String picked,
                                                             CarriagePartKind partKind) {
        if (subType == null) {
            return Optional.empty();
        }
        return switch (subType) {
            case WHOLE_CARRIAGE -> of(BuilderPhotoPaths.Kind.CARRIAGE, shellId, null);
            case CARRIAGE_ROOM -> of(BuilderPhotoPaths.Kind.CONTENTS, picked, null);
            case PARTS -> partKind == null
                    ? Optional.empty()
                    : of(BuilderPhotoPaths.Kind.PART, picked, partKind);
        };
    }

    private static Optional<BuilderPhotoRequest> of(BuilderPhotoPaths.Kind kind, String id,
                                                    CarriagePartKind partKind) {
        return id == null || id.isEmpty()
                ? Optional.empty()
                : Optional.of(new BuilderPhotoRequest(kind, id, partKind));
    }

    public String partKindId() {
        return partKind == null ? "" : partKind.id();
    }
}
