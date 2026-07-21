package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying the Patreon logomark — the coral
 * circle with a navy vertical bar and a white circle — drawn programmatically
 * (scaled to the button size) so no texture asset needs shipping. Sits beside
 * the "Support the Mod" button on the title screen.
 */
@OnlyIn(Dist.CLIENT)
public final class PatreonIconButton extends Button {

    private static final int CORAL       = 0xFFEA5F4A; // Patreon coral
    private static final int CORAL_HOVER = 0xFFF57A66;
    private static final int NAVY        = 0xFF052A3E; // logomark bar
    private static final int WHITE       = 0xFFFFFFFF;

    public PatreonIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int cx = x + getWidth() / 2;
        int cy = y + getHeight() / 2;

        // Coral disc (fills the icon; corners stay transparent, matching the logo).
        int r = s / 2;
        fillCircle(g, cx, cy, r - 1, isHoveredOrFocused() ? CORAL_HOVER : CORAL);

        // Navy vertical bar on the left of the mark.
        int barW = Math.max(2, Math.round(s * 0.13F));
        int barX = x + Math.round(s * 0.27F);
        int barTop = y + Math.round(s * 0.26F);
        int barBottom = y + getHeight() - Math.round(s * 0.26F);
        g.fill(barX, barTop, barX + barW, barBottom, NAVY);

        // White circle to the right of the bar.
        int wr = Math.max(2, Math.round(s * 0.19F));
        fillCircle(g, x + Math.round(s * 0.62F), cy, wr, WHITE);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }
}
