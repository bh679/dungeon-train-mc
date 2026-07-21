package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A small square title-screen icon button rendered as the Patreon logomark
 * (white bar + circle on the Patreon brand orange) — drawn programmatically so
 * no texture asset needs shipping. Used for the "Support on Patreon" shortcut
 * next to the menu-chat envelope.
 */
@OnlyIn(Dist.CLIENT)
public final class PatreonIconButton extends Button {

    private static final int ORANGE       = 0xFFF96854; // Patreon brand orange/coral
    private static final int ORANGE_HOVER = 0xFFFF7E68;
    private static final int WHITE        = 0xFFFFFFFF;
    private static final int BORDER       = 0xFF201008;

    public PatreonIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        // Face + 1px border.
        g.fill(x, y, x + w, y + h, isHoveredOrFocused() ? ORANGE_HOVER : ORANGE);
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);

        // Patreon logomark: vertical bar on the left, filled circle on the right.
        int barX = x + 4;
        g.fill(barX, y + 4, barX + 3, y + h - 4, WHITE);
        fillCircle(g, x + w - 6, y + h / 2, 4, WHITE);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }
}
