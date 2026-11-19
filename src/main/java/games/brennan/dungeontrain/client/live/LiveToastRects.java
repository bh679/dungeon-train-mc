package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Where the popups (toasts) drew this frame, so the stream can put them back on top of its name tag.
 *
 * <p>The stream's "Name is live" pill is painted onto the stream picture after the frame is copied
 * down ({@link StreamNameTag#drawInto}), so it would cover a popup in the top-middle. Each popup's
 * rectangle is recorded as it draws ({@code ToastInstanceRectMixin}), and after the pill goes on
 * {@link LiveFrameGrabber} copies those rectangles from the game frame over it again.</p>
 *
 * <p>Only popups drawn <em>before</em> the frame is read are recorded by the time it is copied, so a
 * frame read ahead of the popups (Show GUI off, F1, a menu that is never streamed) copies nothing.
 * Cleared at the start of every frame. Off with {@link LiveFeedClientConfig#popupsOverNameTag()}.
 * Render thread only; positions are GUI pixels.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveToastRects {

    /** Vanilla shows at most five popup slots; a few spare for double-height toasts. */
    static final int MAX = 8;

    private static final float[] X = new float[MAX];
    private static final float[] Y = new float[MAX];
    private static final int[] W = new int[MAX];
    private static final int[] H = new int[MAX];
    private static int count;

    private LiveToastRects() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        count = 0;
    }

    /** True while popups should be recorded: streaming, with the switch on. */
    public static boolean active() {
        return LiveStreamController.get().streaming() && LiveFeedClientConfig.popupsOverNameTag();
    }

    /** A popup drew at ({@code x},{@code y}) GUI pixels, {@code w}×{@code h}. */
    public static void record(float x, float y, int w, int h) {
        if (count >= MAX || w <= 0 || h <= 0) return;
        X[count] = x;
        Y[count] = y;
        W[count] = w;
        H[count] = h;
        count++;
    }

    /**
     * Copies each recorded popup from {@code main} onto {@code target} (already holding this frame,
     * name tag included), where {@code crop} is the part of {@code main} the frame shows
     * ({@link LiveAspect#cropRect}). The caller rebinds the main target.
     */
    static void copyOver(RenderTarget main, RenderTarget target, int[] crop) {
        if (count == 0 || !LiveFeedClientConfig.popupsOverNameTag()) return;
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        for (int i = 0; i < count; i++) {
            int[] r = blitRect(X[i], Y[i], W[i], H[i], guiScale, crop, main.height, target.width, target.height);
            if (r == null) continue;
            GL30C.glBlitFramebuffer(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7],
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
        }
    }

    /** {@link #blitRect(float, float, int, int, double, int[], int, int, int)} with the whole window streamed. */
    @Nullable
    static int[] blitRect(float x, float y, int w, int h, double guiScale,
                          int mainW, int mainH, int targetW, int targetH) {
        return blitRect(x, y, w, h, guiScale, new int[] {0, 0, mainW, mainH}, mainH, targetW, targetH);
    }

    /**
     * The blit for one popup: {@code {srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1}} in GL's
     * bottom-up pixels, or null when it falls outside the picture. {@code crop} is the
     * {@code {x0, y0, x1, y1}} part of the {@code mainH}-tall window the picture shows. The destination
     * is rounded outward to whole stream pixels, and the source is that same box scaled up, so the copy
     * lines up exactly with the full-frame copy beneath it.
     */
    @Nullable
    static int[] blitRect(float x, float y, int w, int h, double guiScale,
                          int[] crop, int mainH, int targetW, int targetH) {
        int cropW = crop[2] - crop[0];
        int cropH = crop[3] - crop[1];
        if (cropW <= 0 || cropH <= 0 || mainH <= 0 || targetW <= 0 || targetH <= 0 || guiScale <= 0) return null;
        double cropTop = mainH - crop[3]; // the crop's top edge, in top-down window pixels
        // Multiply before dividing: whole window pixels land on whole stream pixels exactly.
        int left = clamp((int) Math.floor((x * guiScale - crop[0]) * targetW / cropW), targetW);
        int right = clamp((int) Math.ceil(((x + w) * guiScale - crop[0]) * targetW / cropW), targetW);
        int top = clamp((int) Math.floor((y * guiScale - cropTop) * targetH / cropH), targetH);
        int bottom = clamp((int) Math.ceil(((y + h) * guiScale - cropTop) * targetH / cropH), targetH);
        if (right <= left || bottom <= top) return null;
        int dy0 = targetH - bottom;
        int dy1 = targetH - top;
        return new int[] {
            crop[0] + scale(left, cropW, targetW), crop[1] + scale(dy0, cropH, targetH),
            crop[0] + scale(right, cropW, targetW), crop[1] + scale(dy1, cropH, targetH),
            left, dy0, right, dy1
        };
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private static int scale(int v, int main, int target) {
        return (int) Math.round((double) v * main / target);
    }
}
