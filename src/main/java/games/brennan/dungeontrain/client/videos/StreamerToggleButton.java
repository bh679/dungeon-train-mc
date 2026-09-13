package games.brennan.dungeontrain.client.videos;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * The <b>Twitch streamers</b> toggle on the Videos page toolbar, beside the platform icons: the same
 * purple tile as the Twitch toggle but carrying a "live" mark — a filled dot with two broadcast arcs —
 * so the two Twitch buttons read as "clips" and "streamers" at a glance. Lit while streamer rows are
 * shown, dimmed while they are hidden; the tooltip says which, like {@link PlatformToggleButton}.
 */
@OnlyIn(Dist.CLIENT)
public final class StreamerToggleButton extends Button {

    private static final int MARK = 0xFFFFFFFF;
    private static final int OFF_ALPHA = 0x50;

    private final BooleanSupplier lit;

    public StreamerToggleButton(int x, int y, int size, BooleanSupplier lit, OnPress onPress) {
        super(x, y, size, size, Component.translatable("gui.dungeontrain.videos.streamers.label"), onPress, DEFAULT_NARRATION);
        this.lit = lit;
        refreshTooltip();
    }

    /** Re-read the state into the tooltip — called by the screen after every toggle. */
    public void refreshTooltip() {
        setTooltip(Tooltip.create(Component.translatable(
                lit.getAsBoolean() ? "gui.dungeontrain.videos.filter.platform.shown"
                                   : "gui.dungeontrain.videos.filter.platform.hidden",
                Component.translatable("gui.dungeontrain.videos.streamers.label"))));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean on = lit.getAsBoolean();
        int alpha = on ? 0xFF : OFF_ALPHA;
        int tile = VideoEntry.Platform.TWITCH.tileColour();
        int body = PlatformToggleButton.tileBody(tile, isHoveredOrFocused(), alpha);
        int mark = (MARK & 0x00FFFFFF) | (alpha << 24);
        int x = getX();
        int y = getY();
        int s = Math.min(getWidth(), getHeight());
        int inset = Math.max(1, Math.round(s * 0.08F));
        g.fill(x + inset, y, x + getWidth() - inset, y + getHeight(), body);
        g.fill(x, y + inset, x + getWidth(), y + getHeight() - inset, body);
        drawLive(g, x, y, s, mark);
    }

    /**
     * The live mark: a filled dot low-centre with two concentric arcs opening upward — the
     * broadcast glyph. Drawn as row fills so it survives any GUI scale. Shared with the list's
     * streamer-row tile.
     */
    static void drawLive(GuiGraphics g, int x, int y, int s, int colour) {
        int cx = x + s / 2;
        int cy = y + s - Math.round(s * 0.34F);
        int dot = Math.max(2, Math.round(s * 0.16F));
        g.fill(cx - dot / 2, cy - dot / 2, cx - dot / 2 + dot, cy - dot / 2 + dot, colour);
        // Two arcs: rings of radius r1 < r2 above the dot, one pixel thick, drawn as row segments.
        int r1 = Math.max(3, Math.round(s * 0.26F));
        int r2 = Math.max(r1 + 2, Math.round(s * 0.40F));
        for (int r : new int[] {r1, r2}) {
            for (int dy = -r; dy <= 0; dy++) {
                double outer = Math.sqrt((double) r * r - (double) dy * dy);
                double inner = Math.sqrt(Math.max(0, (double) (r - 1) * (r - 1) - (double) dy * dy));
                int o = (int) Math.round(outer);
                int in = (int) Math.round(inner);
                if (o <= in) in = o - 1;
                if (in < 0) in = 0;
                g.fill(cx - o, cy + dy, cx - in, cy + dy + 1, colour);
                g.fill(cx + in + 1, cy + dy, cx + o + 1, cy + dy + 1, colour);
            }
        }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.videos.filter.streamers.narration"));
    }
}
