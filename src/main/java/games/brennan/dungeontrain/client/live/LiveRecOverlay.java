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
     * which way the two arms run. Straight arms are two fills; the elbow is a ring quadrant.
     */
    private static void corner(GuiGraphics g, int ox, int oy, int sx, int sy) {
        bar(g, ox + sx * RADIUS, oy, ox + sx * CORNER_LEN, oy + sy * LINE);
        bar(g, ox, oy + sy * RADIUS, ox + sx * LINE, oy + sy * CORNER_LEN);
        int cx = ox + sx * RADIUS, cy = oy + sy * RADIUS; // elbow centre
        int inner = RADIUS - LINE;
        for (int dx = 0; dx < RADIUS; dx++) {
            for (int dy = 0; dy < RADIUS; dy++) {
                double d = Math.hypot(dx + 0.5, dy + 0.5);
                if (d <= RADIUS && d >= inner) {
                    int px = cx - sx * (dx + 1), py = cy - sy * (dy + 1);
                    g.fill(Math.min(px, px + 1), Math.min(py, py + 1), Math.max(px, px + 1), Math.max(py, py + 1), WHITE);
                }
            }
        }
    }

    private static void bar(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), WHITE);
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy < r; dy++) {
            int half = (int) Math.floor(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }
}
