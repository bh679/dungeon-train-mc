package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.track.variant.TrackKind;

/**
 * What a roster key asks the Train Builder to open — the inverse of
 * {@link BuilderRelayKinds#modeFor} and {@link BuilderRelayKinds#categoryIdFor}.
 *
 * <p>The builder's open packet names a mode, a store kind and an id, plus the part kind or track
 * kind that picks the id-space. A roster key names a category and a model, so each category maps
 * back: carriages and contents by id, parts by {@code (kind token, name)}, tracks by
 * {@code (TrackKind id, name)}, and a room — a track kind on disk — under Train Dimensions. The
 * parent link is dropped: a sub-variant has its own id or name, which is all a store needs.</p>
 *
 * @param modeId      the builder mode that authors this kind of template
 * @param kindId      the store kind ({@link BuilderPhotoPaths.Kind#id()})
 * @param id          the template id or name
 * @param partKindId  the part id-space, for parts; empty otherwise
 * @param trackKind   the track id-space, for tracks; null otherwise
 */
public record BuilderOpenTarget(String modeId, String kindId, String id, String partKindId,
                                TrackKind trackKind) {

    /** The open request for a key, or {@code null} for a category the builder cannot hold. */
    public static BuilderOpenTarget of(VariantKey key) {
        if (key == null || key.category() == null) return null;
        return switch (key.category()) {
            case CARRIAGES -> plain(BuilderPhotoPaths.Kind.CARRIAGE, key.modelId());
            case CONTENTS -> plain(BuilderPhotoPaths.Kind.CONTENTS, key.modelId());
            case PARTS -> key.modelName().isEmpty() ? null : new BuilderOpenTarget(
                BuilderMode.INSIDE_CARRIAGE.id(), BuilderPhotoPaths.Kind.PART.id(),
                key.modelName(), key.modelId(), null);
            case PORTALS -> key.modelName().isEmpty() ? null : plain(
                BuilderPhotoPaths.Kind.PORTAL_ROOM, key.modelName());
            case TRACKS -> {
                TrackKind kind = TrackKind.fromId(key.modelId());
                if (kind == null || key.modelName().isEmpty()) yield null;
                yield new BuilderOpenTarget(BuilderMode.TRACKS_TUNNELS.id(),
                    BuilderPhotoPaths.Kind.TRACK.id(), key.modelName(), "", kind);
            }
            case ARCHITECTURE -> null;
        };
    }

    private static BuilderOpenTarget plain(BuilderPhotoPaths.Kind kind, String id) {
        if (id == null || id.isEmpty()) return null;
        return new BuilderOpenTarget(BuilderRelayKinds.modeFor(kind).id(), kind.id(), id, "", null);
    }

    public boolean isTrack() {
        return trackKind != null;
    }
}
