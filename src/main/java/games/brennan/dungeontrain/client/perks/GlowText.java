package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Text with a dark glow round it, so a label stays readable where it crosses a star's dot or a line:
 * the sky colour drawn one unit out in eight directions, then the text on top (no drop shadow).
 * Draws in the current pose, so a scaled label gets a glow scaled with it.
 */
@OnlyIn(Dist.CLIENT)
final class GlowText {

    private static final int[][] AROUND = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
    private static final double GLOW_ALPHA = 0.85;

    private GlowText() {}

    /** {@code colour} is ARGB; the glow takes the same alpha, softened. */
    static void draw(GuiGraphics g, Font font, String text, int x, int y, int colour) {
        double a = ((colour >>> 24) & 0xFF) / 255.0;
        // Minecraft draws text with an alpha under 4/255 fully opaque: below a visible alpha, draw nothing
        if (a * GLOW_ALPHA < 0.03) return;
        int glow = SkyDraw.withAlpha(SkyPalette.BG, a * GLOW_ALPHA);
        for (int[] d : AROUND) g.drawString(font, text, x + d[0], y + d[1], glow, false);
        g.drawString(font, text, x, y, colour, false);
    }
}
