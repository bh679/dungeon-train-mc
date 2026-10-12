package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.List;

/**
 * The small "i" button above Scribble's bold button on the book-writing screen. Hovering it lists
 * what the shared-book content filter holds back, so a writer knows the rules before signing.
 * Clicking does nothing — it is a tooltip anchor, not an action.
 *
 * <p>The tooltip is pinned beside the book rather than following the cursor: right of the button
 * when it fits, otherwise left of the book (large GUI scales / narrow windows), so it never covers
 * the page or the formatting buttons.</p>
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
    static final int LINE_COUNT = 10;

    private static final int BORDER = 0xFF6B3B1F;
    private static final int FACE = 0xFFF4EBD3;
    private static final int FACE_HOVER = 0xFFFFF8E6;
    private static final int INK = 0xFF3F2A14;

    /** Vanilla pads a tooltip's background 3px around its text. */
    private static final int PAD = 3;
    /** Air between the button and the tooltip's background. */
    private static final int AIR = 4;
    /** Widest the text is wrapped to. */
    private static final int MAX_WRAP = 220;
    /** Narrowest the text is wrapped to on the left of the book. */
    private static final int MIN_LEFT_WRAP = 80;
    /** Scribble's undo/redo column starts {@code width/2 - 97}; the left-hand tooltip ends before it. */
    private static final int LEFT_COLUMN_FROM_CENTRE = 97;

    public BookFilterInfoButton(int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("gui.dungeontrain.book_filter_info.title"));
    }

    /** Title, then one line per rule, all in the plain tooltip font. */
    static Component tooltipText() {
        MutableComponent text = Component.translatable("gui.dungeontrain.book_filter_info.title");
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

        if (isHovered()) showPinnedTooltip();
    }

    /**
     * Queues the tooltip for the screen's tooltip pass (drawn on top). It goes right of the button
     * when its natural width fits there; otherwise it moves to the empty space left of the book,
     * beside Scribble's undo/redo column, so it never covers the formatting buttons.
     */
    private void showPinnedTooltip() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) return;
        int textX = getX() + SIZE + AIR + PAD;
        int roomRight = screen.width - textX - PAD;
        List<FormattedCharSequence> natural = mc.font.split(tooltipText(), MAX_WRAP);
        int naturalWidth = natural.stream().mapToInt(mc.font::width).max().orElse(0);
        if (naturalWidth <= roomRight) {
            screen.setTooltipForNextRenderPass(natural, new RightOfWidgetPositioner(textX, getY()), true);
            return;
        }
        int rightEdge = screen.width / 2 - LEFT_COLUMN_FROM_CENTRE - AIR - PAD;
        int wrap = Mth.clamp(rightEdge - PAD * 2, MIN_LEFT_WRAP, MAX_WRAP);
        List<FormattedCharSequence> lines = mc.font.split(tooltipText(), wrap);
        screen.setTooltipForNextRenderPass(lines, new LeftOfBookPositioner(rightEdge, getY()), true);
    }

    /** Ends the tooltip's text at {@code rightEdge}, left of the book. */
    private record LeftOfBookPositioner(int rightEdge, int topY) implements ClientTooltipPositioner {
        @Override
        public Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY,
                                         int tooltipWidth, int tooltipHeight) {
            int x = Math.max(PAD, rightEdge - tooltipWidth);
            int y = Mth.clamp(topY + PAD, PAD, Math.max(PAD, screenHeight - tooltipHeight - PAD));
            return new Vector2i(x, y);
        }
    }

    /** Puts the tooltip's text at a fixed point right of the button, kept on screen. */
    private record RightOfWidgetPositioner(int textX, int topY) implements ClientTooltipPositioner {
        @Override
        public Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY,
                                         int tooltipWidth, int tooltipHeight) {
            int x = Mth.clamp(textX, PAD, Math.max(PAD, screenWidth - tooltipWidth - PAD));
            int y = Mth.clamp(topY + PAD, PAD, Math.max(PAD, screenHeight - tooltipHeight - PAD));
            return new Vector2i(x, y);
        }
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
