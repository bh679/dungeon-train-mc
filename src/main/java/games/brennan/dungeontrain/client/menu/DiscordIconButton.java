package games.brennan.dungeontrain.client.menu;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * A square title-screen icon button carrying the Discord mark — the blurple rounded tile with
 * the white face on it — drawn programmatically (scaled to the button size) so no texture asset
 * needs shipping, the same approach {@link PatreonIconButton} takes for Patreon. Sits in the
 * icon column above the Credits book (see {@code TitleScreenCreditsButton}).
 *
 * <p>It also carries the <b>opted-out pulse</b>: when the player has dismissed the developer
 * welcome popup, a sine-pulsing blue border keeps the Discord affordance gently visible without
 * surfacing another modal. {@code suppressPulse} stands the pulse down while a louder one is
 * running — the unread-message envelope — so two title-screen pulses never compete.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DiscordIconButton extends Button {

    private static final int BLURPLE       = 0xFF5865F2;
    private static final int BLURPLE_HOVER = 0xFF7982F5;
    private static final int FACE          = 0xFFFFFFFF;
    private static final int EYE           = 0xFF5865F2;

    /** One full pulse (alpha 0 → peak → 0) over this many ms. Wall-clock paced so it ticks even when the game pauses. */
    private static final long PULSE_PERIOD_MS = 1500L;
    /** Peak alpha (out of 255) at the brightest moment of the pulse. */
    private static final int PEAK_ALPHA = 200;
    /** Blue tint of the pulse border (24-bit RGB; alpha is pulsed separately). */
    private static final int PULSE_RGB = 0x60_C0_FF;

    /** While true, the pulse overlay is skipped. */
    private final BooleanSupplier suppressPulse;
    /** Whether to pulse at all — false for a player who never opted out of the welcome popup. */
    private final boolean pulsing;

    public DiscordIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        this(x, y, size, narration, onPress, false, () -> false);
    }

    public DiscordIconButton(int x, int y, int size, Component narration, OnPress onPress,
                             boolean pulsing, BooleanSupplier suppressPulse) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
        this.pulsing = pulsing;
        this.suppressPulse = suppressPulse == null ? () -> false : suppressPulse;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int body = isHoveredOrFocused() ? BLURPLE_HOVER : BLURPLE;

        // Blurple tile, corners nipped so it reads as the rounded app icon rather than a square.
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);

        // The face: a wide rounded head, two ears flaring at the bottom corners, two eyes.
        int headTop = y + Math.round(s * 0.32F);
        int headBottom = y + Math.round(s * 0.62F);
        int headLeft = x + Math.round(s * 0.27F);
        int headRight = x + getWidth() - Math.round(s * 0.27F);
        g.fill(headLeft, headTop, headRight, headBottom, FACE);
        // Flared ears — one pixel column wider on each side, sitting a touch lower.
        int earTop = y + Math.round(s * 0.44F);
        int earBottom = y + Math.round(s * 0.70F);
        int ear = Math.max(1, Math.round(s * 0.10F));
        g.fill(headLeft - ear, earTop, headLeft + ear, earBottom, FACE);
        g.fill(headRight - ear, earTop, headRight + ear, earBottom, FACE);
        // Eyes, punched back out in the body colour.
        int eye = Math.max(1, Math.round(s * 0.09F));
        int eyeTop = y + Math.round(s * 0.40F);
        g.fill(headLeft + ear, eyeTop, headLeft + ear + eye, eyeTop + eye + 1, EYE);
        g.fill(headRight - ear - eye, eyeTop, headRight - ear, eyeTop + eye + 1, EYE);

        renderPulse(g);
    }

    /** Sine-pulsing 1-pixel border hugging the button — the opted-out affordance. */
    private void renderPulse(GuiGraphics g) {
        if (!pulsing || suppressPulse.getAsBoolean()) {
            return;
        }
        long now = Util.getMillis();
        float phase = (float) (now % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
        float wave = (Mth.sin(phase * 2.0F * (float) Math.PI) + 1.0F) * 0.5F;
        // Multiply by widget alpha so the pulse fades out with the button during screen
        // transitions (e.g. the ConfirmLinkScreen swap).
        int alpha = (int) ((int) (wave * PEAK_ALPHA) * this.alpha);
        if (alpha <= 0) {
            return;
        }
        int colour = (alpha << 24) | PULSE_RGB;

        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        g.fill(x - 1, y - 1, x + w + 1, y, colour);         // top edge
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, colour); // bottom edge
        g.fill(x - 1, y, x, y + h, colour);                 // left edge
        g.fill(x + w, y, x + w + 1, y + h, colour);         // right edge
    }
}
