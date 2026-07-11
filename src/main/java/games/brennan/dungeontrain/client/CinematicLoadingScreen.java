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
 * {@code false}; the gate closes it when the cinematic is ready). Draws a title
 * plus a progress bar driven by {@link CinematicPreloadGate#progress()}, using
 * the mod's teal/purple palette (matching {@link FreePlayConfirmScreen}).</p>
 */
public final class CinematicLoadingScreen extends Screen {

    private static final int BG = 0xFF0B0A14;            // near-black blue/purple, fully opaque
    private static final int TITLE_TEAL = 0xFF5BC8C2;
    private static final int SUBTITLE = 0xFFB8B8C4;
    private static final int BAR_FRAME = 0xFF3A3350;
    private static final int BAR_TRACK = 0xFF1E1B2E;
    private static final int BAR_FILL_TOP = 0xFF6E5BFF;   // purple
    private static final int BAR_FILL_BOTTOM = 0xFF5BC8C2; // teal

    private static final int MAX_BAR_W = 240;
    private static final int BAR_H = 6;

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
        int barW = Math.min(MAX_BAR_W, this.width - 80);
        int barX = (this.width - barW) / 2;
        int barY = this.height / 2 + 4;
        int titleY = this.height / 2 - 16;

        g.drawCenteredString(this.font, this.title, cx, titleY, TITLE_TEAL);

        // Frame + track.
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + BAR_H + 1, BAR_FRAME);
        g.fill(barX, barY, barX + barW, barY + BAR_H, BAR_TRACK);

        // Fill proportional to loaded fraction.
        double progress = Mth.clamp(CinematicPreloadGate.progress(), 0.0, 1.0);
        int fillW = (int) Math.round(barW * progress);
        if (fillW > 0) {
            g.fillGradient(barX, barY, barX + fillW, barY + BAR_H, BAR_FILL_TOP, BAR_FILL_BOTTOM);
        }

        String pct = (int) Math.round(progress * 100.0) + "%";
        g.drawCenteredString(this.font, pct, cx, barY + BAR_H + 6, SUBTITLE);
    }
}
