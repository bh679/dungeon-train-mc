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
import net.minecraft.client.Minecraft;
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
    /** The stopped stream's playlist this viewer already played to its ENDLIST; never rejoined. */
    @Nullable private String finishedUrl;
    private static final ResourceLocation PENDING_TEXTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live/main_next");

    private LiveFeedSource() {}

    /** What the feed has to show this frame, for a TV screen or the pinned head viewer. */
    public enum Kind { STATIC, DOWNLOADING, WAITING, PICTURE }

    /**
     * One frame's answer. {@code url} is the playlist being shown (null for static); {@code progress}
     * is the ffmpeg download percentage while {@link Kind#DOWNLOADING} (or -1 when unknown);
     * {@code buffering} is true while a picture is stalled on the next segment.
     */
    public record Frame(Kind kind, @Nullable String url, boolean replay, int progress, boolean buffering) {
        static final Frame STATIC = new Frame(Kind.STATIC, null, false, -1, false);
    }

    @Override
    public @NotNull VertexConsumer getVideoFrameBuilder(TVBlockEntity tv, float partialTick, MultiBufferSource buffer,
                                                        boolean shouldUpdate, Vec2i screenSize, Vec2i pixelEffectRes,
                                                        int videoAnimationTick, boolean paused,
                                                        IntAnimationState switchAnim, IntAnimationState staticAnim,
                                                        boolean showsTime) {
        return screenConsumer(frame(videoAnimationTick, partialTick, paused, true),
            buffer, pixelEffectRes, videoAnimationTick, switchAnim, staticAnim);
    }

    /**
     * What to draw a screen with this frame — a TV block's or a hand-held TV's
     * ({@code HeldTvScreen}): Vista's snow, download bars, waiting pattern or the picture itself.
     */
    public VertexConsumer screenConsumer(MultiBufferSource buffer, Vec2i pixelEffectRes, int videoAnimationTick,
                                         float partialTick, boolean paused,
                                         IntAnimationState switchAnim, IntAnimationState staticAnim) {
        return screenConsumer(frame(videoAnimationTick, partialTick, paused),
            buffer, pixelEffectRes, videoAnimationTick, switchAnim, staticAnim);
    }

    private static VertexConsumer screenConsumer(Frame f, MultiBufferSource buffer, Vec2i pixelEffectRes,
                                                 int videoAnimationTick,
                                                 IntAnimationState switchAnim, IntAnimationState staticAnim) {
        return switch (f.kind()) {
            case STATIC -> TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
            case DOWNLOADING -> f.progress() >= 0
                ? TvScreenVertexConsumers.getDownloadingVc(buffer, pixelEffectRes, f.progress(), switchAnim)
                : TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, videoAnimationTick, switchAnim);
            case WAITING -> TvScreenVertexConsumers.getWaitingVc(buffer, pixelEffectRes, videoAnimationTick, switchAnim);
            case PICTURE -> TvScreenVertexConsumers.getSingleTextureVC(buffer, TEXTURE,
                f.buffering() ? CrtOverlay.LOADING : CrtOverlay.NONE, pixelEffectRes, switchAnim, staticAnim);
        };
    }

    /** The texture a {@link Kind#PICTURE} frame is in — the same one every TV draws. */
    public static ResourceLocation texture() {
        return TEXTURE;
    }

    /**
     * Render thread: decide what the feed shows now, keeping the shared decode alive and copying in
     * its newest frame. TVs and the head viewer both call this; they share one session.
     */
    public Frame frame(int animationTick, float partialTick, boolean paused) {
        return frame(animationTick, partialTick, paused, false);
    }

    /** {@code tvBlock}: a TV block is asking, which keeps the decode at full width for a streamer too. */
    private Frame frame(int animationTick, float partialTick, boolean paused, boolean tvBlock) {
        if (!LiveFeedClientConfig.viewingEnabled()) return Frame.STATIC;
        Status status = tvBlock ? LiveStatusPoller.tvWanted() : LiveStatusPoller.wanted();
        boolean replay = false;
        String url;
        if (status.live() && status.slot() && status.playlistUrl() != null && !playedToEnd(status.playlistUrl())) {
            url = status.playlistUrl();
        } else if (status.ended() && playedToEnd(status.playlistUrl())) {
            // The streamer stopped and this viewer has seen everything they recorded: the dark channel.
            if (status.replayUrl() == null) return Frame.STATIC;
            url = status.replayUrl();
            replay = true;
        } else if (!status.live() && status.replayUrl() != null) {
            // Nobody is on: the last ten seconds of the previous broadcast, looped, in black and white.
            url = status.replayUrl();
            replay = true;
        } else {
            return Frame.STATIC;
        }
        switch (FfmpegSupport.state()) {
            case DOWNLOADING -> {
                return new Frame(Kind.DOWNLOADING, url, replay, VistaModClient.getFFmpegDownloadProgress(), false);
            }
            case OFF, FAILED -> {
                return Frame.STATIC;
            }
            default -> { /* ready */ }
        }
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (ffmpeg == null) return Frame.STATIC;
        LiveHlsSession s = sessionFor(url, replay, status, ffmpeg);
        s.touch(ffmpeg);
        LiveFeedTexture tex = texture;
        if (tex == null) return new Frame(Kind.WAITING, url, replay, -1, false);
        MediaStatus st = tex.uploadFrameAtTime(animationTick, partialTick, paused);
        if (!tex.hasPicture()) return new Frame(Kind.WAITING, url, replay, -1, false);
        // The texture may still hold the previous source while the new one decodes behind it.
        LiveHlsSession shown = session;
        String shownUrl = shown != null ? shown.playlistUrl() : url;
        boolean shownReplay = shown != null ? shown.isReplay() : replay;
        return new Frame(Kind.PICTURE, shownUrl, shownReplay, -1, st == MediaStatus.BUFFERING);
    }

    /** This viewer played {@code url} to its ENDLIST (the stopped stream's tail is done). */
    private boolean playedToEnd(@Nullable String url) {
        LiveHlsSession s = session;
        if (s != null && !s.isReplay() && s.finished()) finishedUrl = s.playlistUrl();
        return url != null && url.equals(finishedUrl);
    }

    /**
     * Render thread. A source change (takeover, replay ↔ live) does not cut straight over: the new
     * session decodes alongside the old one and takes the screen at its first frame, so a viewer
     * never sees "waiting" between two pictures. With no picture yet, the switch is immediate.
     * A change of decode width ({@link #decodeWidth()}) is a source change too, and so is the relay
     * starting or stopping to offer a rendition that fits it ({@link #mediaUrlFor}).
     */
    private LiveHlsSession sessionFor(String url, boolean replay, Status status, FFmpeg ffmpeg) {
        int width = decodeWidth();
        String media = mediaUrlFor(url, replay, status, width);
        LiveHlsSession s = session;
        if (s != null && matches(s, url, media, replay, width)) {
            dropPending();
            return s;
        }
        if (s == null || texture == null || !texture.hasPicture()) {
            dropSession();
            session = newSession(url, media, replay, width);
            texture = (LiveFeedTexture) session.createTextureView(TEXTURE);
            texture.register();
            return session;
        }
        LiveHlsSession p = pending;
        if (p == null || !matches(p, url, media, replay, width)) {
            dropPending();
            pending = newSession(url, media, replay, width);
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

    private static boolean matches(LiveHlsSession s, String url, String media, boolean replay, int width) {
        return s.playlistUrl().equals(url) && s.mediaUrl().equals(media) && s.isReplay() == replay && s.width() == width;
    }

    /**
     * What to feed ffmpeg for the stream {@code url} at {@code width}: the smallest rendition the
     * relay has ready that covers it, else the source. The replay clip and anything that is not
     * the status's live playlist (a stopped stream's tail included — the worker plays it out too,
     * but its variants are only listed while the status names that session) stay as they are.
     */
    static String mediaUrlFor(String url, boolean replay, Status status, int width) {
        if (replay || status.variants().isEmpty() || !url.equals(status.playlistUrl())) return url;
        String media = status.mediaUrlFor(width);
        return media != null ? media : url;
    }

    /**
     * The width to decode at now: the viewer width, except for a streamer whose own pinned box is
     * the only thing showing the feed — then the box's real pixel size ({@link LiveDecodeWidth}).
     */
    private static int decodeWidth() {
        return LiveDecodeWidth.forConsumer(LiveStreamController.get().streaming(),
            LiveStatusPoller.tvInterested(), LiveFeedClientConfig.headViewerWidth(true),
            Minecraft.getInstance().getWindow().getGuiScale(), LiveFeedClientConfig.viewerWidth());
    }

    private static LiveHlsSession newSession(String url, String media, boolean replay, int width) {
        return new LiveHlsSession(url, media, width, LiveFeedClientConfig.heightFor(width), replay);
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

    /** Render thread: the session on screen, or null — for the {@code [live]} perf line. */
    @Nullable
    LiveHlsSession currentSession() {
        return session;
    }

    /** Render thread: how many sessions (shown and pending) have a decoder running. */
    int decodingSessions() {
        LiveHlsSession s = session, p = pending;
        return (s != null && s.decoding() ? 1 : 0) + (p != null && p.decoding() ? 1 : 0);
    }

    /** Render thread: everything, including a pending switch (world unload). */
    public void dropAll() {
        dropPending();
        dropSession();
    }
}
