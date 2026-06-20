package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the per-page ride-photo background for the death screen. Cover-fits the
 * photo to the screen (centre-crop, no distortion); a separate vignette pass
 * lays a top/bottom dark gradient over it so the narrative text stays legible
 * while the photo still reads through the middle band.
 *
 * <p>The photo and the vignette are drawn separately so
 * {@link games.brennan.dungeontrain.client.NarrativeDeathScreen} can drive a
 * page-to-page transition: the vignette opacity tracks how "present" the UI is
 * ({@link #drawVignette} strength), and the photo dip-to-black is composited by
 * the caller (a black fill over {@link #drawPhoto}). Which photo each page shows
 * is chosen up-front by {@link DeathBackgroundAssigner}.</p>
 */
public final class DeathBackgroundPainter {

    // Vignette at rest (strength 1): strong at the top/bottom (titles, footer),
    // dark enough through the centre that narration stays legible over the photo.
    private static final int EDGE = 0xEC090A0D;   // ~92% at top & bottom edges
    private static final int CENTER = 0xB0090A0D; // ~69% through the middle band

    private DeathBackgroundPainter() {}

    /** Draw {@code shot} cover-fit across the screen (fully opaque). */
    public static void drawPhoto(GuiGraphics g, RideSnapshot shot, int screenW, int screenH) {
        if (shot == null) return;
        drawCover(g, shot, screenW, screenH);
    }

    /**
     * Lay the top/bottom legibility vignette over the photo at {@code strength}
     * (0..1) of its rest opacity. The death screen passes the UI's alpha, so the
     * vignette is darkest when the page is settled and fades away as the UI fades
     * out — revealing the bare photo between screens.
     */
    public static void drawVignette(GuiGraphics g, int screenW, int screenH, float strength) {
        if (strength <= 0.0f) return;
        float s = Math.min(1.0f, strength);
        int edge = scaleAlpha(EDGE, s);
        int center = scaleAlpha(CENTER, s);
        int half = screenH / 2;
        g.fillGradient(0, 0, screenW, half, edge, center);
        g.fillGradient(0, half, screenW, screenH, center, edge);
    }

    /** Scale only the alpha byte of an ARGB colour by {@code f} (0..1). */
    private static int scaleAlpha(int argb, float f) {
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
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
