package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The little × that rides just above the far end of the {@link VideosIconButton} share tab. Click
 * it and the tab folds away for the rest of the session ({@link VideosIconButton#dismissShareTab()}).
 *
 * <p>A separate widget because it sits outside the Videos button's rectangle, and a widget's clicks
 * stop at its edges. It follows the tab: each frame it re-anchors to the tab's current right end,
 * and it only draws — or takes a click — while the tab is fully out, so nothing appears during the
 * slide and nothing lingers once the tab has gone.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ShareTabCloseButton extends Button {

    /** The × is this many pixels square; the hit box matches. */
    private static final int SIZE = 5;
    /** Gap between the tab's top edge and the ×'s bottom. */
    private static final int GAP = 1;
    private static final int MARK = 0xFFE0E0E0;
    private static final int MARK_HOVER = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF202020;

    private final VideosIconButton tab;

    public ShareTabCloseButton(VideosIconButton tab, Component narration) {
        super(0, 0, SIZE, SIZE, narration, b -> VideosIconButton.dismissShareTab(), DEFAULT_NARRATION);
        this.tab = tab;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Follow the tab's far end. Judged after the move so hover is against where it is now.
        setX(tab.getX() + tab.getWidth() - SIZE);
        setY(tab.getY() - SIZE - GAP);
        if (!tab.isShareTabOut()) return;
        this.isHovered = mouseX >= getX() && mouseY >= getY()
                && mouseX < getX() + SIZE && mouseY < getY() + SIZE;

        int colour = isHoveredOrFocused() ? MARK_HOVER : MARK;
        int x = getX();
        int y = getY();
        for (int i = 0; i < SIZE; i++) {
            // Shadow one pixel down-right, then the two diagonals over it.
            g.fill(x + i + 1, y + i + 1, x + i + 2, y + i + 2, SHADOW);
            g.fill(x + SIZE - i, y + i + 1, x + SIZE - i + 1, y + i + 2, SHADOW);
        }
        for (int i = 0; i < SIZE; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, colour);
            g.fill(x + SIZE - 1 - i, y + i, x + SIZE - i, y + i + 1, colour);
        }
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return tab.isShareTabOut() && super.clicked(mouseX, mouseY);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return tab.isShareTabOut() && super.isMouseOver(mouseX, mouseY);
    }
}
