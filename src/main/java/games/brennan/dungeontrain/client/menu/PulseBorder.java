package games.brennan.dungeontrain.client.menu;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A sine-pulsing one-pixel border hugging a widget — the "look here" affordance the title-screen
 * Discord icon uses when the player has opted out of the welcome popup, and the Videos page's
 * submit button uses while OBS is running. One place so the two pulse alike.
 *
 * <p>Wall-clock paced so it keeps ticking while the game is paused. The pulse fades with the
 * widget's own alpha so it disappears with the button during screen transitions.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PulseBorder {

    /** One full pulse (alpha 0 → peak → 0) over this many ms. */
    private static final long PERIOD_MS = 1500L;
    /** Peak alpha (out of 255) at the brightest moment. */
    private static final int PEAK_ALPHA = 200;

    private PulseBorder() {}

    /**
     * Draws the border around {@code (x, y, w, h)} in {@code rgb} (24-bit; alpha is pulsed), scaled
     * by {@code widgetAlpha}. Draws nothing at the trough.
     */
    public static void render(GuiGraphics g, int x, int y, int w, int h, int rgb, float widgetAlpha) {
        long now = Util.getMillis();
        float phase = (float) (now % PERIOD_MS) / (float) PERIOD_MS;
        float wave = (Mth.sin(phase * 2.0F * (float) Math.PI) + 1.0F) * 0.5F;
        int alpha = (int) ((int) (wave * PEAK_ALPHA) * widgetAlpha);
        if (alpha <= 0) return;
        int colour = (alpha << 24) | (rgb & 0xFFFFFF);
        g.fill(x - 1, y - 1, x + w + 1, y, colour);         // top edge
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, colour); // bottom edge
        g.fill(x - 1, y, x, y + h, colour);                 // left edge
        g.fill(x + w, y, x + w + 1, y + h, colour);         // right edge
    }
}
