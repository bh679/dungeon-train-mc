package games.brennan.dungeontrain.client.logo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import games.brennan.dungeontrain.client.live.LiveFeedTexture;
import games.brennan.dungeontrain.client.live.LiveFrameShape;
import games.brennan.dungeontrain.client.live.LiveHlsSession;
import games.brennan.dungeontrain.client.snapshot.DeathBackgroundPainter;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The Live Feed behind the main menu: whoever is streaming, decoded silently through the same
 * ffmpeg → texture path the TVs and the death clip use, drawn cover-fit. Its own session and an
 * anonymous status poll (as the website's background makes), so it never counts this player as a
 * viewer and never touches the in-world TVs' shared feed. Nothing to draw (the photos show instead)
 * while nobody is live, ffmpeg isn't ready, viewing is switched off, or the first frame isn't in.
 * Render thread, except the poll's reply.
 */
final class MenuLiveFeed {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "menu_backdrop/live");
    private static final long POLL_NANOS = 15_000_000_000L;
    /** Decode width: a full-screen backdrop, so the larger rendition when there is one. */
    private static final int DECODE_WIDTH = 1280;

    private static volatile LiveFeedClient.Status status = LiveFeedClient.Status.OFFLINE;
    private static long lastPoll;
    private static boolean polling;
    @Nullable private static LiveHlsSession session;
    @Nullable private static LiveFeedTexture texture;
    @Nullable private static String playing;

    private MenuLiveFeed() {}

    /** Whoever is live now, or null: for the backdrop's caption. */
    @Nullable static String streamer() {
        LiveFeedClient.Status s = status;
        return s.live() ? s.streamer() : null;
    }

    /** Draws the live stream cover-fit over the screen; false (nothing drawn) while there is none to show. */
    static boolean draw(GuiGraphics g, int screenW, int screenH, int tick, float partialTick) {
        poll();
        LiveFeedClient.Status s = status;
        FFmpeg ffmpeg = FfmpegSupport.get();
        String url = s.live() ? s.playlistUrl() : null;
        if (url == null || ffmpeg == null || !LiveFeedClientConfig.viewingEnabled()) {
            close();
            return false;
        }
        if (session == null || !url.equals(playing) || session.refused()) {
            close();
            String media = s.mediaUrlFor(DECODE_WIDTH);
            session = new LiveHlsSession(url, media != null ? media : url, DECODE_WIDTH, LiveFeedClientConfig.heightFor(DECODE_WIDTH), false);
            playing = url;
        }
        session.touch(ffmpeg);
        session.watchdog(System.currentTimeMillis());
        if (session.frameSequence() == 0) return false;
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

    /** Anonymous, uncounted poll of who is live, at most every 15 s while the menu shows. */
    private static void poll() {
        long now = System.nanoTime();
        if (polling || (lastPoll != 0 && now - lastPoll < POLL_NANOS)) return;
        polling = true;
        lastPoll = now;
        LiveFeedClient.status(null, LiveFeedClient.Channel.LIVE).whenComplete((result, error) -> {
            try {
                status = error != null || result == null ? LiveFeedClient.Status.OFFLINE : LiveFeedClient.parseStatus(result);
            } catch (RuntimeException e) {
                LOGGER.debug("[DungeonTrain] menu live status unreadable", e);
                status = LiveFeedClient.Status.OFFLINE;
            } finally {
                polling = false;
            }
        });
    }

    /** Stops the decode and frees the texture (the menu closed, or nobody is live). */
    static void close() {
        LiveFeedTexture t = texture;
        texture = null;
        if (t != null) {
            t.unregister();
            t.close();
        }
        LiveHlsSession old = session;
        session = null;
        playing = null;
        if (old != null) old.close();
    }

    /** Lets the next menu visit poll straight away. */
    static void forgetPoll() { lastPoll = 0; }

    private static void dropTexture() {
        LiveFeedTexture t = texture;
        texture = null;
        if (t != null) {
            t.unregister();
            t.close();
        }
    }
}
