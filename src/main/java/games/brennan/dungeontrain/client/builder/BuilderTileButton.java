package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One image tile on the {@link TrainBuilderScreen} picker: a screenshot of what you'd be
 * building, with the mode name on a dark strip along the bottom and a white border while it is
 * the picked one.
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

    private final BuilderMode mode;
    private final boolean textureAvailable;
    private final boolean captioned;
    private final boolean selected;
    private final boolean hoverLights;

    BuilderTileButton(int x, int y, int width, int height, BuilderMode mode, OnPress onPress) {
        this(x, y, width, height, mode, true, false, true, onPress);
    }

    /**
     * A tile on the {@link TrainBuilderScreen} picker: captioned, lit only while it is the
     * choice, and without a tooltip — the picker's detail column already shows the mode's
     * description, so nothing is left for a hover to say. No hover lighting either: on that
     * screen a tile is picked by clicking it, and the cursor passing over should not look like
     * the choice moving.
     */
    static BuilderTileButton pickerTile(int x, int y, int width, int height, BuilderMode mode,
                                        boolean selected, OnPress onPress) {
        return new BuilderTileButton(x, y, width, height, mode, true, selected, false, onPress);
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
        this(x, y, width, height, mode, captioned, selected, true, onPress);
    }

    /**
     * @param hoverLights whether the cursor resting on the tile lights it. Off for the picker,
     *                    where only the chosen tile is lit and there is no tooltip.
     */
    private BuilderTileButton(int x, int y, int width, int height, BuilderMode mode,
                              boolean captioned, boolean selected, boolean hoverLights, OnPress onPress) {
        super(x, y, width, height, Component.translatable(mode.labelKey()), onPress, DEFAULT_NARRATION);
        this.mode = mode;
        this.captioned = captioned;
        this.selected = selected;
        this.hoverLights = hoverLights;
        this.textureAvailable = BuilderTileArt.isAvailable(mode);
        // A captioned tile already says its name, so its tooltip can say what the mode is for; an
        // uncaptioned thumbnail has to spend the tooltip on the name itself.
        if (hoverLights) {
            this.setTooltip(Tooltip.create(Component.translatable(
                    captioned ? mode.descriptionKey() : mode.labelKey())));
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
        // On the strip the chosen tile stays lit, so "chosen" reads differently from "under the
        // cursor"; on the picker only the chosen tile is ever lit.
        boolean lit = selected || (hoverLights && this.isHoveredOrFocused());
        BuilderTileArt.renderTile(g, mode, textureAvailable, x, y, w, h, captioned, lit, this.alpha);
    }
}
