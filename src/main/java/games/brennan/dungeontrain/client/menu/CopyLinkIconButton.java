package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A tiny chain-link glyph that copies a URL: two overlapping link rings drawn programmatically,
 * no frame, no texture. Sits in the corner of {@code PaymentConfirmScreen} so the real destination
 * stays one click away without the address being printed on the panel.
 *
 * <p>Deliberately quiet: dim grey at rest, brighter on hover. It is a courtesy for the player who
 * cannot open a browser from Minecraft, not a third option competing with Open and Cancel.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CopyLinkIconButton extends Button {

    // Rests at the confirm panel's body-text colour so it reads as part of the panel; white on hover.
    private static final int REST = 0xFFC7BDA7;
    private static final int HOVER = 0xFFFFFFFF;

    public CopyLinkIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int c = isHoveredOrFocused() ? HOVER : REST;
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        // Two hollow rings, the second offset down-right so they overlap like chain links.
        int r = Math.max(3, (s * 2) / 3);
        ring(g, x, y, r, c);
        ring(g, x + s - r, y + s - r, r, c);
    }

    /** A hollow square ring of side {@code r}, one pixel thick. */
    private static void ring(GuiGraphics g, int x, int y, int r, int color) {
        g.fill(x, y, x + r, y + 1, color);
        g.fill(x, y + r - 1, x + r, y + r, color);
        g.fill(x, y + 1, x + 1, y + r - 1, color);
        g.fill(x + r - 1, y + 1, x + r, y + r - 1, color);
    }
}
