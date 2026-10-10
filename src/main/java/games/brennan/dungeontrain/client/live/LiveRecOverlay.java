package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The streamer's viewfinder: while the camcorder is broadcasting, two thick rounded frame corners
 * sit top-left and bottom-right and a red record dot blinks top-right, level with the frame — the
 * way a camera's own screen looks. Only the wearer sees it.
 *
 * <p>Drawn after everything else: after the whole HUD ({@link RenderGuiEvent.Post}) and, when a
 * screen such as the inventory is open, after that screen ({@link ScreenEvent.Render.Post}), so it
 * stays on top of menus exactly as the recording shows them. F1 hides it with the rest of the HUD
 * only while no screen is open.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveRecOverlay {

    static final int MARGIN = 14;
    static final int CORNER_LEN = 40;
    static final int LINE = 5;
    static final int RADIUS = 12;
    static final int DOT_RADIUS = 5;
    static final long BLINK_MS = 500;
    private static final int WHITE = 0xF0FFFFFF;
    private static final int RED = 0xFFE62E2E;

    private LiveRecOverlay() {}

    /** On for the first half of every second — the blink every camcorder has. */
    static boolean dotOn(long nowMs) {
        return (nowMs / BLINK_MS) % 2 == 0;
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) return;
        draw(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onScreen(ScreenEvent.Render.Post event) {
        draw(event.getGuiGraphics());
    }

    private static void draw(GuiGraphics g) {
        if (!LiveStreamController.get().streaming()) return;
        int w = g.guiWidth();
        int h = g.guiHeight();
        corner(g, MARGIN, MARGIN, 1, 1);                 // top-left, arms go right and down
        corner(g, w - MARGIN, h - MARGIN, -1, -1);       // bottom-right, arms go left and up
        if (dotOn(System.currentTimeMillis())) {
            // Level with the top frame bar: centre on its middle line, flush with the right margin.
            disc(g, w - MARGIN - DOT_RADIUS, MARGIN + LINE / 2, DOT_RADIUS, RED);
        }
    }

    /**
     * A thick L with a rounded elbow at ({@code ox},{@code oy}); {@code sx}/{@code sy} are ±1 and say
     * which way the two arms run. The shape is a pixel mask in top-left orientation, mirrored into
     * place pixel by pixel so every orientation seams exactly the same way.
     */
    private static void corner(GuiGraphics g, int ox, int oy, int sx, int sy) {
        for (int lx = 0; lx < CORNER_LEN; lx++) {
            for (int ly = 0; ly < CORNER_LEN; ly++) {
                if (!inCorner(lx, ly)) continue;
                int x0 = sx > 0 ? ox + lx : ox - lx - 1;
                int y0 = sy > 0 ? oy + ly : oy - ly - 1;
                g.fill(x0, y0, x0 + 1, y0 + 1, WHITE);
            }
        }
    }

    /** Local-space membership: the two straight arms past the elbow, plus the quarter ring of the elbow. */
    static boolean inCorner(int lx, int ly) {
        if (ly < LINE && lx >= RADIUS) return true;
        if (lx < LINE && ly >= RADIUS) return true;
        if (lx < RADIUS && ly < RADIUS) {
            double d = Math.hypot(RADIUS - (lx + 0.5), RADIUS - (ly + 0.5));
            return d <= RADIUS && d >= RADIUS - LINE;
        }
        return false;
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy < r; dy++) {
            int half = (int) Math.floor(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }
}
