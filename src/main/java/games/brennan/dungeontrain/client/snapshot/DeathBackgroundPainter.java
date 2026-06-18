package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Picks and draws the per-page ride-photo background for the death screen.
 * Cover-fits the photo to the screen (centre-crop, no distortion) and lays a
 * top/bottom dark vignette over it so the narrative text stays legible while
 * the photo still reads through the middle band.
 *
 * <p>Drawing is split so {@link games.brennan.dungeontrain.client.NarrativeDeathScreen}
 * can drive a page-to-page transition: two photos cross-fade via
 * {@link #drawPhoto} (alpha), and the vignette opacity tracks how "present" the
 * UI is via {@link #drawVignette} (strength) — at its darkest when the page is
 * settled and legible, fading to nothing at the peak of a transition so the bare
 * photo is fully revealed.</p>
 */
public final class DeathBackgroundPainter {

    // Vignette at rest (strength 1): strong at the top/bottom (titles, footer),
    // dark enough through the centre that narration stays legible over the photo.
    private static final int EDGE = 0xEC090A0D;   // ~92% at top & bottom edges
    private static final int CENTER = 0xB0090A0D; // ~69% through the middle band

    private DeathBackgroundPainter() {}

    /**
     * Choose a snapshot for a page. {@code random=true} draws from the whole
     * gallery (feedback pages); otherwise prefers {@code preferred}'s newest
     * shot, falling back to the newest of any tag.
     */
    public static RideSnapshot pick(SnapshotTag preferred, boolean random) {
        if (random) {
            RideSnapshot r = RideSnapshotGallery.random();
            return r != null ? r : RideSnapshotGallery.latest();
        }
        RideSnapshot s = preferred != null ? RideSnapshotGallery.latestOf(preferred) : null;
        return s != null ? s : RideSnapshotGallery.latest();
    }

    /**
     * Draw {@code shot} cover-fit across the screen at {@code alpha} (0..1).
     * Alpha lets a later photo cross-fade in over an earlier one during a page
     * transition; {@code alpha <= 0} or a null shot draws nothing.
     */
    public static void drawPhoto(GuiGraphics g, RideSnapshot shot, int screenW, int screenH, float alpha) {
        if (shot == null || alpha <= 0.0f) return;
        float a = Math.min(1.0f, alpha);
        if (a < 1.0f) g.setColor(1.0f, 1.0f, 1.0f, a);
        drawCover(g, shot, screenW, screenH);
        if (a < 1.0f) g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
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
