package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.function.Consumer;

/**
 * The streamer's viewfinder: while the camcorder is broadcasting, two thick rounded frame corners
 * sit top-left and bottom-right and, inside that frame in the top-right, a red record dot blinks
 * next to a large steady "LIVE" — the
 * way a camera's own screen looks. Only the wearer sees it.
 *
 * <p>Drawn once the whole frame is finished ({@link RenderFrameEvent.Post}, at high priority so it
 * lands before {@link LiveStreamController} grabs the frame for the stream): above the HUD, above
 * any open screen such as the inventory, and above other mods' corner widgets, exactly as the
 * recording shows them. F1 hides it with the rest of the HUD only while no screen is open.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveRecOverlay {

    static final int MARGIN = 14;
    static final int CORNER_LEN = 40;
    static final int LINE = 5;
    static final int RADIUS = 12;
    static final int DOT_RADIUS = 7;
    /** The badge sits inside the frame, this far in from the corner arms. */
    static final int BADGE_PAD = 10;
    static final float BADGE_SCALE = 2f;
    static final long BLINK_MS = 500;
    static final int LABEL_GAP = 5;
    private static final Component LABEL = Component.translatable("gui.dungeontrain.live.badge");
    private static final int WHITE = 0xF0FFFFFF;
    private static final int RED = 0xFFE62E2E;

    private LiveRecOverlay() {}

    /** On for the first half of every second — the blink every camcorder has. */
    static boolean dotOn(long nowMs) {
        return (nowMs / BLINK_MS) % 2 == 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !LiveStreamController.get().streaming()) return;
        if (mc.screen == null && mc.options.hideGui) return;
        guiPass(LiveRecOverlay::draw);
    }

    /**
     * Draw {@code painter} straight onto the main target after the frame is finished, with the same
     * orthographic setup vanilla uses for the HUD (so GUI pixels are GUI pixels), and restore the
     * matrices afterwards. Shared with {@link LiveHeadViewer}.
     */
    static void guiPass(Consumer<GuiGraphics> painter) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, ClientHooks.getGuiFarPlane()),
                VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            mc.getMainRenderTarget().bindWrite(true);
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            painter.accept(g);
            g.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
        }
    }

    /** Right edge of the space inside the viewfinder frame, where the badge is right-aligned. */
    static int innerRight(int guiWidth) {
        return guiWidth - MARGIN - LINE - BADGE_PAD;
    }

    /** Bottom of the LIVE badge, in GUI pixels; anything stacked under it starts below this. */
    static int badgeBottom(int fontLineHeight) {
        return MARGIN + LINE + BADGE_PAD + Math.round(fontLineHeight * BADGE_SCALE);
    }

    private static void draw(GuiGraphics g) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        corner(g, MARGIN, MARGIN, 1, 1);                 // top-left, arms go right and down
        corner(g, w - MARGIN, h - MARGIN, -1, -1);       // bottom-right, arms go left and up
        // Inside the frame, tucked into the top-right: the dot blinks, the label does not.
        var font = Minecraft.getInstance().font;
        int innerRight = w - MARGIN - LINE - BADGE_PAD;
        int innerTop = MARGIN + LINE + BADGE_PAD;
        int textH = Math.round(font.lineHeight * BADGE_SCALE);
        int cy = innerTop + textH / 2;
        int dotX = innerRight - DOT_RADIUS;
        if (dotOn(System.currentTimeMillis())) disc(g, dotX, cy, DOT_RADIUS, RED);
        int textW = Math.round(font.width(LABEL) * BADGE_SCALE);
        int tx = dotX - DOT_RADIUS - LABEL_GAP - textW;
        g.pose().pushPose();
        g.pose().translate(tx, innerTop, 0);
        g.pose().scale(BADGE_SCALE, BADGE_SCALE, 1f);
        g.drawString(font, LABEL, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
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
