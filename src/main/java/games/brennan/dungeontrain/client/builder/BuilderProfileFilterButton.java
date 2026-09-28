package games.brennan.dungeontrain.client.builder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * One of My Builds' two narrowing chips — which kind of build, and where it stands with the reviewer.
 *
 * <p>A cycling chip rather than a strip of tabs, which is the shape {@link BuilderTilesPerRowButton},
 * {@link BuilderRoomSizeButton} and {@link BuilderMirrorButton} already have on this screen's
 * neighbours: eleven values across two axes would be eleven buttons competing with a grid that is
 * already the tightest thing here for space, and the gesture is one players have from every one of
 * those controls.</p>
 *
 * <p>Left-click for the next value, right-click or shift-click for the previous, wrapping at both
 * ends — a hard stop at the last option means walking the whole way back to reach the first. Both
 * directions live in the tooltip, since nothing on a chip shows either.</p>
 *
 * <p>Generic over its options so one class serves both axes. The value is a plain string — a relay
 * kind or a review state, both of which arrive as strings from the relay and neither of which this
 * screen has any reason to convert into an enum on the way past.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderProfileFilterButton extends Button {

    /**
     * One choice: what it filters to, what the chip reads while it is chosen, and the colour the chip
     * wears while it is chosen.
     *
     * <p>{@code tint} is a full-strength state colour — the same one the matching tiles are ringed in
     * — which the chip fades itself (see {@link #TINT_ALPHA}). Passing the faded value instead would
     * put the same colour in the codebase twice at two different opacities, and the second one would
     * be the one nobody remembered to change. {@code 0} for an option with no colour of its own,
     * which is every type and the two status options that aren't a verdict.</p>
     */
    record Option(String value, String labelKey, int tint) {

        Option(String value, String labelKey) {
            this(value, labelKey, 0);
        }
    }

    /**
     * How much of the state colour the chip wears: a wash, not a fill.
     *
     * <p>It goes on over the finished button — sprite and label both — because vanilla's
     * {@code Button} draws the two together and there is no seam between them to paint into. So it
     * has to be light enough to leave white text on grey legible, which is what a quarter is: enough
     * to name the colour, not enough to be read as text of that colour.</p>
     */
    private static final int TINT_ALPHA = 0x40000000;

    private final List<Option> options;
    private final Consumer<String> onChange;

    /** Read on every frame rather than cached, so the chip can never disagree with the screen. */
    private final java.util.function.Supplier<String> current;

    BuilderProfileFilterButton(int x, int y, int width, int height, List<Option> options,
                               java.util.function.Supplier<String> current, Consumer<String> onChange,
                               String tooltipKey) {
        super(x, y, width, height, Component.empty(), b -> {}, DEFAULT_NARRATION);
        this.options = List.copyOf(options);
        this.current = current;
        this.onChange = onChange;
        setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
    }

    @Override
    public void onPress() {
        step(Screen.hasShiftDown() ? -1 : +1);
    }

    /**
     * Right-click steps back.
     *
     * <p>Handled here rather than in the press handler because {@code AbstractButton} only ever
     * reports button 0 — it treats anything else as not a click at all, so without this the chip
     * would only ever go forwards. The same reason {@link BuilderTilesPerRowButton} does it.</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && this.visible && this.active && isMouseOver(mouseX, mouseY)) {
            step(-1);
            playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void step(int delta) {
        int at = indexOfCurrent();
        // floorMod, not %: a backwards step from the first option is negative, and % would answer
        // with a negative index that lands outside the list rather than at the end of it.
        onChange.accept(options.get(Math.floorMod(at + delta, options.size())).value());
    }

    /**
     * Where the chip is now. A value this chip has no option for reads as the first one — which is
     * "All", so a filter that somehow went stale shows everything rather than hiding everything.
     */
    private int indexOfCurrent() {
        String value = current.get();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equals(value)) return i;
        }
        return 0;
    }

    /**
     * The ordinary button, washed in the colour of whatever it is currently filtering to.
     *
     * <p>Only the status chip ever has one, and it is the point of the wash: the grid it filters is
     * colour-coded, so a chip reading "Accepted" in green says which slice you are looking at in the
     * same language the tiles answer in — and a chip left narrowed is a good deal harder to forget
     * about when it is coloured.</p>
     */
    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        int tint = options.get(indexOfCurrent()).tint();
        if (tint == 0) return;
        // Inset by one so the wash sits inside the button's own border rather than softening its edge.
        g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1,
                (tint & 0x00FFFFFF) | TINT_ALPHA);
    }

    @Override
    public Component getMessage() {
        return Component.translatable(options.get(indexOfCurrent()).labelKey());
    }
}
