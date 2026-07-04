package games.brennan.dungeontrain.client.menu;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Vanilla {@link Button} with a sine-pulsing blue border drawn over the
 * standard sprite. Used as a drop-in replacement for the title-screen
 * Discord button after the player has opted out of the developer welcome
 * popup — the pulse keeps the Discord affordance gently visible without
 * surfacing another modal.
 *
 * <p>The body of the button is rendered untouched (delegating to
 * {@link Button#renderWidget(GuiGraphics, int, int, float)}) so click
 * hit-testing, hover state, accessibility narration, and label rendering
 * all behave exactly like a vanilla button. The pulse is an overlay only.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PulsingDiscordButton extends Button {

    /** One full pulse (alpha 0 → peak → 0) over this many ms. Wall-clock paced so it ticks even when the game pauses. */
    private static final long PULSE_PERIOD_MS = 1500L;
    /** Peak alpha (out of 255) at the brightest moment of the pulse. */
    private static final int PEAK_ALPHA = 200;
    /** Blue tint (24-bit RGB; alpha is pulsed separately). */
    private static final int BLUE_RGB = 0x60_C0_FF;

    /** While true, the pulse overlay is skipped — so two title-screen pulses never compete for attention. */
    private final java.util.function.BooleanSupplier suppressPulse;

    public PulsingDiscordButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, onPress, () -> false);
    }

    public PulsingDiscordButton(int x, int y, int width, int height, Component message, OnPress onPress,
                                java.util.function.BooleanSupplier suppressPulse) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.suppressPulse = suppressPulse == null ? () -> false : suppressPulse;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Standard button render first (sprite background + label).
        super.renderWidget(g, mouseX, mouseY, partialTick);

        if (suppressPulse.getAsBoolean()) {
            return; // a louder affordance (e.g. the unread-message envelope) is pulsing right now
        }

        // Pulsing blue 1-pixel border overlay.
        long now = Util.getMillis();
        float phase = (float) (now % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
        // sin(2πt) in [-1, 1]; map to [0, 1] then to [0, PEAK_ALPHA].
        float wave = (Mth.sin(phase * 2.0F * (float) Math.PI) + 1.0F) * 0.5F;
        int alpha = (int) (wave * PEAK_ALPHA);
        // Multiply by widget alpha so the pulse fades out with the button
        // during screen transitions (e.g. ConfirmLinkScreen swap).
        alpha = (int) (alpha * this.alpha);
        if (alpha <= 0) return;
        int colour = (alpha << 24) | BLUE_RGB;

        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        // Four thin rectangles forming a 1-pixel border that hugs the button.
        g.fill(x - 1, y - 1, x + w + 1, y, colour);             // top edge
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, colour);     // bottom edge
        g.fill(x - 1, y, x, y + h, colour);                     // left edge
        g.fill(x + w, y, x + w + 1, y + h, colour);             // right edge
    }
}
