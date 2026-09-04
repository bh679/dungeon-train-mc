package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.client.builder.BuilderTilePreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.gui.GuiGraphics;

/**
 * How a template is looked up for its picture: the same four things that find its photo.
 *
 * @param kind      which store owns it
 * @param id        the bare id, or the variant name for kinds that need one
 * @param partKind  for parts
 * @param trackKind for track-side kinds
 */
public record TemplateArt(BuilderPhotoPaths.Kind kind, String id, CarriagePartKind partKind, TrackKind trackKind) {

    /** The lookup for a key, or null when the category has no template art (architecture). */
    public static TemplateArt of(VariantKey key) {
        if (key == null || key.category() == null) return null;
        return switch (key.category()) {
            case CARRIAGES -> new TemplateArt(BuilderPhotoPaths.Kind.CARRIAGE, key.modelId(), null, null);
            case CONTENTS -> new TemplateArt(BuilderPhotoPaths.Kind.CONTENTS, key.modelId(), null, null);
            case PARTS -> {
                CarriagePartKind pk = CarriagePartKind.fromId(key.modelId());
                yield pk == null ? null : new TemplateArt(BuilderPhotoPaths.Kind.PART, key.modelName(), pk, null);
            }
            case TRACKS -> {
                TrackKind tk = TrackKind.fromId(key.modelId());
                yield tk == null ? null : new TemplateArt(BuilderPhotoPaths.Kind.TRACK, key.modelName(), null, tk);
            }
            case PORTALS -> new TemplateArt(BuilderPhotoPaths.Kind.PORTAL_ROOM, key.modelName(), null, null);
            case ARCHITECTURE -> null;
        };
    }

    /**
     * The lookup for one of the player's uploaded builds, or null for a kind this build of the mod
     * does not know.
     *
     * <p>A relay build names its store the same way a template does, so once the kind is resolved
     * it draws through exactly the same model and photo path as an editor tile.</p>
     */
    public static TemplateArt ofBuild(BuilderProfilePacket.Entry entry) {
        if (entry == null) return null;
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(entry.kind());
        if (kind == null) return null;
        CarriagePartKind partKind = kind == BuilderPhotoPaths.Kind.PART
            ? CarriagePartKind.fromId(entry.subKind()) : null;
        TrackKind trackKind = kind == BuilderPhotoPaths.Kind.TRACK
            ? TrackKind.fromId(entry.subKind()) : null;
        if (kind == BuilderPhotoPaths.Kind.PART && partKind == null) return null;
        if (kind == BuilderPhotoPaths.Kind.TRACK && trackKind == null) return null;
        return new TemplateArt(kind, entry.buildName(), partKind, trackKind);
    }

    /** Draw the baked model, or false when there is none yet or ever. */
    public boolean drawModel(GuiGraphics g, int x, int y, int w, int h, float yaw, float fill) {
        return BuilderTilePreviews.draw(g, kind, id, partKind, trackKind, x, y, w, h, yaw, fill);
    }

    /** Draw the photo, or false when there is none. */
    public boolean drawPhoto(GuiGraphics g, int x, int y, int w, int h) {
        return BuilderTilePreviews.drawPhoto(g, kind, id, partKind, trackKind, x, y, w, h);
    }

    /** The data-sheet numbers, or null while the bake is queued. */
    public TemplateSummary summary() {
        return BuilderTilePreviews.summary(kind, id, partKind, trackKind);
    }

    /** A hover-spin key: stable per template across re-layouts. */
    public String spinKey() {
        return kind.name() + ":" + (partKind == null ? "" : partKind.id()) + ":"
            + (trackKind == null ? "" : trackKind.id()) + ":" + id;
    }
}
