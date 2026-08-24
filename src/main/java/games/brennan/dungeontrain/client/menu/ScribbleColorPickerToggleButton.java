package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.Consumer;

/**
 * Tiny palette button on the book-writing screen that shows or hides the Scribble mod's
 * colour-swatch grid — see {@link games.brennan.dungeontrain.client.ScribbleColorPickerToggle},
 * which positions it and owns the swatches it toggles.
 *
 * <p>Drawn entirely with {@link GuiGraphics#fill} rather than blitted from a sprite. Two reasons:
 * it needs no new PNG or atlas entry for a 10px control, and Scribble's own swatches are
 * {@code fill()}-drawn squares, so a hand-drawn 2x2 mini-palette sits in the same visual language
 * as the grid it controls. The button dims to a flat grey when the picker is hidden, so its state
 * reads at a glance without a label the book GUI has no room for.</p>
 *
 * <p>Deliberately NOT a {@code CycleButton}: those render a full-width labelled option row, which
 * is right for the options screen and far too large for the margin of a book page.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ScribbleColorPickerToggleButton extends AbstractButton {

    /** Two Scribble swatches wide (8px each), so the button reads as part of the grid. */
    public static final int SIZE = 10;

    private static final Component SHOW_TOOLTIP =
            Component.translatable("gui.dungeontrain.scribble.color_picker.show");
    private static final Component HIDE_TOOLTIP =
            Component.translatable("gui.dungeontrain.scribble.color_picker.hide");
    private static final Component NARRATION =
            Component.translatable("gui.dungeontrain.scribble.color_picker.narration");

    private static final int BORDER_DARK = 0xFF000000;
    private static final int BORDER_LIGHT = 0xFFA0A0A0;
    private static final int HOVER_WASH = 0x33FFFFFF;

    /** The 2x2 mini-palette, in reading order: red, yellow / green, blue. */
    private static final int[] QUADRANTS = {0xFFFF5555, 0xFFFFFF55, 0xFF55FF55, 0xFF5555FF};

    /** Flat grey stand-in drawn in place of the palette while the picker is hidden. */
    private static final int QUADRANT_HIDDEN = 0xFF6E6E6E;

    private final Consumer<Boolean> onToggle;
    private boolean shown;

    /**
     * @param shown    current visibility of the swatch grid — sets the initial look
     * @param onToggle receives the NEW visibility when clicked; expected to both persist it and
     *                 apply it to the swatches
     */
    public ScribbleColorPickerToggleButton(int x, int y, boolean shown, Consumer<Boolean> onToggle) {
        super(x, y, SIZE, SIZE, NARRATION);
        this.shown = shown;
        this.onToggle = onToggle;
        setTooltip(Tooltip.create(shown ? HIDE_TOOLTIP : SHOW_TOOLTIP));
    }

    @Override
    public void onPress() {
        shown = !shown;
        setTooltip(Tooltip.create(shown ? HIDE_TOOLTIP : SHOW_TOOLTIP));
        onToggle.accept(shown);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + SIZE;
        int y1 = y0 + SIZE;

        // Light border on top + left, dark on bottom + right — matches PrefabSideTabButton's
        // extruded look, and reads against the book page's parchment background.
        g.fill(x0, y0, x1, y1, BORDER_DARK);
        g.fill(x0, y0, x1, y0 + 1, BORDER_LIGHT);
        g.fill(x0, y0, x0 + 1, y1, BORDER_LIGHT);

        // 2x2 palette inside the 1px border: each quadrant is (SIZE - 2) / 2 px.
        int inset = 1;
        int half = (SIZE - 2) / 2;
        for (int i = 0; i < QUADRANTS.length; i++) {
            int qx = x0 + inset + (i % 2) * half;
            int qy = y0 + inset + (i / 2) * half;
            g.fill(qx, qy, qx + half, qy + half, shown ? QUADRANTS[i] : QUADRANT_HIDDEN);
        }

        if (isHovered()) {
            g.fill(x0, y0, x1, y1, HOVER_WASH);
        }
    }
}
