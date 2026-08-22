package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying the Bilibili mark — the blue rounded tile with the
 * white antennaed TV on it — drawn programmatically (scaled to the button size) so no texture asset
 * needs shipping, the same approach {@link DiscordIconButton} and {@link PatreonIconButton} take.
 *
 * <p>Shown only to Chinese-language clients, one slot above Discord in the icon column (see
 * {@code TitleScreenCreditsButton}), because Discord — the community link directly below it — is
 * blocked in mainland China and is a dead end for exactly those players.</p>
 *
 * <p>No pulse behaviour: the sine pulse on {@link DiscordIconButton} is the opted-out-of-the-welcome-
 * popup affordance, and two pulsing title-screen icons would compete with each other.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BilibiliIconButton extends Button {

    private static final int BLUE       = 0xFF00A1D6;
    private static final int BLUE_HOVER = 0xFF2FB9E5;
    private static final int FACE       = 0xFFFFFFFF;

    /** Diagonal steps in each antenna — enough to read as a slant at 20px without turning to mush. */
    private static final int ANTENNA_STEPS = 3;

    public BilibiliIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int body = isHoveredOrFocused() ? BLUE_HOVER : BLUE;

        // Blue tile, corners nipped so it reads as the rounded app icon rather than a square.
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);

        // The TV: a white outline box sitting low on the tile, hollow so the tile colour reads
        // through it — the mark is a drawn outline, not a filled screen.
        int tvLeft = x + Math.round(s * 0.22F);
        int tvRight = x + getWidth() - Math.round(s * 0.22F);
        int tvTop = y + Math.round(s * 0.45F);
        int tvBottom = y + getHeight() - Math.round(s * 0.16F);
        int stroke = Math.max(1, Math.round(s * 0.06F));
        g.fill(tvLeft, tvTop, tvRight, tvBottom, FACE);
        g.fill(tvLeft + stroke, tvTop + stroke, tvRight - stroke, tvBottom - stroke, body);

        // Two antennae, flaring up and outward from the top corners of the box. Stepped rather than
        // drawn, because GuiGraphics only fills axis-aligned rectangles.
        int step = Math.max(1, Math.round(s * 0.07F));
        for (int i = 0; i < ANTENNA_STEPS; i++) {
            int up = tvTop - (i + 1) * step;
            g.fill(tvLeft - (i + 1) * step, up, tvLeft - i * step, up + step, FACE);
            g.fill(tvRight + i * step, up, tvRight + (i + 1) * step, up + step, FACE);
        }

        // Two eyes inside the box, placed symmetrically either side of its centre — measured out
        // from the middle rather than at quarter-widths, so integer division can't bias one eye
        // a pixel closer to its wall than the other at this size.
        int eye = Math.max(1, Math.round(s * 0.08F));
        int eyeTop = (tvTop + tvBottom) / 2 - eye / 2;
        int centreX = (tvLeft + tvRight) / 2;
        int spread = Math.max(1, Math.round(s * 0.10F));
        g.fill(centreX - spread - eye, eyeTop, centreX - spread, eyeTop + eye, FACE);
        g.fill(centreX + spread, eyeTop, centreX + spread + eye, eyeTop + eye, FACE);
    }
}
