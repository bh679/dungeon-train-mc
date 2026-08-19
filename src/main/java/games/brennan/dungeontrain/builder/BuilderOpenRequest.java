package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
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
 * @param kind      which store owns the template, and therefore where its photo lives
 * @param id        the template id within that store
 * @param partKind  which part is being opened; null for every kind but {@link BuilderPhotoPaths.Kind#PART},
 *                  where it is required because a part id is only unique within its kind
 * @param trackKind which track-side kind is being opened; null for every kind but
 *                  {@link BuilderPhotoPaths.Kind#TRACK}, and required there for the same reason —
 *                  {@code default} is a track tile, a pillar section, a tunnel and a staircase
 */
public record BuilderOpenRequest(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind,
                                 TrackKind trackKind) {

    /** The token {@link #subTypeToken} records for a portal room. */
    public static final String PORTAL_ROOM_SUB_TYPE = "portal_room";

    public BuilderOpenRequest {
        id = id == null ? "" : id;
    }

    /** The three-arg form, for the carriage-side kinds that have no track kind to carry. */
    public BuilderOpenRequest(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind) {
        this(kind, id, partKind, null);
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
     * A track template, named by its kind.
     *
     * <p>Separate from {@link #forSelection} because a track build has no
     * {@link BuilderNewOptions.SubType} to route on — the carriage sub types are the three things
     * you can author inside a carriage, and none of them is a rail.</p>
     */
    public static Optional<BuilderOpenRequest> forTrack(TrackKind trackKind, String id) {
        if (trackKind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BuilderOpenRequest(BuilderPhotoPaths.Kind.TRACK, id, null, trackKind));
    }

    /** Whether this is a track-side template rather than something inside a carriage. */
    public boolean isTrack() {
        return kind == BuilderPhotoPaths.Kind.TRACK;
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
            // A group records the same sub type as a single carriage: both are "the whole carriage",
            // and how many of them there are is the mode's answer, not the sub type's. What tells a
            // later Save the two apart is the number of build volumes it finds — see BuilderSave.
            case CARRIAGE, CARRIAGE_GROUP -> BuilderNewOptions.SubType.WHOLE_CARRIAGE.id();
            case CONTENTS -> BuilderNewOptions.SubType.CARRIAGE_ROOM.id();
            case PART -> BuilderNewOptions.SubType.PARTS.id();
            case PORTAL_ROOM -> PORTAL_ROOM_SUB_TYPE;
            // A track build records no sub type — see subType(), and BuilderWorldSetup's track arm,
            // which sets the field itself.
            case TRACK -> "";
        };
    }

    /**
     * Which sub type this request's template belongs to, or <b>null</b> for a portal room.
     *
     * <p>Read for one thing: how much train to park around what was opened
     * ({@code BuilderWorldLayout.parkedCarriages}). Null rather than a stand-in, because a room is
     * not stamped on a carriage at all — {@code applyOpen} takes the room path before any of that
     * arithmetic, and {@code parkedCarriages} already reads null as "the mode's own count". Naming
     * some sub type here instead would be a quiet claim that a room is a kind of carriage build.</p>
     *
     * <p>Not the same question as {@link #subTypeToken}, which is what gets recorded on the world so
     * a later Save knows which store to write.</p>
     *
     * <p>Null for a track template too, and for the same reason: the sub types describe what part of
     * a <em>carriage</em> a build is, and neither a rail nor a room is one. Callers gate on
     * {@link #isTrack()} / {@link #isPortalRoom()} first — {@code BuilderWorldSetup.applyOpen}
     * branches to both arms well before it asks this.</p>
     */
    public BuilderNewOptions.SubType subType() {
        return switch (kind) {
            case CARRIAGE, CARRIAGE_GROUP -> BuilderNewOptions.SubType.WHOLE_CARRIAGE;
            case CONTENTS -> BuilderNewOptions.SubType.CARRIAGE_ROOM;
            case PART -> BuilderNewOptions.SubType.PARTS;
            case TRACK, PORTAL_ROOM -> null;
        };
    }

    /**
     * The build a builder world is already holding, read back off the world, or empty when it is
     * holding nothing named.
     *
     * <p>Reopening a saved builder world re-runs the Open that put the build there
     * ({@code BuilderWorldSetup.reopen}), so that a world always comes back up as the scene its mode
     * describes rather than as whatever mixture the last session left in the blocks. This is the
     * inverse of the three writes that record it — {@code setBuilderSubType},
     * {@code setBuilderTrackKind} and {@code setBuilderName} — and has to stay their exact mirror:
     * the token it reads is the same one {@code BuilderSave} routes on, so a wrong answer here
     * reopens the world as the wrong kind of build.</p>
     *
     * <p>Empty for a draft (a New that was never saved has no name), which the caller answers by
     * standing the mode's bare scene back up instead.</p>
     */
    public static Optional<BuilderOpenRequest> fromWorldData(String name, String subTypeToken,
                                                             String partKindId,
                                                             String trackKindId) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        // A track build records no sub type — the kind is the field that names it — so it is asked
        // first, exactly as BuilderSave does before it reads the portal-room token.
        if (trackKindId != null && !trackKindId.isEmpty()) {
            return forTrack(TrackKind.fromId(trackKindId), name);
        }
        if (PORTAL_ROOM_SUB_TYPE.equals(subTypeToken)) {
            return Optional.of(forPortalRoom(name));
        }
        return BuilderNewOptions.SubType.fromId(subTypeToken)
                .flatMap(subType ->
                        forSelection(subType, name, CarriagePartKind.fromId(partKindId)));
    }

    /** Whether this request is for a portal room rather than something on a carriage. */
    public boolean isPortalRoom() {
        return kind == BuilderPhotoPaths.Kind.PORTAL_ROOM;
    }

    public String partKindId() {
        return partKind == null ? "" : partKind.id();
    }

    public String trackKindId() {
        return trackKind == null ? "" : trackKind.id();
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }
}
