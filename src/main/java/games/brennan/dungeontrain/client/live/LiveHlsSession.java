package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.client.textures.web.IWebTexture;
import net.mehvahdjukaar.vista.client.web.IMediaSession;
import net.mehvahdjukaar.vista.client.web.MediaError;
import net.mehvahdjukaar.vista.client.web.MediaStatus;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Decodes one live playlist with Vista's ffmpeg into a back buffer the {@link LiveFeedTexture}
 * copies from. One session per playlist URL, shared by every TV showing the feed.
 *
 * <p>ffmpeg reads the HLS directly ({@code -live_start_index -1} joins at the newest segment,
 * {@code -re} plays it at real speed instead of bursting a whole segment), scales to the viewer
 * width, and pipes raw RGB frames. When the stream ends — the playlist 404s after the relay deletes
 * it, or carries ENDLIST — ffmpeg exits and the reader backs off (2 s → 30 s) before trying again,
 * keeping the last frame on screen meanwhile. {@link #release()} after 30 s with no viewer kills
 * the process; the texture survives.</p>
 */
public final class LiveHlsSession implements IMediaSession {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long IDLE_KILL_MS = 30_000;

    private final String playlistUrl;
    private final boolean replay;
    private final int width, height;
    private final NativeImage back;
    private final Object lock = new Object();
    private volatile long frameSeq;
    private volatile MediaStatus status = MediaStatus.LOADING;
    private volatile long lastWantedMs = System.currentTimeMillis();
    private volatile boolean closed;
    private volatile Process process;
    private Thread reader;

    public LiveHlsSession(String playlistUrl, int width, int height) {
        this(playlistUrl, width, height, false);
    }

    /**
     * {@code replay}: the URL is one finished segment of the last stream, looped forever in black
     * and white with a little noise — what a dark channel shows between broadcasts.
     */
    public LiveHlsSession(String playlistUrl, int width, int height, boolean replay) {
        this.playlistUrl = playlistUrl;
        this.replay = replay;
        this.width = width;
        this.height = height;
        this.back = new NativeImage(NativeImage.Format.RGBA, width, height, false);
    }

    public String playlistUrl() { return playlistUrl; }
    public long frameSequence() { return frameSeq; }
    public MediaStatus status() { return status; }

    /** A TV wants this feed right now; (re)start decoding if idle. */
    public synchronized void touch(FFmpeg ffmpeg) {
        lastWantedMs = System.currentTimeMillis();
        if (closed || (reader != null && reader.isAlive())) return;
        reader = new Thread(() -> readLoop(ffmpeg), "dt-live-decoder");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(FFmpeg ffmpeg) {
        long backoffMs = 2000;
        while (!closed && System.currentTimeMillis() - lastWantedMs < IDLE_KILL_MS) {
            Process p = null;
            try {
                status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
                p = ffmpeg.runFFmpeg(args());
                process = p;
                FfmpegSupport.drain(p.getErrorStream(), "decoder");
                int frameBytes = width * height * 3;
                byte[] buf = new byte[frameBytes];
                try (InputStream in = p.getInputStream()) {
                    while (!closed) {
                        if (System.currentTimeMillis() - lastWantedMs >= IDLE_KILL_MS) break;
                        if (!readFully(in, buf)) break;
                        synchronized (lock) {
                            int i = 0;
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    int r = buf[i++] & 0xFF, g = buf[i++] & 0xFF, b = buf[i++] & 0xFF;
                                    back.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                                }
                            }
                            frameSeq++;
                        }
                        status = MediaStatus.READY;
                        backoffMs = 2000;
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("[DungeonTrain] live decoder: {}", e.toString());
            } finally {
                if (p != null) p.destroyForcibly();
                process = null;
            }
            if (closed) break;
            status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
            try { Thread.sleep(backoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
        status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
    }

    private String[] args() {
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-re"));
        if (replay) {
            a.addAll(List.of("-stream_loop", "-1", "-i", playlistUrl, "-an",
                "-vf", "scale=" + width + ":" + height + ",format=gray,noise=alls=18:allf=t+u,format=rgb24"));
        } else {
            a.addAll(List.of("-live_start_index", "-1",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", playlistUrl, "-an", "-vf", "scale=" + width + ":" + height));
        }
        a.addAll(List.of("-f", "image2pipe", "-vcodec", "rawvideo", "-pix_fmt", "rgb24", "-"));
        return a.toArray(new String[0]);
    }

    public boolean isReplay() {
        return replay;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    /** Render thread: copy the newest frame into {@code dst}; false if nothing decoded yet. */
    public boolean copyLatestInto(NativeImage dst) {
        if (frameSeq == 0) return false;
        synchronized (lock) {
            dst.copyFrom(back);
        }
        return true;
    }

    // ---- IMediaSession ---------------------------------------------------------------------------

    @Override
    public IWebTexture createTextureView(ResourceLocation resourceLocation) {
        return new LiveFeedTexture(resourceLocation, this, width, height);
    }

    @Override
    public boolean shouldRefreshTexture(IWebTexture tt) {
        return true;
    }

    @Override
    public boolean isFailed() {
        return false;
    }

    @Override
    public MediaError getError() {
        return MediaError.NONE;
    }

    @Override
    public void close() {
        closed = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
        Thread r = reader;
        if (r != null) {
            try { r.join(TimeUnit.SECONDS.toMillis(2)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) {
            back.close();
        }
    }
}
