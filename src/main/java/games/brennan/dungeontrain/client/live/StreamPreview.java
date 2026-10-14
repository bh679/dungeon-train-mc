package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * The Options → Stream preview: a copy of this frame taken at the point the broadcast would read it
 * with no menu open ({@link StreamCapture#previewPoint}), at the broadcast's own resolution.
 *
 * <p>Render thread only. Copies happen only while a {@link StreamPreviewWidget} has drawn within the
 * last few frames, and the copy is a GPU blit with no read-back, so an open Stream tab costs next to
 * nothing and a closed one costs nothing. The GUI pass runs before the open screen, so the widget
 * draws the frame it is sitting on top of. The target is freed shortly after the widget stops
 * asking.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamPreview {

    private static final long ACTIVE_NS = 250_000_000L;
    private static final int RELEASE_TICKS = 40;

    @Nullable private static TextureTarget target;
    private static long requestedNs;
    private static boolean capturedThisFrame;
    private static boolean hasFrame;
    private static int idleTicks;

    private StreamPreview() {}

    /** Widget: keep the preview capturing. */
    public static void request() {
        requestedNs = System.nanoTime();
    }

    /** The preview texture, or null until a frame has been taken. */
    @Nullable
    public static TextureTarget frame() {
        return hasFrame ? target : null;
    }

    private static boolean active() {
        return Minecraft.getInstance().level != null && System.nanoTime() - requestedNs < ACTIVE_NS;
    }

    private static StreamCapture.Point point() {
        return StreamCapture.previewPoint(LiveFeedClientConfig.streamGui(), LiveStreamController.streamChatShown());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onGuiStart(RenderGuiEvent.Pre event) {
        capturedThisFrame = false;
        if (active() && point() == StreamCapture.Point.BEFORE_GUI) capture(event.getGuiGraphics());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (VanillaGuiLayers.CHAT.equals(event.getName()) && active() && point() == StreamCapture.Point.BEFORE_CHAT) {
            capture(event.getGuiGraphics());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onGuiEnd(RenderGuiEvent.Post event) {
        if (active() && point() == StreamCapture.Point.AFTER_GUI) capture(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (active()) {
            idleTicks = 0;
        } else if (target != null && ++idleTicks > RELEASE_TICKS) {
            release();
        }
    }

    private static void capture(GuiGraphics graphics) {
        if (capturedThisFrame) return;
        capturedThisFrame = true;
        graphics.flush();
        int w = LiveFeedClientConfig.captureWidth();
        int h = LiveFeedClientConfig.heightFor(w);
        if (target == null || target.width != w || target.height != h) {
            release();
            target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            target.setFilterMode(GL11C.GL_NEAREST); // show the broadcast's pixels, not a smoothed upscale
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GL30C.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, w, h,
            GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
        StreamNameTag.drawInto(target); // rebinds the main target
        hasFrame = true;
    }

    private static void release() {
        if (target != null) target.destroyBuffers();
        target = null;
        hasFrame = false;
    }
}
