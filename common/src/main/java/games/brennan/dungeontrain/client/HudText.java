package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Helper that wraps the {@code PoseStack.scale} pattern for HUD text. Both
 * {@link VersionHudOverlay} and {@link EditorStatusHudOverlay} route their
 * drawing through this so a single {@link ClientDisplayConfig#getHudScale()}
 * controls every 2D HUD label uniformly.
 *
 * <p>Minecraft's {@link Font} is a fixed-pixel bitmap — to render at a
 * different size you scale the {@link com.mojang.blaze3d.vertex.PoseStack}
 * before drawing. Drawing at {@code (x / scale, y / scale)} keeps the visual
 * on-screen position equal to the caller's {@code (x, y)} despite the scale.
 *
 * <p>Layout helpers ({@link #scaledWidth}, {@link #scaledLineHeight}) return
 * the post-scale dimensions in screen pixels so callers can centre text and
 * size backdrop rectangles consistently with the smaller (or larger) glyphs.
 */
public final class HudText {

    private HudText() {}

    /** Current HUD font scale. {@code 1.0} = vanilla. */
    public static float scale() {
        return (float) ClientDisplayConfig.getHudScale();
    }

    public static void drawScaled(GuiGraphics g, Font font, Component label, int x, int y, int color, boolean shadow) {
        float s = scale();
        if (s == 1.0f) {
            g.drawString(font, label, x, y, color, shadow);
            return;
        }
        g.pose().pushPose();
        g.pose().scale(s, s, 1.0f);
        g.drawString(font, label, Math.round(x / s), Math.round(y / s), color, shadow);
        g.pose().popPose();
    }

    public static void drawScaled(GuiGraphics g, Font font, String label, int x, int y, int color, boolean shadow) {
        float s = scale();
        if (s == 1.0f) {
            g.drawString(font, label, x, y, color, shadow);
            return;
        }
        g.pose().pushPose();
        g.pose().scale(s, s, 1.0f);
        g.drawString(font, label, Math.round(x / s), Math.round(y / s), color, shadow);
        g.pose().popPose();
    }

    public static int scaledWidth(Font font, Component label) {
        return Math.round(font.width(label) * scale());
    }

    public static int scaledWidth(Font font, String label) {
        return Math.round(font.width(label) * scale());
    }

    public static int scaledLineHeight(Font font) {
        return Math.round(font.lineHeight * scale());
    }
}
