package games.brennan.dungeontrain.client.deathphotos;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * The death screen's photo page body: one horizontally scrolling row of thumbnails, drawn wider than
 * the page's text column (it spans the screen, less a margin) so the photos get the room.
 *
 * <p>The wheel — vertical or horizontal — moves a scroll target; the drawn offset eases toward it.
 * Fade bands at either edge show there is more row off-screen. A click on a thumbnail is reported by
 * {@link #photoAt} so the host can open the fullscreen viewer.</p>
 */
public final class PhotoStripPage {

    private static final int MARGIN_X = 16;
    private static final int GAP = 8;
    private static final int MIN_HEIGHT = 48;
    private static final int MAX_HEIGHT = 400;
    private static final int SCROLL_STEP = 64;
    private static final int FADE_W = 24;
    private static final float EASE = 0.35f;
    private static final int BORDER = 0x33FFFFFF;
    private static final int BORDER_HOVER = 0xCCE6D6B0;
    private static final int EDGE_SHADE = 0xC0000000;

    private float scroll;
    private float scrollTarget;
    private StripLayout layout;
    private int vpX, vpY, vpW, vpH;

    /** Back to the start of the row (page entered again). */
    public void resetScroll() {
        scroll = 0f;
        scrollTarget = 0f;
    }

    /**
     * Draw the row between {@code top} and {@code bottom}, vertically centred there. Thumbnails are
     * only drawn once the page is {@code settled} — the photos are opaque blits that can't take the
     * page's fade. Returns the y below the row.
     */
    public int draw(GuiGraphics g, Font font, List<DeathPhoto> photos, int screenW, int top, int bottom,
                    int mouseX, int mouseY, boolean settled) {
        int avail = Math.max(0, bottom - top);
        // As tall as the space allows: the photos are the page.
        int h = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, avail - 4));
        vpX = MARGIN_X;
        vpW = Math.max(1, screenW - 2 * MARGIN_X);
        vpH = h;
        vpY = top + Math.max(0, (avail - h) / 2);

        float[] aspects = new float[photos.size()];
        for (int i = 0; i < aspects.length; i++) aspects[i] = photos.get(i).aspect();
        layout = StripLayout.of(aspects, h, GAP);

        int max = layout.maxScroll(vpW);
        scrollTarget = clamp(scrollTarget, max);
        scroll += (scrollTarget - scroll) * EASE;
        if (Math.abs(scrollTarget - scroll) < 0.5f) scroll = scrollTarget;
        scroll = clamp(scroll, max);

        if (!settled || photos.isEmpty()) return vpY + vpH;

        int offset = Math.round(scroll);
        int inset = layout.centreInset(vpW);
        int hover = hoverIndex(mouseX, mouseY);
        g.enableScissor(vpX, vpY, vpX + vpW, vpY + vpH);
        for (int i = 0; i < photos.size(); i++) {
            int x = vpX + inset + layout.xs()[i] - offset;
            int w = layout.widths()[i];
            if (x + w < vpX || x > vpX + vpW) continue; // scrolled out of view
            g.fill(x, vpY, x + w, vpY + h, PhotoPainter.CELL_BG);
            PhotoPainter.drawContain(g, font, photos.get(i), x, vpY, w, h);
            PhotoPainter.drawBorder(g, x, vpY, w, h, i == hover ? BORDER_HOVER : BORDER);
        }
        if (offset > 0) drawEdgeFade(g, vpX, true);
        if (offset < max) drawEdgeFade(g, vpX + vpW - FADE_W, false);
        g.disableScissor();
        return vpY + vpH;
    }

    /** A horizontal shade band: darkest at the viewport edge, clear towards the middle. */
    private void drawEdgeFade(GuiGraphics g, int x, boolean leftEdge) {
        int baseA = (EDGE_SHADE >>> 24) & 0xFF;
        for (int i = 0; i < FADE_W; i++) {
            float t = leftEdge ? 1f - (float) i / FADE_W : (float) (i + 1) / FADE_W;
            int a = Math.round(baseA * t);
            g.fill(x + i, vpY, x + i + 1, vpY + vpH, a << 24);
        }
    }

    private int hoverIndex(double mx, double my) {
        if (layout == null || mx < vpX || mx >= vpX + vpW || my < vpY || my >= vpY + vpH) return -1;
        return layout.indexAt((int) mx - vpX, Math.round(scroll), vpW);
    }

    /** The photo under the cursor, or -1. */
    public int photoAt(double mx, double my) {
        return hoverIndex(mx, my);
    }

    /** Wheel over the row: scroll it. Returns whether the event was used. */
    public boolean scroll(double mx, double my, double dx, double dy) {
        if (layout == null || my < vpY || my >= vpY + vpH) return false;
        int max = layout.maxScroll(vpW);
        if (max <= 0) return false;
        double notches = Math.abs(dx) > Math.abs(dy) ? dx : dy;
        scrollTarget = clamp(scrollTarget - (float) notches * SCROLL_STEP, max);
        return true;
    }

    private static float clamp(float v, int max) {
        return Math.max(0f, Math.min(max, v));
    }
}
