package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * How many template tiles the Open screen puts in a row — a small numbered chip beside the type
 * controls.
 *
 * <p>The grid's column count was fixed at three, which is a good number and a bad only number: a
 * track kind with thirty variants under it is mostly scrolling, and three big tiles is the wrong
 * trade for someone who knows their own library by its photos. The grid itself fills the window, so
 * what this picks is how that width is divided — bigger tiles or more of them.</p>
 *
 * <p>Left-click for more; right-click or shift-click for fewer. A pair of arrow cells would be the
 * obvious shape and there is no room for one beside the 200px control block, so the second mouse
 * button carries the second direction — the same trade, for the same reason, as
 * {@link BuilderRoomSizeButton} — and shift-click is there beside it because that is what vanilla's
 * own cycling buttons do. Both are in the tooltip, since nothing about the chip shows either.</p>
 *
 * <p>The count cycles rather than stopping: it is a short range you flick through to find the one
 * you want, and a hard stop at the top means walking all the way back down to reach the bottom.</p>
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
        // Shift-click goes the other way, which is vanilla CycleButton's own gesture (its onClick
        // cycles by -1 on shift) — so it is one players already have from the video settings, and
        // the chip doesn't need to teach a third idiom of its own.
        super(x, y, WIDTH, height, Component.empty(),
                b -> step(screenWidth, Screen.hasShiftDown() ? -1 : +1, onChange), DEFAULT_NARRATION);
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
     * Step the count and persist it, wrapping round at both ends.
     *
     * <p>A cycle rather than a clamp: this is a four-value range you flick through to see which one
     * you like, and stopping dead at six means walking all the way back down to reach two. The wrap
     * is over the range <em>this window</em> can hold, not the nominal one, so a screen that
     * saturates at five cycles 2→5→2 rather than pausing on a sixth column it cannot draw.</p>
     *
     * <p>Steps from the <em>effective</em> count rather than the stored one, so the chip and the
     * grid can never disagree: on a window that saturates at five, a stored six reads as five here,
     * and stepping down from it goes to four rather than to a five that would draw identically. The
     * stored value is only overwritten when a click lands, so a count squeezed out by a small window
     * survives until the player touches it.</p>
     */
    private static void step(int screenWidth, int delta, Runnable onChange) {
        int min = BuilderTemplateGridLayout.MIN_COLUMNS;
        int max = BuilderTemplateGridLayout.maxColumnsFor(screenWidth);
        int current = effectiveColumns(screenWidth);
        // floorMod, not %: a backwards step from the bottom is negative, and % would answer with a
        // negative index that lands below the range instead of at the top of it.
        int wanted = min + Math.floorMod(current - min + delta, max - min + 1);
        if (wanted == current) {
            return;   // a window with room for only one count — nothing to cycle through
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
