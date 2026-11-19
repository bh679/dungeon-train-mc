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
 * ({@link LiveHlsSession#localLoop}). The clip keeps its real shape (the streamer's window, via
 * {@link LiveHlsSession#createFullView}); how it's fitted depends on where it shows. Here it is the
 * page's full-screen backdrop, so it is drawn cover-fit: it fills the screen and crops what overflows,
 * never stretched. The page's dip-to-black and vignette are still laid over it by the caller. Nothing to draw until the clip is cut and its first frame is in;
 * the page shows its photo until then. Render thread.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathClipBackground {

    /** The decode is at least this size, in the clip's own shape. */
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
            playing = clip;
        }
        session.touch(ffmpeg);
        if (session.frameSequence() == 0) return false;
        // The texture is the clip's real size, made at its first frame (and again if that ever changed).
        LiveFrameShape.Size size = session.shape();
        if (texture == null || texture.getPixels() == null
                || texture.getPixels().getWidth() != size.width() || texture.getPixels().getHeight() != size.height()) {
            dropTexture();
            texture = session.createFullView(TEXTURE);
            texture.register();
        }
        texture.uploadFrameAtTime(tick, partialTick, false);
        if (!texture.hasPicture()) return false;
        DeathBackgroundPainter.drawCover(g, TEXTURE, size.width(), size.height(), screenW, screenH);
        return true;
    }

    /** Stops the decode and frees the texture. */
    public static void close() {
        dropTexture();
        LiveHlsSession s = session;
        session = null;
        playing = null;
        if (s != null) s.close();
    }

    private static void dropTexture() {
        LiveFeedTexture t = texture;
        texture = null;
        if (t != null) {
            t.unregister();
            t.close();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }
}
