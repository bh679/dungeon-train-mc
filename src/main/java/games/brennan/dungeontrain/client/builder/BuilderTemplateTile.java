package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One template in the Open screen's grid: its photo, with its name on a dark strip along the bottom.
 *
 * <p>Not a {@code Button}. The grid scrolls, so a widget with a fixed position would need moving
 * every frame and would still take clicks that had scrolled up behind the type controls; the screen
 * owns hit-testing through {@link BuilderTemplateGridLayout#indexAt} and this is what it draws. The
 * look is deliberately the picker tile's — {@link BuilderTileButton}'s label strip and hover
 * border — because a wall of pictures you choose from is the same gesture in both places.</p>
 *
 * <p>Templates that have never been photographed fall back to the mode's tile art, dimmed further
 * so a real photo is obviously a photo. A builder world writes the missing pictures as you browse
 * (see {@code BuilderPhotoPacket}'s {@code onlyIfMissing}), so a fresh library fills itself in.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTemplateTile {

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int LABEL_INACTIVE = 0xFF909090;

    private static final int IDLE_DIM = 0x40000000;
    /** No photo: the stand-in art is pushed further back so it doesn't read as this template's own. */
    private static final int FALLBACK_DIM = 0x80000000;
    /** A tile you can look at but not open. */
    private static final int INACTIVE_DIM = 0xA0000000;

    private static final int LABEL_STRIP_H = 14;

    private BuilderTemplateTile() {}

    /**
     * Draw one cell.
     *
     * @param photoKind which store to look the photo up in; null when this entry has no photo of its
     *                  own — a track template, or a Stage, which isn't a template at all
     * @param id        the bare template id, already untagged
     * @param label     what the strip reads; passed in rather than derived from {@code id} because a
     *                  saved build is shown under a heading so it can't be mistaken for a Stage
     * @param openable  false for a listed-but-not-yet-buildable template — drawn dimmed, with a grey
     *                  label and no hover border, so it reads as "shown, not offered"
     */
    static void render(GuiGraphics g, BuilderMode mode, boolean modeArtAvailable,
                       BuilderPhotoPaths.Kind photoKind, String id, CarriagePartKind partKind,
                       Component label,
                       int x, int y, int width, int height, boolean hovered, boolean openable) {
        BuilderPhotoTextures.Photo photo = photoKind == null
                ? null
                : BuilderPhotoTextures.textureFor(photoKind, id, partKind);

        if (photo != null) {
            BuilderTileArt.renderCover(g, photo.texture(), photo.width(), photo.height(),
                    x, y, width, height, 1.0F);
        } else {
            BuilderTileArt.render(g, mode, modeArtAvailable, x, y, width, height, 1.0F);
            g.fill(x, y, x + width, y + height, FALLBACK_DIM);
        }

        boolean highlight = hovered && openable;
        if (!openable) {
            g.fill(x, y, x + width, y + height, INACTIVE_DIM);
        } else if (!highlight) {
            // Dim until hovered, so the focused option pops out of the wall.
            g.fill(x, y, x + width, y + height, IDLE_DIM);
        }
        g.renderOutline(x, y, width, height, highlight ? BORDER_HOVER : BORDER_IDLE);

        int stripTop = y + height - LABEL_STRIP_H;
        g.fill(x + 1, stripTop, x + width - 1, y + height - 1, LABEL_STRIP_BG);
        Minecraft mc = Minecraft.getInstance();
        g.drawCenteredString(mc.font, label, x + width / 2,
                stripTop + (LABEL_STRIP_H - mc.font.lineHeight) / 2,
                openable ? LABEL_COLOUR : LABEL_INACTIVE);
    }
}
