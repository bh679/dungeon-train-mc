package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.Optional;

/**
 * Which template the Train Builder is being asked to open.
 *
 * <p>Deliberately narrower than {@link BuilderNewRequest}: a kind, an id, and — for a part — which
 * kind of part it is. Nothing else. <b>New</b> has to answer "what should this new thing start
 * from?", which is a question with five sources, tagged strings and a shell to resolve.
 * <b>Open</b> asks "which of these existing files am I editing?", and that has exactly one
 * answer.</p>
 *
 * <p>That narrowness is the point rather than a simplification. Open names the template it is about
 * to point {@code builderName} at, so any ambiguity in the identity becomes a save over the wrong
 * file. There is no {@code whole:} tag to mis-tag here, no mode-dependent lookup deciding what a
 * bare id means, and no arm where an unresolved id quietly becomes something else — see
 * {@link BuilderWorldSetup#applyOpen}, whose contract is the opposite of {@code applyNew}'s.</p>
 *
 * @param kind     which store owns the template, and therefore where its photo lives
 * @param id       the template id within that store
 * @param partKind which part is being opened; null for every kind but {@link BuilderPhotoPaths.Kind#PART},
 *                 where it is required because a part id is only unique within its kind
 */
public record BuilderOpenRequest(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind) {

    public BuilderOpenRequest {
        id = id == null ? "" : id;
    }

    /**
     * The request for one grid cell, or empty when the selection can't name a template.
     *
     * <p>The sub type decides the store, mirroring {@code BuilderPhotoRequest.forSelection} so a
     * template and its photo are always looked up in the same place. A whole carriage is the one
     * that differs from New's reading of the same sub type: New's Whole Carriage picker lists
     * <em>stages</em> and only optionally a saved build, whereas Open can only ever be opening a
     * saved build.</p>
     */
    public static Optional<BuilderOpenRequest> forSelection(BuilderNewOptions.SubType subType,
                                                            String id,
                                                            CarriagePartKind partKind) {
        if (subType == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (subType) {
            case WHOLE_CARRIAGE -> Optional.of(
                    new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE, id, null));
            case CARRIAGE_ROOM -> Optional.of(
                    new BuilderOpenRequest(BuilderPhotoPaths.Kind.CONTENTS, id, null));
            // A part with no kind has no unique identity — `standard` is both a floor and a door —
            // so this is a missing request rather than one with a hole in it.
            case PARTS -> partKind == null
                    ? Optional.empty()
                    : Optional.of(new BuilderOpenRequest(BuilderPhotoPaths.Kind.PART, id, partKind));
        };
    }

    /** The sub type this request saves back as, so the server records what {@code BuilderSave} needs. */
    public BuilderNewOptions.SubType subType() {
        return switch (kind) {
            case CARRIAGE -> BuilderNewOptions.SubType.WHOLE_CARRIAGE;
            case CONTENTS -> BuilderNewOptions.SubType.CARRIAGE_ROOM;
            case PART -> BuilderNewOptions.SubType.PARTS;
        };
    }

    public String partKindId() {
        return partKind == null ? "" : partKind.id();
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }
}
