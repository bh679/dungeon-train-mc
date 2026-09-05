package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The tile-preview stack, for screens outside this package.
 *
 * <p>{@link BuilderTileMeshCache}, {@link BuilderTileMesh} and {@link BuilderTileModelRenderer}
 * stay package-private: they are one machine with one cache, and this is the whole of what
 * another screen needs from it. The inventory-style editor screen draws its template tiles and
 * its rotating preview through here.</p>
 *
 * <p>Call {@link #beginFrame(int)} once per frame before any {@link #draw}; a tile whose mesh is
 * not ready yet answers false so the caller can draw its flat fallback for this frame.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderTilePreviews {

    private BuilderTilePreviews() {}

    /** Open the frame's bake budget — see {@link BuilderTileMeshCache#beginFrame(int)}. */
    public static void beginFrame(int bakeBudget) {
        BuilderTileMeshCache.beginFrame(bakeBudget);
    }

    /**
     * Draw the template's baked model, turned to {@code yaw} degrees and filling {@code fill} of
     * the cell, or answer false when there is nothing baked to draw yet or ever.
     */
    public static boolean draw(GuiGraphics g, BuilderPhotoPaths.Kind kind, String id,
                               CarriagePartKind partKind, TrackKind trackKind,
                               int x, int y, int w, int h, float yaw, float fill) {
        BuilderTileMesh mesh = BuilderTileMeshCache.meshFor(kind, id, partKind, trackKind);
        if (mesh == null) {
            return false;
        }
        BuilderTileModelRenderer.render(g, mesh, x, y, w, h, yaw, fill);
        return true;
    }

    /** Draw the template's photo, cover-cropped into the cell, or answer false when it has none. */
    public static boolean drawPhoto(GuiGraphics g, BuilderPhotoPaths.Kind kind, String id,
                                    CarriagePartKind partKind, TrackKind trackKind,
                                    int x, int y, int w, int h) {
        BuilderPhotoTextures.Photo photo = kind == null
                ? null
                : BuilderPhotoTextures.textureFor(kind, id, partKind, trackKind);
        if (photo == null) {
            return false;
        }
        BuilderTileArt.renderCover(g, photo.texture(), photo.width(), photo.height(),
                x, y, w, h, 1.0F);
        return true;
    }

    /** The template's data-sheet numbers, or null while its bake is still queued. */
    public static TemplateSummary summary(BuilderPhotoPaths.Kind kind, String id,
                                          CarriagePartKind partKind, TrackKind trackKind) {
        return BuilderTileMeshCache.summaryFor(kind, id, partKind, trackKind);
    }

    /** Drop every baked mesh — on the way out of a screen, as the Open grid does. */
    public static void clear() {
        BuilderTileMeshCache.clear();
    }
}
