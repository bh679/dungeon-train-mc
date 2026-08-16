package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One image tile on the {@link TrainBuilderScreen} picker: a screenshot of what you'd be
 * building, with the mode name on a dark strip along the bottom and a white border on hover.
 *
 * <p>The image itself — and the slate fallback for art that isn't in the repo yet — is
 * {@link BuilderTileArt}, shared with the New screen's mode row so both fail the same way.
 * Presence is checked in the constructor rather than per-frame because screens rebuild all
 * their widgets in {@code init()}, which also runs on a resource reload.</p>
 *
 * <p>The Open screen's mode strip ({@link BuilderModeStripLayout}) draws the same tile in two
 * further states — <b>uncaptioned</b>, for the thumbnails flanking the selection, and
 * <b>selected</b>, for the large one in the middle. Same widget rather than a lookalike, because
 * the picker and the strip are showing the identical four things and a builder should not have to
 * learn that twice.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileButton extends Button {

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int IDLE_DIM = 0x40000000;

    private static final int LABEL_STRIP_H = 16;

    private final BuilderMode mode;
    private final boolean textureAvailable;
    private final boolean captioned;
    private final boolean selected;

    BuilderTileButton(int x, int y, int width, int height, BuilderMode mode, OnPress onPress) {
        this(x, y, width, height, mode, true, false, onPress);
    }

    /**
     * @param captioned whether the mode's name is written across the bottom of the tile. An
     *                  uncaptioned tile gets it as a tooltip instead — small and wordless is the
     *                  point of a thumbnail, but a picture you can't name is a guess.
     * @param selected  whether this tile is the current choice. A selected tile is never dimmed and
     *                  keeps the bright border, so "chosen" reads differently from "under the
     *                  cursor" — on the strip, one tile is always both-or-neither otherwise.
     */
    BuilderTileButton(int x, int y, int width, int height, BuilderMode mode,
                      boolean captioned, boolean selected, OnPress onPress) {
        super(x, y, width, height, Component.translatable(mode.labelKey()), onPress, DEFAULT_NARRATION);
        this.mode = mode;
        this.captioned = captioned;
        this.selected = selected;
        this.textureAvailable = BuilderTileArt.isAvailable(mode);
        if (!captioned) {
            this.setTooltip(Tooltip.create(Component.translatable(mode.labelKey())));
        }
    }

    BuilderMode mode() {
        return mode;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = this.isHoveredOrFocused();

        boolean lit = hovered || selected;

        BuilderTileArt.render(g, mode, textureAvailable, x, y, w, h, this.alpha);

        // Dim the whole tile slightly until it's hovered, so the focused option pops.
        if (!lit) {
            g.fill(x, y, x + w, y + h, IDLE_DIM);
        }

        g.renderOutline(x, y, w, h, lit ? BORDER_HOVER : BORDER_IDLE);

        if (!captioned) {
            return;
        }
        int stripTop = y + h - LABEL_STRIP_H;
        g.fill(x + 1, stripTop, x + w - 1, y + h - 1, LABEL_STRIP_BG);
        Minecraft mc = Minecraft.getInstance();
        g.drawCenteredString(mc.font, this.getMessage(), x + w / 2,
                stripTop + (LABEL_STRIP_H - mc.font.lineHeight) / 2, LABEL_COLOUR);
    }
}
