package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Picks and draws the per-page ride-photo background for the death screen.
 * Cover-fits the photo to the screen (centre-crop, no distortion) and lays a
 * top/bottom dark vignette over it so the narrative text stays legible while
 * the photo still reads through the middle band.
 */
public final class DeathBackgroundPainter {

    // Vignette: strong at the top/bottom (titles, footer), light through the centre.
    private static final int EDGE = 0xE0090A0D; // ~88% at top & bottom edges
    private static final int CENTER = 0x80090A0D; // ~50% through the middle

    private DeathBackgroundPainter() {}

    /** Draw {@code shot} cover-fit across the screen with the legibility vignette. */
    public static void draw(GuiGraphics g, RideSnapshot shot, int screenW, int screenH) {
        if (shot == null) return;
        drawCover(g, shot, screenW, screenH);
        int half = screenH / 2;
        g.fillGradient(0, 0, screenW, half, EDGE, CENTER);
        g.fillGradient(0, half, screenW, screenH, CENTER, EDGE);
    }

    private static void drawCover(GuiGraphics g, RideSnapshot shot, int screenW, int screenH) {
        float imgAspect = shot.aspect();
        float scrAspect = (float) screenW / Math.max(1, screenH);
        int dw, dh;
        if (scrAspect > imgAspect) { // screen wider than photo → match width, overflow height
            dw = screenW;
            dh = Math.round(screenW / imgAspect);
        } else {                     // match height, overflow width
            dh = screenH;
            dw = Math.round(screenH * imgAspect);
        }
        int dx = (screenW - dw) / 2;
        int dy = (screenH - dh) / 2;
        // Scale the whole texture into the dw×dh destination rect.
        g.blit(shot.texture(), dx, dy, dw, dh, 0.0f, 0.0f, shot.width(), shot.height(), shot.width(), shot.height());
    }
}
