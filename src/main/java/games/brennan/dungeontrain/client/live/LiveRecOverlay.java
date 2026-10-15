package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * The streamer's viewfinder: while the camcorder is broadcasting, two thick rounded frame corners
 * sit top-left and bottom-right and, inside the top-left corner, a red record dot blinks
 * next to a large steady "LIVE" — the
 * way a camera's own screen looks. Under LIVE, smaller, an eye and how many are watching (players at
 * a TV or wearing one, plus website viewers — {@link LiveStreamController#viewerCount()}); it
 * appears with the relay's first heartbeat reply. The top-right stays clear for the pinned live
 * viewer ({@link LiveHeadViewer}). Only the wearer sees it.
 *
 * <p>Drawn once the whole frame is finished ({@link RenderFrameEvent.Post}) at {@link EventPriority#LOW},
 * i.e. after {@link LiveStreamController} has grabbed the frame for the stream (NORMAL), so the
 * viewfinder never reaches the broadcast. On screen it still sits above the HUD, above any open
 * screen such as the inventory, and above other mods' corner widgets; {@link LiveHeadViewer}
 * (LOWEST) layers over it. F1 hides it with the rest of the HUD only while no screen is open.</p>
 *
 * <p>While the stream carries on after death ({@link LivePostMortem}) an End Stream button sits right
 * of LIVE. It is drawn in the same pass, so it never reaches the broadcast either; being outside any
 * screen's widget list, its click is caught in {@link #onMousePressed}.</p>
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
    /** The viewer count row under LIVE: normal-size text, this far below the badge. */
    static final int COUNT_GAP = 4;
    static final int GLYPH_GAP = 3;
    /** An open eye with its pupil, one cell per GUI pixel; 7 tall like a digit. */
    static final String[] EYE = {
        "....###....",
        "..##...##..",
        ".#..###..#.",
        "#..#####..#",
        ".#..###..#.",
        "..##...##..",
        "....###....",
    };
    private static final Component LABEL = Component.translatable("gui.dungeontrain.live.badge");
    private static final Component END_STREAM = Component.translatable("gui.dungeontrain.live.end_stream");
    /** The End Stream button: this far right of LIVE, padded around its label, vanilla button sprites. */
    static final int END_GAP = 10;
    static final int END_PAD_X = 6;
    static final int END_H = 20;
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HOVER = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final int END_TEXT = 0xFFFF5555;
    private static final int WHITE = 0xF0FFFFFF;
    private static final int RED = 0xFFE62E2E;
    /** The corner mask as runs of set pixels, {lx0, lx1, ly} in local space — worked out once, not per frame. */
    static final int[][] CORNER_SPANS = spans(CORNER_LEN, CORNER_LEN, LiveRecOverlay::inCorner);
    /** The eye's runs, same layout. */
    static final int[][] EYE_SPANS = spans(EYE[0].length(), EYE.length, (col, row) -> EYE[row].charAt(col) == '#');
    /** Reused by {@link #guiPass} each frame instead of two fresh matrices. */
    private static final Matrix4f SAVED_PROJECTION = new Matrix4f();
    private static final Matrix4f GUI_PROJECTION = new Matrix4f();

    private LiveRecOverlay() {}

    /** On for the first half of every second — the blink every camcorder has. */
    static boolean dotOn(long nowMs) {
        return (nowMs / BLINK_MS) % 2 == 0;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
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
        Matrix4f savedProjection = SAVED_PROJECTION.set(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(GUI_PROJECTION.setOrtho(0f, w, h, 0f, 1000f, ClientHooks.getGuiFarPlane()),
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

    /** Left edge of the space inside the viewfinder's top-left corner, where the badge starts. */
    static int innerLeft() {
        return MARGIN + LINE + BADGE_PAD;
    }

    /** Top of the viewer count row, just under LIVE. */
    static int countTop(int fontLineHeight) {
        return MARGIN + LINE + BADGE_PAD + Math.round(fontLineHeight * BADGE_SCALE) + COUNT_GAP;
    }

    private static void draw(GuiGraphics g) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        corner(g, MARGIN, MARGIN, 1, 1);                 // top-left, arms go right and down
        corner(g, w - MARGIN, h - MARGIN, -1, -1);       // bottom-right, arms go left and up
        // Inside the top-left corner: the dot blinks, the label does not.
        var font = Minecraft.getInstance().font;
        int left = innerLeft();
        int innerTop = MARGIN + LINE + BADGE_PAD;
        int textH = Math.round(font.lineHeight * BADGE_SCALE);
        int cy = innerTop + textH / 2;
        int dotX = left + DOT_RADIUS;
        if (dotOn(System.currentTimeMillis())) disc(g, dotX, cy, DOT_RADIUS, RED);
        int tx = dotX + DOT_RADIUS + LABEL_GAP;
        g.pose().pushPose();
        g.pose().translate(tx, innerTop, 0);
        g.pose().scale(BADGE_SCALE, BADGE_SCALE, 1f);
        g.drawString(font, LABEL, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
        if (LiveStreamController.get().postMortem()) endStreamButton(g, font);
        int viewers = LiveStreamController.get().viewerCount();
        if (viewers >= 0) viewerCount(g, font, left, countTop(font.lineHeight), viewers);
    }

    /** The End Stream button's place: right of LIVE, centred on it. */
    static LiveHeadViewer.Box endStreamBox(Font font) {
        int innerTop = MARGIN + LINE + BADGE_PAD;
        int textH = Math.round(font.lineHeight * BADGE_SCALE);
        int labelRight = innerLeft() + 2 * DOT_RADIUS + LABEL_GAP + Math.round(font.width(LABEL) * BADGE_SCALE);
        int w = font.width(END_STREAM) + 2 * END_PAD_X;
        return new LiveHeadViewer.Box(labelRight + END_GAP, innerTop + (textH - END_H) / 2, w, END_H);
    }

    private static void endStreamButton(GuiGraphics g, Font font) {
        LiveHeadViewer.Box b = endStreamBox(font);
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double mx = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double my = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        boolean hover = LiveStreamSettingsButton.contains(b, mx, my);
        g.blitSprite(hover ? BUTTON_HOVER : BUTTON, b.x(), b.y(), b.w(), b.h());
        g.drawString(font, END_STREAM, b.x() + END_PAD_X, b.y() + (b.h() - font.lineHeight) / 2 + 1, END_TEXT, true);
    }

    /** A click on the End Stream button ends the post-mortem stream and goes no further. */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        LiveStreamController live = LiveStreamController.get();
        if (!live.postMortem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!LiveStreamSettingsButton.contains(endStreamBox(mc.font), event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
        live.endPostMortem();
    }

    /** From {@code left}: the eye, then the number, both at normal GUI size. */
    private static void viewerCount(GuiGraphics g, Font font, int left, int top, int viewers) {
        for (int[] run : EYE_SPANS) g.fill(left + run[0], top + run[2], left + run[1], top + run[2] + 1, WHITE);
        g.drawString(font, Integer.toString(viewers), left + EYE[0].length() + GLYPH_GAP, top, 0xFFFFFFFF, true);
    }

    /**
     * A thick L with a rounded elbow at ({@code ox},{@code oy}); {@code sx}/{@code sy} are ±1 and say
     * which way the two arms run. The shape is a pixel mask in top-left orientation
     * ({@link #CORNER_SPANS}), mirrored into place run by run so every orientation seams exactly the
     * same way.
     */
    private static void corner(GuiGraphics g, int ox, int oy, int sx, int sy) {
        for (int[] run : CORNER_SPANS) {
            int x0 = sx > 0 ? ox + run[0] : ox - run[1];
            int x1 = sx > 0 ? ox + run[1] : ox - run[0];
            int y0 = sy > 0 ? oy + run[2] : oy - run[2] - 1;
            g.fill(x0, y0, x1, y0 + 1, WHITE);
        }
    }

    /** A {@code w}×{@code h} mask as horizontal runs {x0 (inclusive), x1 (exclusive), y}. */
    static int[][] spans(int w, int h, BiPredicate<Integer, Integer> set) {
        List<int[]> runs = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!set.test(x, y)) { x++; continue; }
                int start = x;
                while (x < w && set.test(x, y)) x++;
                runs.add(new int[] {start, x, y});
            }
        }
        return runs.toArray(new int[0][]);
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
