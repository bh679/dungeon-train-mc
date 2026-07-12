package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Shared visual language for {@link CinematicLoadingScreen} and the themed
 * {@code LevelLoadingScreenThemeMixin} first screen, so the two read as one
 * continuous loading sequence rather than two unrelated screens.
 *
 * <p>Everything here is drawn procedurally with {@link GuiGraphics} — no
 * texture assets — matching the rest of the mod's custom screens
 * ({@code NarrativeDeathScreen}, {@code CinematicLoadingScreen}).</p>
 */
public final class LoadingScreenTheme {

    private static final int BG_TOP = 0xFF0B0A14;       // near-black blue/purple
    private static final int BG_BOTTOM = 0xFF141222;    // slightly lighter — subtle depth gradient
    public static final int TITLE_TEAL = 0xFF5BC8C2;
    public static final int PCT = 0xFF7E828E;
    public static final int TIP_LABEL = 0xFF6E7280;
    public static final int TIP_TEXT = 0xFF9A9EAC;

    // Death-screen train palette (matches NarrativeDeathScreen.drawTrain).
    private static final int RAIL = 0xFF43454E;
    private static final int TIE = 0xFF2B2C33;
    private static final int CAR_BODY = 0xFF33353E;
    private static final int CAR_ROOF = 0xFFFF5555;
    private static final int CAR_WINDOW = 0xFF14151A;
    private static final int INF = 0xFF5A5C66;
    private static final int SMOKE = 0x30C9CCD6;
    /** Fade-tail carriages ahead of the front car — decreasing alpha. */
    private static final int[] FADE_TAIL = { 0x8033353E, 0x4D2B2C33, 0x2624252B };

    // Carriage geometry.
    private static final int CAR_W = 20;
    private static final int CAR_H = 13;
    private static final int SPACING = 24;   // car width + coupling gap
    public static final int MAX_RAIL_W = 300;
    private static final int INF_RESERVE = 18; // room at the right end for the ∞ glyph
    private static final int TIE_SPACING = 8;

    private LoadingScreenTheme() {}

    /** Opaque gradient fill replacing the vanilla world blur/grid — never a flash of vanilla UI. */
    public static void fillBackground(GuiGraphics g, int width, int height) {
        g.fillGradient(0, 0, width, height, BG_TOP, BG_BOTTOM);
    }

    public static void drawTitle(GuiGraphics g, Font font, Component title, int cx, int cy) {
        g.drawCenteredString(font, title, cx, cy, TITLE_TEAL);
    }

    public static void drawPercent(GuiGraphics g, Font font, double progress, int cx, int cy) {
        String pct = (int) Math.round(Mth.clamp(progress, 0.0, 1.0) * 100.0) + "%";
        g.drawCenteredString(font, pct, cx, cy, PCT);
    }

    /**
     * A dim, centred, word-wrapped status/story line. Used for both the rotating
     * {@link LoadingStories} line and, on the first screen, the live
     * {@code BootstrapProgress} phase label.
     */
    public static void drawTip(GuiGraphics g, Font font, Component tip, int cx, int y, int maxWidth) {
        List<FormattedText> lines = font.getSplitter().splitLines(tip, maxWidth, tip.getStyle());
        int lineY = y;
        for (FormattedText line : lines) {
            g.drawCenteredString(font, Component.literal(line.getString()), cx, lineY, TIP_TEXT);
            lineY += font.lineHeight + 2;
        }
    }

    /**
     * The train completing itself: solid carriages fill the rail left→right in
     * proportion to {@code progress}, a fade tail runs ahead of the front car
     * until the rail is full, drifting smoke rises off the lead carriage, and a
     * gently pulsing {@code ∞} closes the line.
     */
    public static void drawFillingTrain(GuiGraphics g, Font font, int railLeft, int railW, int railY, double progress, long animNanos) {
        int railRight = railLeft + railW;
        int startX = railLeft + 2;
        int slots = Math.max(1, (railW - INF_RESERVE) / SPACING);
        int solid = (int) Math.round(Mth.clamp(progress, 0.0, 1.0) * slots);
        if (solid > slots) solid = slots;

        // Rail + ties.
        g.fill(railLeft, railY, railRight, railY + 2, RAIL);
        for (int x = railLeft; x < railRight; x += TIE_SPACING) {
            g.fill(x, railY + 2, x + 2, railY + 4, TIE);
        }

        // Solid carriages.
        for (int i = 0; i < solid; i++) {
            drawCarriage(g, startX + i * SPACING, railY);
        }

        // Smoke drifting off the lead carriage, and fade tail ahead of it — only
        // while the rail isn't full yet.
        if (solid < slots) {
            int leadX = startX + Math.max(0, solid - 1) * SPACING;
            drawSmoke(g, leadX + CAR_W / 2, railY - CAR_H, animNanos);

            int fadeX = startX + solid * SPACING;
            for (int j = 0; j < FADE_TAIL.length; j++) {
                int cxp = fadeX + j * SPACING;
                if (cxp + CAR_W > railRight - INF_RESERVE) break;
                int fh = CAR_H - j * 3;
                if (fh < 5) fh = 5;
                g.fill(cxp, railY - fh, cxp + CAR_W, railY, FADE_TAIL[j]);
            }
        }

        // ∞ terminus — the line runs on forever. Gentle alpha pulse.
        double pulse = 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(animNanos / 6.0e8));
        int infColor = (INF & 0x00FFFFFF) | ((int) (0xFF * pulse) << 24);
        g.drawString(font, "∞", railRight - INF_RESERVE + 4, railY - 8, infColor, false);
    }

    /** A single carriage: dark body, red roof stripe, two windows. */
    private static void drawCarriage(GuiGraphics g, int x, int railY) {
        int top = railY - CAR_H;
        g.fill(x, top, x + CAR_W, railY, CAR_BODY);
        g.fill(x, top, x + CAR_W, top + 2, CAR_ROOF);
        g.fill(x + 3, top + 4, x + 7, top + 8, CAR_WINDOW);
        g.fill(x + 11, top + 4, x + 15, top + 8, CAR_WINDOW);
    }

    /** Three soft puffs drifting up and to the right off the lead carriage, looping over time. */
    private static void drawSmoke(GuiGraphics g, int baseX, int baseY, long animNanos) {
        double t = (animNanos / 1.0e9) % 3.0; // 3s loop
        for (int i = 0; i < 3; i++) {
            double phase = (t + i * 1.0) % 3.0;
            double life = phase / 3.0; // 0..1
            int size = 2 + (int) Math.round(life * 3);
            int x = baseX + (int) Math.round(life * 10);
            int y = baseY - (int) Math.round(life * 14);
            int alpha = (int) Math.round((1.0 - life) * 0x30);
            int color = (SMOKE & 0x00FFFFFF) | (alpha << 24);
            g.fill(x, y, x + size, y + size, color);
        }
    }
}
