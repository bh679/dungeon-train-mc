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

    /** The token {@link #subTypeToken} records for a portal room. */
    public static final String PORTAL_ROOM_SUB_TYPE = "portal_room";

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

    /**
     * A portal room, which no sub type names.
     *
     * <p>Its own factory rather than an arm of {@link #forSelection}, because that method reads a
     * {@link BuilderNewOptions.SubType} and Train Dimensions has none — the mode's Open arm maps
     * straight off the mode. See {@code BuilderOpenOptions.openSourceFor}.</p>
     */
    public static BuilderOpenRequest forPortalRoom(String id) {
        return new BuilderOpenRequest(BuilderPhotoPaths.Kind.PORTAL_ROOM, id, null);
    }

    /**
     * The sub-type token recorded on the world, so a later Save knows what it is looking at.
     *
     * <p>A token rather than a {@link BuilderNewOptions.SubType}, because a portal room is not one
     * and must not become one: that enum is the New screen's cycle control, so a fourth value would
     * offer "Portal Room" on a screen that cannot make one. The field on the world has always been a
     * free string, so the three readers that parse it — {@code BuilderSave.subTypeOf},
     * {@code BuilderSavePacket.kindOf}, {@code BuilderBoundsPacket} — each answer for this token
     * explicitly.</p>
     */
    public String subTypeToken() {
        return switch (kind) {
            case CARRIAGE -> BuilderNewOptions.SubType.WHOLE_CARRIAGE.id();
            case CONTENTS -> BuilderNewOptions.SubType.CARRIAGE_ROOM.id();
            case PART -> BuilderNewOptions.SubType.PARTS.id();
            case PORTAL_ROOM -> PORTAL_ROOM_SUB_TYPE;
        };
    }

    /** Whether this request is for a portal room rather than something on a carriage. */
    public boolean isPortalRoom() {
        return kind == BuilderPhotoPaths.Kind.PORTAL_ROOM;
    }

    public String partKindId() {
        return partKind == null ? "" : partKind.id();
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }
}
