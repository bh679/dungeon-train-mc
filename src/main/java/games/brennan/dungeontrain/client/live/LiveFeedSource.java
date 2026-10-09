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
    /** The next session, decoding in the background; it replaces {@link #session} at its first frame. */
    @Nullable private LiveHlsSession pending;
    @Nullable private LiveFeedTexture pendingTexture;
    private static final ResourceLocation PENDING_TEXTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live/main_next");

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
        boolean replay = false;
        String url;
        if (status.live() && status.slot() && status.playlistUrl() != null) {
            url = status.playlistUrl();
        } else if (!status.live() && status.replayUrl() != null) {
            // Nobody is on: the last ten seconds of the previous broadcast, looped, in black and white.
            url = status.replayUrl();
            replay = true;
        } else {
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

        LiveHlsSession s = sessionFor(url, replay, ffmpeg);
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

    /**
     * Render thread. A source change (takeover, replay ↔ live) does not cut straight over: the new
     * session decodes alongside the old one and takes the screen at its first frame, so a viewer
     * never sees "waiting" between two pictures. With no picture yet, the switch is immediate.
     */
    private LiveHlsSession sessionFor(String url, boolean replay, FFmpeg ffmpeg) {
        LiveHlsSession s = session;
        if (s != null && s.playlistUrl().equals(url) && s.isReplay() == replay) {
            dropPending();
            return s;
        }
        if (s == null || texture == null || !texture.hasPicture()) {
            dropSession();
            session = newSession(url, replay);
            texture = (LiveFeedTexture) session.createTextureView(TEXTURE);
            texture.register();
            return session;
        }
        LiveHlsSession p = pending;
        if (p == null || !p.playlistUrl().equals(url) || p.isReplay() != replay) {
            dropPending();
            pending = newSession(url, replay);
            pendingTexture = (LiveFeedTexture) pending.createTextureView(PENDING_TEXTURE);
            pendingTexture.register();
            p = pending;
        }
        p.touch(ffmpeg);
        if (p.frameSequence() > 0) {
            // The new picture is here: promote it. The texture id the TV draws stays TEXTURE.
            dropSession();
            session = p;
            texture = (LiveFeedTexture) p.createTextureView(TEXTURE);
            texture.register();
            LiveFeedTexture pt = pendingTexture;
            if (pt != null) { pt.unregister(); pt.close(); }
            pending = null;
            pendingTexture = null;
            return session;
        }
        return s; // keep showing the old picture meanwhile
    }

    private static LiveHlsSession newSession(String url, boolean replay) {
        int w = LiveFeedClientConfig.viewerWidth();
        int h = LiveFeedClientConfig.heightFor(w);
        return new LiveHlsSession(url, w, h, replay);
    }

    private void dropPending() {
        LiveFeedTexture pt = pendingTexture;
        if (pt != null) { pt.unregister(); pt.close(); pendingTexture = null; }
        LiveHlsSession p = pending;
        if (p != null) { p.close(); pending = null; }
    }

    /** Render thread: forget the current session and its texture (world unload, takeover). */
    public void dropSession() {
        LiveFeedTexture t = texture;
        if (t != null) { t.unregister(); t.close(); texture = null; }
        LiveHlsSession s = session;
        if (s != null) { s.close(); session = null; }
    }

    /** Render thread: everything, including a pending switch (world unload). */
    public void dropAll() {
        dropPending();
        dropSession();
    }
}
