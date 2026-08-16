package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How many template tiles the Open screen puts in a row — a small numbered chip beside the type
 * controls.
 *
 * <p>The grid's column count was fixed at three, which is a good number and a bad only number: a
 * track kind with thirty variants under it is mostly scrolling, and three big tiles is the wrong
 * trade for someone who knows their own library by its photos. The block the tiles are laid into
 * stays the same width whichever count is picked, so this buys tiles-on-screen by making each tile
 * smaller rather than by spreading the grid across the window — the screen keeps its shape while
 * the thing being changed changes.</p>
 *
 * <p>Left-click for more, right-click for fewer. A pair of arrow cells would be the obvious shape
 * and there is no room for one beside the 200px control block, so the second mouse button carries
 * the second direction — the same trade, for the same reason, as {@link BuilderRoomSizeButton},
 * and stated in the tooltip since nothing about the chip shows it.</p>
 *
 * <p>The label is a bare digit rather than a grid glyph. {@link BuilderTemplateTile} picked its
 * {@code »} specifically because Latin-1 survives the font's unicode fallback in every shipped
 * locale; a box-drawing or maths glyph has no such guarantee, and digits do.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTilesPerRowButton extends Button {

    /** Wide enough for one digit with the usual button padding — see the range, which is 2..6. */
    static final int WIDTH = 22;

    private final int screenWidth;
    private final Runnable onChange;

    /**
     * @param screenWidth the screen's width in GUI pixels, for the clamp — a narrow window holds
     *                    fewer columns than the range allows, and the chip must not offer one it
     *                    cannot draw
     * @param onChange    run after the new count is persisted; the screen rebuilds itself on it
     */
    BuilderTilesPerRowButton(int x, int y, int height, int screenWidth, Runnable onChange) {
        super(x, y, WIDTH, height, Component.empty(),
                b -> step(screenWidth, +1, onChange), DEFAULT_NARRATION);
        this.screenWidth = screenWidth;
        this.onChange = onChange;
        setTooltip(Tooltip.create(Component.translatable(
                "gui.dungeontrain.builder.open.tiles_per_row.tooltip")));
    }

    /**
     * Right-click counts down.
     *
     * <p>Handled here rather than through the click handler because {@code AbstractButton} only ever
     * reports button 0 — it treats anything else as not a click at all, so without this the chip
     * would only ever count up.</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.visible && this.active && isMouseOver(mouseX, mouseY)) {
            step(screenWidth, -1, onChange);
            playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Step the count and persist it.
     *
     * <p>Steps from the <em>effective</em> count rather than the stored one, so the chip and the
     * grid can never disagree: on a window that saturates at four, the stored six reads as four
     * here, and clicking down from it goes to three rather than to a five that would draw
     * identically. The stored value is only overwritten when a click actually lands, so a count
     * squeezed out by a small window survives until the player touches it.</p>
     */
    private static void step(int screenWidth, int delta, Runnable onChange) {
        int current = effectiveColumns(screenWidth);
        int wanted = Math.max(BuilderTemplateGridLayout.MIN_COLUMNS,
                Math.min(BuilderTemplateGridLayout.maxColumnsFor(screenWidth), current + delta));
        if (wanted == current) {
            return;   // already at an end of the range — the chip just doesn't move
        }
        ClientDisplayConfig.setBuilderTilesPerRow(wanted);
        onChange.run();
    }

    /**
     * What the grid will actually lay out at this width: the player's stored ask, clamped to the
     * columns the screen can hold. The single value the chip shows and steps from.
     */
    static int effectiveColumns(int screenWidth) {
        return Math.max(BuilderTemplateGridLayout.MIN_COLUMNS,
                Math.min(BuilderTemplateGridLayout.maxColumnsFor(screenWidth),
                        ClientDisplayConfig.getBuilderTilesPerRow()));
    }

    @Override
    public Component getMessage() {
        return Component.literal(Integer.toString(effectiveColumns(screenWidth)));
    }
}
