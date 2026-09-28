package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * <p>What fills the picture, in order of preference: a <b>3D model</b> built from the template's own
 * blocks ({@link BuilderTileMeshCache}), which is framed to the build and cannot go stale; failing
 * that its <b>photo</b>, which is what a tile still waiting for its turn to bake shows, and what a
 * template with no readable blocks keeps forever; failing that the mode's <b>tile art</b>, dimmed
 * further so a real preview is obviously a real preview.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTemplateTile {

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int LABEL_INACTIVE = 0xFF909090;

    /** The ground a 3D model stands against, in place of a photo. */
    private static final int MODEL_BACKDROP = 0xFF14161C;

    private static final int IDLE_DIM = 0x40000000;
    /** No photo: the stand-in art is pushed further back so it doesn't read as this template's own. */
    private static final int FALLBACK_DIM = 0x80000000;
    /** A tile you can look at but not open. */
    private static final int INACTIVE_DIM = 0xA0000000;

    private static final int LABEL_STRIP_H = BuilderTemplateGridLayout.LABEL_STRIP_H;

    /** The drill-in button: a chip in the picture's bottom-right corner. */
    private static final int MORE_BG = 0xC0101010;
    private static final int MORE_BG_HOVER = 0xF0303030;
    private static final int MORE_BORDER = 0xFF000000;
    private static final int MORE_BORDER_HOVER = 0xFFFFFFFF;
    /**
     * Guillemet rather than a texture: the builder art is authored screenshots, and adding a GUI
     * sprite sheet for one 12px glyph would be a lot of machinery for an arrow. Latin-1, so the
     * font's unicode fallback covers it in every shipped locale.
     */
    private static final String MORE_GLYPH = "»";

    /** Breathing room either side of a caption, so a fitted one doesn't touch its own border. */
    private static final int LABEL_PADDING = 6;

    private static final String ELLIPSIS = "…";

    /** Padding between the state badge and the two cell edges it sits in the corner of. */
    private static final int BADGE_INSET = 3;

    /**
     * The favourite star, in four states.
     *
     * <p>Hollow when the build is not starred, filled when it is, and each swapping to a brighter
     * version while the pointer is over it — the padlock precedent from the book vote page, where the
     * hover state answers "what does this do" before the thing is pressed rather than only after.</p>
     */
    private static final ResourceLocation STAR_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star");
    private static final ResourceLocation STAR_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_highlighted");
    private static final ResourceLocation STAR_FILLED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_filled");
    private static final ResourceLocation STAR_FILLED_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_filled_highlighted");

    /** A backing plate under the star, so a hollow one reads against a pale build behind it. */
    private static final int STAR_BG = 0x90101010;
    private static final int STAR_BG_HOVER = 0xC0303030;

    private BuilderTemplateTile() {}

    /**
     * A caption cut to fit its cell.
     *
     * <p>Centred text draws from the middle outwards, so an over-long one does not simply overflow its
     * tile — it grows in BOTH directions and lands on top of the captions either side of it, which is
     * how three names end up reading as one run-on string. Nothing clipped it before because every
     * caption was a short template name; a caption that also carries a state is routinely wider than
     * the cell.</p>
     *
     * <p>Cut rather than scissored: a scissor would slice a glyph in half at the tile edge and leave
     * no sign that anything was missing, where an ellipsis says so.</p>
     */
    private static Component fit(Font font, Component label, int maxWidth) {
        String text = label.getString();
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return label;
        int room = maxWidth - font.width(ELLIPSIS);
        if (room <= 0) return Component.literal(ELLIPSIS);
        return Component.literal(font.plainSubstrByWidth(text, room) + ELLIPSIS);
    }

    /**
     * Draw one cell.
     *
     * @param photoKind which store to look the photo up in; null when this entry has no photo of its
     *                  own — a Stage or a track kind, neither of which is a template at all
     * @param id        the bare template id, already untagged
     * @param label     what the strip reads; passed in rather than derived from {@code id} because a
     *                  saved build is shown under a heading so it can't be mistaken for a Stage
     * @param openable  false for a listed-but-not-yet-buildable template — drawn dimmed, with a grey
     *                  label and no hover border, so it reads as "shown, not offered"
     * @param yaw       degrees the 3D model is turned to; ignored when there isn't one
     */
    static void render(GuiGraphics g, BuilderMode mode, boolean modeArtAvailable,
                       BuilderPhotoPaths.Kind photoKind, String id, CarriagePartKind partKind,
                       TrackKind trackKind, Component label,
                       int x, int y, int width, int height, boolean hovered, boolean openable,
                       float yaw) {
        render(g, mode, modeArtAvailable, photoKind, id, partKind, trackKind, label,
                x, y, width, height, hovered, openable, yaw, null, 0);
    }

    /**
     * As above, badged with a submission state.
     *
     * <p>Only My Builds passes one: everywhere else a tile is a thing you might open, and its border
     * says one thing — whether you are pointing at it. There a tile also has a STATE, and the state is
     * what the screen is for, so it gets a coloured band and an icon in the corner. {@code null}
     * means no state, and draws exactly what every other grid draws.</p>
     *
     * @param badgeSize the icon's edge in pixels, from the grid that laid this cell out — geometry
     *                  the layout owns rather than the renderer, so the two can't disagree about
     *                  where the corner is
     */
    static void render(GuiGraphics g, BuilderMode mode, boolean modeArtAvailable,
                       BuilderPhotoPaths.Kind photoKind, String id, CarriagePartKind partKind,
                       TrackKind trackKind, Component label,
                       int x, int y, int width, int height, boolean hovered, boolean openable,
                       float yaw, BuilderReviewBadge badge, int badgeSize) {
        BuilderTileMesh mesh = BuilderTileMeshCache.meshFor(photoKind, id, partKind, trackKind);

        if (mesh != null) {
            // A flat ground behind the model: the mode art under a 3D build is visual noise, and a
            // build needs something to sit against or it reads as cut out.
            g.fill(x, y, x + width, y + height, MODEL_BACKDROP);
            // Framed in the picture, not the whole cell: the label strip is opaque and painted over
            // the bottom afterwards, so a model centred on the cell would sit visibly low behind it.
            BuilderTileModelRenderer.render(g, mesh, x, y, width, height - LABEL_STRIP_H, yaw);
        } else {
            renderFlat(g, mode, modeArtAvailable, photoKind, id, partKind, trackKind,
                    x, y, width, height);
        }

        boolean highlight = hovered && openable;
        if (!openable) {
            g.fill(x, y, x + width, y + height, INACTIVE_DIM);
        } else if (!highlight) {
            // Dim until hovered, so the focused option pops out of the wall.
            g.fill(x, y, x + width, y + height, IDLE_DIM);
        }
        if (badge == null) {
            g.renderOutline(x, y, width, height, highlight ? BORDER_HOVER : BORDER_IDLE);
        } else {
            // Two rings. The outer one is the ordinary hover border, so pointing at a tile reads the
            // same here as anywhere; the inner one is the state, and it stays put underneath — a
            // selected build must not lose the only mark saying where it stands.
            g.renderOutline(x, y, width, height, highlight ? BORDER_HOVER : badge.borderColour());
            g.renderOutline(x + 1, y + 1, width - 2, height - 2, badge.borderColour());
            // Top-right: the drill-in chip has the bottom-right, and a badge that moved to dodge it
            // would be a badge whose position meant something it doesn't.
            g.blitSprite(badge.icon(), x + width - badgeSize - BADGE_INSET, y + BADGE_INSET,
                    badgeSize, badgeSize);
        }

        int stripTop = y + height - LABEL_STRIP_H;
        g.fill(x + 1, stripTop, x + width - 1, y + height - 1, LABEL_STRIP_BG);
        Minecraft mc = Minecraft.getInstance();
        g.drawCenteredString(mc.font, fit(mc.font, label, width - LABEL_PADDING), x + width / 2,
                stripTop + (LABEL_STRIP_H - mc.font.lineHeight) / 2,
                openable ? LABEL_COLOUR : LABEL_INACTIVE);
    }

    /**
     * The picture when there is no model to draw: this template's photo, or the mode's art.
     *
     * <p>Both of these were the whole story before 3D previews. They still cover the cases a model
     * can't: a tile whose bake hasn't come up in the queue yet, a template whose blocks won't read,
     * and a category tile that has no template of its own at all.</p>
     */
    private static void renderFlat(GuiGraphics g, BuilderMode mode, boolean modeArtAvailable,
                                   BuilderPhotoPaths.Kind photoKind, String id,
                                   CarriagePartKind partKind, TrackKind trackKind,
                                   int x, int y, int width, int height) {
        BuilderPhotoTextures.Photo photo = photoKind == null
                ? null
                : BuilderPhotoTextures.textureFor(photoKind, id, partKind, trackKind);

        if (photo != null) {
            BuilderTileArt.renderCover(g, photo.texture(), photo.width(), photo.height(),
                    x, y, width, height, 1.0F);
        } else {
            BuilderTileArt.render(g, mode, modeArtAvailable, x, y, width, height, 1.0F);
            g.fill(x, y, x + width, y + height, FALLBACK_DIM);
        }
    }

    /**
     * The favourite star, in a cell's top-left corner.
     *
     * <p>Drawn as a separate call after the cell, exactly as {@link #renderMore} is and for the same
     * reason: it is a second hit target inside one cell, and only the caller knows which entries can
     * carry one. Its geometry comes from {@link BuilderTemplateGridLayout}, so what is drawn here and
     * what a click tests cannot disagree.</p>
     *
     * @param favourite whether this build is starred — which fills the star rather than outlining it
     * @param hovered   whether the pointer is on the STAR, not merely on the cell around it
     */
    static void renderStar(GuiGraphics g, int x, int y, int size, boolean favourite, boolean hovered) {
        g.fill(x, y, x + size, y + size, hovered ? STAR_BG_HOVER : STAR_BG);
        ResourceLocation sprite = favourite
                ? (hovered ? STAR_FILLED_HIGHLIGHTED_SPRITE : STAR_FILLED_SPRITE)
                : (hovered ? STAR_HIGHLIGHTED_SPRITE : STAR_SPRITE);
        g.blitSprite(sprite, x, y, size, size);
    }

    /**
     * The drill-in button, for a cell that stands for a group of sub-variants.
     *
     * <p>Drawn as a separate call after the cell so the caller — which is the only thing that knows
     * which entries are groups — decides where it appears, and so the two hit targets inside one cell
     * stay visibly distinct: the picture opens the group's own template, this opens what's under it.</p>
     */
    static void renderMore(GuiGraphics g, int x, int y, int size, boolean hovered) {
        g.fill(x, y, x + size, y + size, hovered ? MORE_BG_HOVER : MORE_BG);
        g.renderOutline(x, y, size, size, hovered ? MORE_BORDER_HOVER : MORE_BORDER);
        Minecraft mc = Minecraft.getInstance();
        int textWidth = mc.font.width(MORE_GLYPH);
        g.drawString(mc.font, MORE_GLYPH,
                x + (size - textWidth) / 2 + 1,
                y + (size - mc.font.lineHeight) / 2 + 1,
                LABEL_COLOUR, false);
    }
}
