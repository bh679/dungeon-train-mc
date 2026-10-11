package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The small "i" button above Scribble's bold button on the book-writing screen. Hovering it lists
 * what the shared-book content filter holds back, so a writer knows the rules before signing.
 * Clicking does nothing — it is a tooltip anchor, not an action.
 *
 * <p>The list mirrors the relay's moderation categories in broad strokes only (dp-relay
 * {@code moderation.js} / {@code moderation-routing.js}); it is deliberately general so it does
 * not go stale every time the relay's rules are tuned.</p>
 *
 * <p>Drawn with {@link GuiGraphics#fill} in the book's parchment-and-leather colours, the same way
 * {@link games.brennan.dungeontrain.client.menu.ScribbleColorPickerToggleButton} is drawn, so it
 * needs no texture.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BookFilterInfoButton extends AbstractWidget {

    public static final int SIZE = 12;

    /** Lines of the tooltip after the title, as {@code gui.dungeontrain.book_filter_info.<n>}. */
    static final int LINE_COUNT = 11;

    private static final int BORDER = 0xFF6B3B1F;
    private static final int FACE = 0xFFF4EBD3;
    private static final int FACE_HOVER = 0xFFFFF8E6;
    private static final int INK = 0xFF3F2A14;

    public BookFilterInfoButton(int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("gui.dungeontrain.book_filter_info.title"));
        setTooltip(Tooltip.create(tooltipText()));
    }

    /** Title in bold, then one line per rule. */
    static Component tooltipText() {
        MutableComponent text = Component.translatable("gui.dungeontrain.book_filter_info.title")
                .withStyle(s -> s.withBold(true));
        for (int i = 1; i <= LINE_COUNT; i++) {
            text.append("\n").append(Component.translatable("gui.dungeontrain.book_filter_info." + i));
        }
        return text;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        g.fill(x0, y0, x0 + SIZE, y0 + SIZE, BORDER);
        g.fill(x0 + 1, y0 + 1, x0 + SIZE - 1, y0 + SIZE - 1, isHovered() ? FACE_HOVER : FACE);

        // A pixel "i": dot, stem, and a foot so it reads at 1x GUI scale.
        g.fill(x0 + 5, y0 + 2, x0 + 7, y0 + 4, INK);
        g.fill(x0 + 5, y0 + 5, x0 + 7, y0 + 10, INK);
        g.fill(x0 + 4, y0 + 5, x0 + 5, y0 + 6, INK);
        g.fill(x0 + 4, y0 + 9, x0 + 8, y0 + 10, INK);
    }

    /** Silent: the button has no action, so a click sound would promise one. */
    @Override
    public void playDownSound(SoundManager soundManager) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
