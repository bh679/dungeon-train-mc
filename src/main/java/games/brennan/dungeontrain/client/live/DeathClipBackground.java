package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.DeathBackgroundPainter;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The streamer's death clip ({@link LiveDeathClip}) looped as the opening death page's backdrop, in
 * place of its ride photo: decoded by the same ffmpeg → texture path the TVs use
 * ({@link LiveHlsSession#localLoop}), drawn cover-fit, with the page's dip-to-black and vignette
 * still laid over it by the caller. Nothing to draw until the clip is cut and its first frame is in;
 * the page shows its photo until then. Render thread.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathClipBackground {

    static final int WIDTH = LiveDeathClip.WIDTH;
    static final int HEIGHT = WIDTH * 9 / 16;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live/death_clip");

    @Nullable private static LiveHlsSession session;
    @Nullable private static LiveFeedTexture texture;
    @Nullable private static Path playing;

    private DeathClipBackground() {}

    /** Draws the clip cover-fit over the whole screen; false (nothing drawn) while there is none to show. */
    public static boolean draw(GuiGraphics g, int screenW, int screenH, int tick, float partialTick) {
        Path clip = LiveDeathClip.ready();
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (clip == null || ffmpeg == null) return false;
        if (session == null || !clip.equals(playing)) {
            close();
            session = LiveHlsSession.localLoop(clip, WIDTH, HEIGHT);
            texture = (LiveFeedTexture) session.createTextureView(TEXTURE);
            texture.register();
            playing = clip;
        }
        session.touch(ffmpeg);
        texture.uploadFrameAtTime(tick, partialTick, false);
        if (!texture.hasPicture()) return false;
        DeathBackgroundPainter.drawCover(g, TEXTURE, WIDTH, HEIGHT, screenW, screenH);
        return true;
    }

    /** Stops the decode and frees the texture. */
    public static void close() {
        LiveFeedTexture t = texture;
        LiveHlsSession s = session;
        texture = null;
        session = null;
        playing = null;
        if (t != null) {
            t.unregister();
            t.close();
        }
        if (s != null) s.close();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }
}
