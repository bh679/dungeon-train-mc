package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import net.mehvahdjukaar.moonlight.api.util.math.Vec2i;
import net.mehvahdjukaar.vista.VistaModClient;
import net.mehvahdjukaar.vista.client.CrtOverlay;
import net.mehvahdjukaar.vista.client.textures.TvScreenVertexConsumers;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.client.web.MediaStatus;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.mehvahdjukaar.vista.common.tv.IntAnimationState;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Vista {@link IVideoSource} that is the Live Feed: the picture every TV holding a Live Feed Cassette
 * ({@code compat/vista/LiveBroadcastLocation} → {@code LiveBroadcastSource}) shows.
 *
 * <p>Static when nobody is live (or the viewer cap is full), Vista's "downloading" bars while its
 * ffmpeg is fetched, the waiting pattern while the first frames decode, then the stream. One
 * {@link LiveHlsSession} per playlist URL is shared by every TV; a new session id in the relay's
 * status (a takeover) swaps to a fresh session, which is how viewers follow the new streamer.</p>
 */
public final class LiveFeedSource implements IVideoSource {

    public static final LiveFeedSource MAIN = new LiveFeedSource();

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live/main");

    @Nullable private LiveHlsSession session;
    @Nullable private LiveFeedTexture texture;

    private LiveFeedSource() {}

    @Override
    public @NotNull VertexConsumer getVideoFrameBuilder(TVBlockEntity tv, float partialTick, MultiBufferSource buffer,
                                                        boolean shouldUpdate, Vec2i screenSize, Vec2i pixelEffectRes,
                                                        int videoAnimationTick, boolean paused,
                                                        IntAnimationState switchAnim, IntAnimationState staticAnim,
                                                        boolean showsTime) {
        if (!LiveFeedClientConfig.viewingEnabled()) {
            return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
        }
        Status status = LiveStatusPoller.wanted();
        if (!status.live() || !status.slot() || status.playlistUrl() == null) {
            // Keep the last picture briefly under the switch animation? No — static is the honest
            // signal that the broadcast is over.
            return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
        }
        switch (FfmpegSupport.state()) {
            case DOWNLOADING -> {
                int pct = VistaModClient.getFFmpegDownloadProgress();
                return pct >= 0
                    ? TvScreenVertexConsumers.getDownloadingVc(buffer, pixelEffectRes, pct, switchAnim)
                    : TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, videoAnimationTick, switchAnim);
            }
            case OFF, FAILED -> {
                return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
            }
            default -> { /* ready */ }
        }
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (ffmpeg == null) return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);

        LiveHlsSession s = sessionFor(status.playlistUrl());
        s.touch(ffmpeg);
        LiveFeedTexture tex = texture;
        if (tex == null) return TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, videoAnimationTick, switchAnim);
        MediaStatus st = tex.uploadFrameAtTime(videoAnimationTick, partialTick, paused);
        if (!tex.hasPicture()) {
            return TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, videoAnimationTick, switchAnim);
        }
        CrtOverlay overlay = st == MediaStatus.BUFFERING ? CrtOverlay.LOADING : CrtOverlay.NONE;
        return TvScreenVertexConsumers.getSingleTextureVC(buffer, TEXTURE, overlay, pixelEffectRes, switchAnim, staticAnim);
    }

    /** Render thread. */
    private LiveHlsSession sessionFor(String playlistUrl) {
        LiveHlsSession s = session;
        if (s != null && s.playlistUrl().equals(playlistUrl)) return s;
        dropSession();
        int w = LiveFeedClientConfig.viewerWidth();
        int h = LiveFeedClientConfig.heightFor(w);
        s = new LiveHlsSession(playlistUrl, w, h);
        LiveFeedTexture t = (LiveFeedTexture) s.createTextureView(TEXTURE);
        t.register();
        session = s;
        texture = t;
        return s;
    }

    /** Render thread: forget the current session and its texture (world unload, takeover). */
    public void dropSession() {
        LiveFeedTexture t = texture;
        if (t != null) { t.unregister(); t.close(); texture = null; }
        LiveHlsSession s = session;
        if (s != null) { s.close(); session = null; }
    }
}
