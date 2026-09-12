package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderTemplateIdentity;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.variant.TrackKind;

/**
 * The build a Train Builder world is holding, as the roster key the editor screen selects by.
 *
 * <p>The editor answers "where am I standing" from the status packet its plot sweep pushes; the
 * builder never runs that sweep, so its bounds packet stands in. {@link BuilderTemplateIdentity}
 * turns those recorded strings into the {@code (kind, subKind, id)} the stores address, and this
 * maps that onto the key shapes the roster rows carry — carriages and contents keyed by id twice,
 * parts and tracks by {@code (kind token, name)}, rooms under the one portal kind. Pure, so the
 * shapes are pinned by a test rather than by opening every kind of build.</p>
 */
public final class BuilderStanding {

    private BuilderStanding() {}

    /**
     * The open build's key, or {@code null} for a draft (no name yet) and for a carriage group,
     * which has no roster row of its own.
     */
    public static VariantKey key(String modeId, String subTypeId, String partKindId,
                                 String trackKindId, String buildName, int parked) {
        BuilderTemplateIdentity.Identity id = BuilderTemplateIdentity
            .of(modeId, subTypeId, partKindId, trackKindId, buildName, parked)
            .orElse(null);
        return id == null ? null : keyOf(id.kind(), id.subKind(), id.id());
    }

    /** The roster key for a store identity; null where the roster has no row for the kind. */
    public static VariantKey keyOf(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return null;
        return switch (kind) {
            case CARRIAGE -> VariantKey.of(PlotCategory.CARRIAGES, id, id);
            case CONTENTS -> VariantKey.of(PlotCategory.CONTENTS, id, id);
            case PART -> subKind == null || subKind.isEmpty() ? null
                : VariantKey.of(PlotCategory.PARTS, subKind, id);
            case PORTAL_ROOM -> VariantKey.of(PlotCategory.PORTALS, TrackKind.PORTAL_ROOM.id(), id);
            case TRACK -> {
                TrackKind track = TrackKind.fromId(subKind);
                if (track == null) yield null;
                // A room is a track kind on disk and its own category on the screen.
                yield track == TrackKind.PORTAL_ROOM
                    ? VariantKey.of(PlotCategory.PORTALS, track.id(), id)
                    : VariantKey.of(PlotCategory.TRACKS, track.id(), id);
            }
            case CARRIAGE_GROUP -> null;
        };
    }
}
