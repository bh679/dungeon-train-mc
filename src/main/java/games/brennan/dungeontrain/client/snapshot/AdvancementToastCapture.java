package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.AdvancementPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.slf4j.Logger;

/**
 * Takes the screenshot a milestone announcement carries: the player's own view, a moment after the
 * advancement toast has slid in, with the chat hidden for that one frame. Asked for by
 * {@code CaptureAdvancementPacket}; the JPEG goes back as {@link AdvancementPhotoPacket}.
 *
 * <p>The toast appears when the client receives the advancement itself, which lands just before
 * the capture request, so a short {@link #SETTLE_MS} wait catches it fully on screen (vanilla keeps
 * it up for ~5 s). Chat is hidden by cancelling the vanilla chat GUI layer while
 * {@link #captureFrame} is set; everything else on the HUD renders as the player sees it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementToastCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wait for the toast's slide-in before grabbing the frame. */
    static final long SETTLE_MS = 1_500L;
    /** Stays under {@link AdvancementPhotoPacket}'s 1 MB codec cap, like the death-screen ride photo. */
    private static final int MAX_BYTES = 1_000_000;

    private static volatile ResourceLocation pendingId;
    private static long captureAtMs;
    private static boolean captureFrame;

    private AdvancementToastCapture() {}

    /** Queue a capture for {@code advancementId}; a newer request replaces an older unsent one. */
    public static void request(ResourceLocation advancementId) {
        pendingId = advancementId;
        captureAtMs = Util.getMillis() + SETTLE_MS;
        captureFrame = false;
    }

    /** Hide the chat on the frame being captured. */
    @SubscribeEvent
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (captureFrame && VanillaGuiLayers.CHAT.equals(event.getName())) {
            event.setCanceled(true);
        }
    }

    /** Decide before the frame renders whether this is the one. */
    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        if (pendingId == null || captureFrame) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { pendingId = null; return; }
        if (Util.getMillis() >= captureAtMs) captureFrame = true;
    }

    /** The frame (HUD included, chat left out) has been drawn — grab it and send it. */
    @SubscribeEvent
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        if (!captureFrame) return;
        ResourceLocation id = pendingId;
        captureFrame = false;
        pendingId = null;
        if (id == null) return;
        byte[] jpeg = null;
        try (NativeImage shot = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            jpeg = SnapshotJpegEncoder.encode(shot, MAX_BYTES);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] advancement screenshot failed: {}", t.toString());
        }
        // An empty image still goes back so the server posts promptly instead of waiting out its timeout.
        DungeonTrainNet.sendToServer(new AdvancementPhotoPacket(id, jpeg != null ? jpeg : new byte[0]));
    }
}
