package games.brennan.dungeontrain.client.videos;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * One platform's toggle on the Videos page toolbar: a square tile carrying that platform's mark,
 * drawn programmatically (no texture assets — the same approach as {@code DiscordIconButton}).
 * Lit while the platform is shown, dimmed to a ghost while it is hidden; the tooltip names the
 * platform and the state, because a dimmed logomark alone does not say which way "off" is.
 *
 * <p>The marks are the recognisable shapes, not the trademarks: a play triangle, a TV with two
 * antennae, a speech bubble with two slits, a camera outline with a lens and a dot. Each is a
 * handful of {@code fill} calls scaled to the tile so the button survives any GUI scale.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PlatformToggleButton extends Button {

    private static final int MARK = 0xFFFFFFFF;
    /** Alpha applied to the whole tile while the toggle is off. */
    private static final int OFF_ALPHA = 0x50;

    private final VideoEntry.Platform platform;
    private final BooleanSupplier lit;

    public PlatformToggleButton(int x, int y, int size, VideoEntry.Platform platform,
                                BooleanSupplier lit, OnPress onPress) {
        super(x, y, size, size, Component.translatable("gui.dungeontrain.videos.platform." + platform.key()),
                onPress, DEFAULT_NARRATION);
        this.platform = platform;
        this.lit = lit;
        refreshTooltip();
    }

    /** Re-read the state into the tooltip — called by the screen after every toggle. */
    public void refreshTooltip() {
        setTooltip(Tooltip.create(Component.translatable(
                lit.getAsBoolean() ? "gui.dungeontrain.videos.filter.platform.shown"
                                   : "gui.dungeontrain.videos.filter.platform.hidden",
                Component.translatable("gui.dungeontrain.videos.platform." + platform.key()))));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean on = lit.getAsBoolean();
        int alpha = on ? 0xFF : OFF_ALPHA;
        int body = withAlpha(isHoveredOrFocused() ? lighten(platform.tileColour()) : platform.tileColour(), alpha);
        int mark = withAlpha(MARK, alpha);
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());

        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);

        switch (platform) {
            case YOUTUBE -> drawPlay(g, x, y, s, mark);
            case BILIBILI -> drawTv(g, x, y, s, mark, body);
            case TWITCH -> drawBubble(g, x, y, s, mark, body);
            case INSTAGRAM -> drawCamera(g, x, y, s, mark);
            case OTHER -> {
                int d = Math.max(2, Math.round(s * 0.2F));
                g.fill(x + (s - d) / 2, y + (s - d) / 2, x + (s + d) / 2, y + (s + d) / 2, mark);
            }
        }
    }

    /** A right-pointing triangle, one pixel column at a time. Shared with the title-screen icon's shape. */
    public static void drawPlay(GuiGraphics g, int x, int y, int s, int colour) {
        int triH = Math.max(3, Math.round(s * 0.46F));
        if ((triH & 1) == 0) triH++;
        int triW = Math.max(2, Math.round(triH * 0.85F));
        int left = x + (s - triW) / 2 + Math.max(1, Math.round(s * 0.05F));
        int midY = y + s / 2;
        for (int i = 0; i < triW; i++) {
            int half = Math.round((triH / 2.0F) * (1.0F - i / (float) triW));
            g.fill(left + i, midY - half, left + i + 1, midY + half + 1, colour);
        }
    }

    /** A TV: rounded body, two antennae above, two eyes punched back out in the body colour. */
    private static void drawTv(GuiGraphics g, int x, int y, int s, int mark, int body) {
        int bodyL = x + Math.round(s * 0.2F);
        int bodyR = x + s - Math.round(s * 0.2F);
        int bodyT = y + Math.round(s * 0.4F);
        int bodyB = y + s - Math.round(s * 0.22F);
        g.fill(bodyL + 1, bodyT, bodyR - 1, bodyB, mark);
        g.fill(bodyL, bodyT + 1, bodyR, bodyB - 1, mark);
        // Antennae: two short diagonals meeting the body's top corners.
        int len = Math.max(2, Math.round(s * 0.18F));
        for (int i = 0; i < len; i++) {
            g.fill(bodyL + 1 + i, bodyT - 1 - (len - i), bodyL + 2 + i, bodyT - (len - i), mark);
            g.fill(bodyR - 2 - i, bodyT - 1 - (len - i), bodyR - 1 - i, bodyT - (len - i), mark);
        }
        // Eyes.
        int eye = Math.max(1, Math.round(s * 0.08F));
        int eyeY = bodyT + (bodyB - bodyT) / 2 - eye / 2;
        g.fill(bodyL + Math.round(s * 0.14F), eyeY, bodyL + Math.round(s * 0.14F) + eye, eyeY + eye + 1, body);
        g.fill(bodyR - Math.round(s * 0.14F) - eye, eyeY, bodyR - Math.round(s * 0.14F), eyeY + eye + 1, body);
    }

    /** A speech bubble with a tail at the bottom-left and two vertical slits. */
    private static void drawBubble(GuiGraphics g, int x, int y, int s, int mark, int body) {
        int l = x + Math.round(s * 0.22F);
        int r = x + s - Math.round(s * 0.22F);
        int t = y + Math.round(s * 0.22F);
        int b = y + s - Math.round(s * 0.34F);
        g.fill(l, t, r, b, mark);
        // Tail: a small block below the left edge.
        int tail = Math.max(1, Math.round(s * 0.1F));
        g.fill(l, b, l + tail + 1, b + tail, mark);
        // Slits.
        int slitW = Math.max(1, Math.round(s * 0.07F));
        int slitT = t + Math.round((b - t) * 0.3F);
        int slitB = b - Math.round((b - t) * 0.3F);
        int gap = (r - l) / 3;
        g.fill(l + gap - slitW / 2, slitT, l + gap - slitW / 2 + slitW, slitB, body);
        g.fill(r - gap - slitW / 2, slitT, r - gap - slitW / 2 + slitW, slitB, body);
    }

    /** A camera: square outline, a lens ring in the centre, a dot in the top-right corner. */
    private static void drawCamera(GuiGraphics g, int x, int y, int s, int mark) {
        int l = x + Math.round(s * 0.22F);
        int r = x + s - Math.round(s * 0.22F);
        int t = y + Math.round(s * 0.22F);
        int b = y + s - Math.round(s * 0.22F);
        g.fill(l, t, r, t + 1, mark);
        g.fill(l, b - 1, r, b, mark);
        g.fill(l, t, l + 1, b, mark);
        g.fill(r - 1, t, r, b, mark);
        // Lens ring: an outline square a third the size, centred.
        int ring = Math.max(3, (r - l) / 2);
        int rl = l + (r - l - ring) / 2;
        int rt = t + (b - t - ring) / 2;
        g.fill(rl, rt, rl + ring, rt + 1, mark);
        g.fill(rl, rt + ring - 1, rl + ring, rt + ring, mark);
        g.fill(rl, rt, rl + 1, rt + ring, mark);
        g.fill(rl + ring - 1, rt, rl + ring, rt + ring, mark);
        // Dot.
        g.fill(r - 3, t + 2, r - 2, t + 3, mark);
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** Nudge each channel toward white for the hover state. */
    private static int lighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 0x22);
        int gr = Math.min(255, ((argb >> 8) & 0xFF) + 0x22);
        int b = Math.min(255, (argb & 0xFF) + 0x22);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }
}
