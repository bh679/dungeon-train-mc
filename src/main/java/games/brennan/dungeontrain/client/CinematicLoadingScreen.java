package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Themed loading screen shown by {@link CinematicPreloadGate} between world-entry
 * and the spawn intro cinematic, while the terrain around the shot streams in.
 *
 * <p>Opaque (fully hides the popping-in world), non-pausing (so the integrated
 * server keeps generating/streaming chunks behind it — {@link #isPauseScreen}
 * returns {@code false}), and non-dismissible ({@link #shouldCloseOnEsc} returns
 * {@code false}; the gate closes it when the cinematic is ready).</p>
 *
 * <p>Progress is drawn as the <b>filling train</b> — the same carriages-on-a-rail
 * dissolving into an {@code ∞} motif as the death screen's
 * {@code NarrativeDeathScreen.drawTrain}: carriages assemble left→right as chunks
 * load, a short fade tail runs ahead of the front car, and the {@code ∞} sits at
 * the end of the line. The bar <em>is</em> the train completing itself. Driven by
 * {@link CinematicPreloadGate#progress()}.</p>
 */
public final class CinematicLoadingScreen extends Screen {

    private static final int BG = 0xFF0B0A14;          // near-black blue/purple, fully opaque
    private static final int TITLE_TEAL = 0xFF5BC8C2;
    private static final int PCT = 0xFF7E828E;

    // Death-screen train palette (matches NarrativeDeathScreen.drawTrain).
    private static final int RAIL = 0xFF43454E;
    private static final int CAR_BODY = 0xFF33353E;
    private static final int CAR_ROOF = 0xFFFF5555;
    private static final int CAR_WINDOW = 0xFF14151A;
    private static final int INF = 0xFF5A5C66;
    /** Fade-tail carriages ahead of the front car — decreasing alpha. */
    private static final int[] FADE_TAIL = { 0x8033353E, 0x4D2B2C33, 0x2624252B };

    // Carriage geometry.
    private static final int CAR_W = 20;
    private static final int CAR_H = 13;
    private static final int SPACING = 24;   // car width + coupling gap
    private static final int MAX_RAIL_W = 300;
    private static final int INF_RESERVE = 18; // room at the right end for the ∞ glyph

    public CinematicLoadingScreen() {
        super(Component.translatable("gui.dungeontrain.cinematic.loading"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** Opaque fill replaces the vanilla world blur/dim so the loading terrain never shows. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BG);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;
        double progress = Mth.clamp(CinematicPreloadGate.progress(), 0.0, 1.0);

        int railW = Math.min(MAX_RAIL_W, this.width - 80);
        int railLeft = cx - railW / 2;
        int railY = cy + 8;

        g.drawCenteredString(this.font, this.title, cx, cy - 30, TITLE_TEAL);
        drawFillingTrain(g, railLeft, railW, railY, progress);
        String pct = (int) Math.round(progress * 100.0) + "%";
        g.drawCenteredString(this.font, pct, cx, cy + 34, PCT);
    }

    /**
     * The train completing itself: solid carriages fill the rail left→right in
     * proportion to {@code progress}, a fade tail runs ahead of the front car
     * until the rail is full, and the {@code ∞} closes the line.
     */
    private void drawFillingTrain(GuiGraphics g, int railLeft, int railW, int railY, double progress) {
        int railRight = railLeft + railW;
        int startX = railLeft + 2;
        int slots = Math.max(1, (railW - INF_RESERVE) / SPACING);
        int solid = (int) Math.round(progress * slots);
        if (solid < 0) solid = 0;
        if (solid > slots) solid = slots;

        // Rail.
        g.fill(railLeft, railY, railRight, railY + 2, RAIL);

        // Solid carriages.
        for (int i = 0; i < solid; i++) {
            drawCarriage(g, startX + i * SPACING, railY);
        }

        // Fade tail ahead of the front car — only while the rail isn't full yet.
        if (solid < slots) {
            int fadeX = startX + solid * SPACING;
            for (int j = 0; j < FADE_TAIL.length; j++) {
                int cxp = fadeX + j * SPACING;
                if (cxp + CAR_W > railRight - INF_RESERVE) break;
                int fh = CAR_H - j * 3;
                if (fh < 5) fh = 5;
                g.fill(cxp, railY - fh, cxp + CAR_W, railY, FADE_TAIL[j]);
            }
        }

        // ∞ terminus — the line runs on forever.
        g.drawString(this.font, "∞", railRight - INF_RESERVE + 4, railY - 8, INF, false);
    }

    /** A single carriage: dark body, red roof stripe, two windows. */
    private void drawCarriage(GuiGraphics g, int x, int railY) {
        int top = railY - CAR_H;
        g.fill(x, top, x + CAR_W, railY, CAR_BODY);
        g.fill(x, top, x + CAR_W, top + 2, CAR_ROOF);
        g.fill(x + 3, top + 4, x + 7, top + 8, CAR_WINDOW);
        g.fill(x + 11, top + 4, x + 15, top + 8, CAR_WINDOW);
    }
}
