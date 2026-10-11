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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Decodes one live playlist with Vista's ffmpeg into a back buffer the {@link LiveFeedTexture}
 * copies from. One session per playlist URL, shared by every TV showing the feed.
 *
 * <p>ffmpeg reads the HLS directly ({@code -live_start_index -1} joins at the newest segment,
 * {@code -re} plays it at real speed instead of bursting a whole segment), scales to the viewer
 * width, and pipes raw RGBA frames. When the streamer stops, their last playlist carries ENDLIST:
 * ffmpeg plays the tail out and exits cleanly, and the session is {@link #finished()} — never
 * restarted, since rejoining a finished playlist would play it again from its first segment. Any
 * other exit (a 404, a network drop) backs off (2 s → 30 s) before trying again, keeping the last
 * frame on screen meanwhile. A playlist that vanishes without ENDLIST leaves ffmpeg waiting forever
 * instead of exiting, so a live run with no frame for {@link #STALL_MS} is killed from {@link #touch}
 * into that same backoff. {@link #release()} after 30 s with no viewer kills
 * the process; the texture survives.</p>
 *
 * <p>Frames arrive as RGBA, whose little-endian ints are already {@link NativeImage}'s pixel values.
 * The reader fills a staging image under {@code fillLock} and swaps it with {@code back} under
 * {@code lock}, so the render thread's {@link #copyLatestInto} only ever waits for a swap. {@link
 * #close()} never waits for the reader: it interrupts it (ending a backoff sleep) and kills ffmpeg
 * (ending a blocked read), then frees both images under both locks — the reader checks {@code closed}
 * under {@code fillLock} before it touches one.</p>
 */
public final class LiveHlsSession implements IMediaSession {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long IDLE_KILL_MS = 30_000;
    /**
     * A live run with no new frame for this long is stuck — its playlist vanished without an ENDLIST
     * and ffmpeg stopped refetching but never exits. About two segments: a viewer legitimately waits
     * up to one for the next.
     */
    static final long STALL_MS = 20_000;

    private final String playlistUrl;
    private final boolean replay;
    private final int width, height;
    /** Guarded by {@code lock}: the newest whole frame. */
    private NativeImage back;
    /** Guarded by {@code fillLock}: the frame being decoded. */
    private NativeImage staging;
    private final Object lock = new Object();
    private final Object fillLock = new Object();
    private volatile long frameSeq;
    private volatile MediaStatus status = MediaStatus.LOADING;
    private volatile long lastWantedMs = System.currentTimeMillis();
    private volatile boolean closed;
    private volatile boolean finished;
    private volatile Process process;
    private volatile Thread reader;
    /** The newest frame, or the start of the current ffmpeg run if later: what {@link #stalled} measures from. */
    private volatile long lastProgressMs = System.currentTimeMillis();
    /** The run the stall watchdog already killed, so it is killed (and logged) once. */
    private volatile Process stallKilled;

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
        this.staging = new NativeImage(NativeImage.Format.RGBA, width, height, false);
    }

    public String playlistUrl() { return playlistUrl; }
    public long frameSequence() { return frameSeq; }
    public MediaStatus status() { return status; }

    /** True once a live playlist was played to its ENDLIST: the stream is over and this session is done. */
    public boolean finished() { return finished; }

    /**
     * Whether an ffmpeg run that just ended reached the end of the stream: a clean exit (0) after at
     * least one frame on a live playlist. Errors (404, network) exit non-zero; a replay loops forever.
     */
    static boolean reachedEnd(int exitCode, long framesThisRun, boolean replay) {
        return !replay && exitCode == 0 && framesThisRun > 0;
    }

    /**
     * Whether a live run has gone quiet for good: frames were decoded, then none for {@link #STALL_MS}.
     * A replay loops a local-length segment and never stalls this way; before the first frame the
     * waiting pattern is already showing.
     */
    static boolean stalled(long lastProgressMs, long nowMs, boolean replay, long framesSeen) {
        return !replay && framesSeen > 0 && nowMs - lastProgressMs >= STALL_MS;
    }

    /** Whether the decoder thread is still running. */
    boolean decoding() {
        Thread r = reader;
        return r != null && r.isAlive();
    }

    /** A TV wants this feed right now; (re)start decoding if idle. */
    public synchronized void touch(FFmpeg ffmpeg) {
        lastWantedMs = System.currentTimeMillis();
        if (closed || finished) return;
        if (reader != null && reader.isAlive()) {
            killIfStalled(lastWantedMs);
            return;
        }
        reader = new Thread(() -> readLoop(ffmpeg), "dt-live-decoder");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Render thread: a stalled run shows buffering and its ffmpeg is killed — a signal, never a wait —
     * so the reader's blocked read ends and {@link #readLoop} falls into its backoff and retries.
     */
    private void killIfStalled(long nowMs) {
        Process p = process;
        if (p == null || p == stallKilled || !stalled(lastProgressMs, nowMs, replay, frameSeq)) return;
        stallKilled = p;
        status = MediaStatus.BUFFERING;
        LOGGER.info("[DungeonTrain] live decoder: no frame for {} s, restarting {}",
            (nowMs - lastProgressMs) / 1000, playlistUrl);
        p.destroyForcibly();
    }

    private void readLoop(FFmpeg ffmpeg) {
        long backoffMs = 2000;
        while (!closed && System.currentTimeMillis() - lastWantedMs < IDLE_KILL_MS) {
            Process p = null;
            try {
                status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
                p = ffmpeg.runFFmpeg(args(playlistUrl, width, height, replay));
                lastProgressMs = System.currentTimeMillis(); // a fresh run gets a whole stall window
                process = p;
                if (closed) break;
                FfmpegSupport.drain(p.getErrorStream(), "decoder");
                byte[] buf = new byte[width * height * 4];
                IntBuffer pixels = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                long framesThisRun = 0;
                try (InputStream in = p.getInputStream()) {
                    while (!closed) {
                        if (System.currentTimeMillis() - lastWantedMs >= IDLE_KILL_MS) break;
                        if (!readFully(in, buf)) break;
                        if (!publish(pixels)) break;
                        framesThisRun++;
                        status = MediaStatus.READY;
                        backoffMs = 2000;
                    }
                }
                if (!closed && p.waitFor(2, TimeUnit.SECONDS) && reachedEnd(p.exitValue(), framesThisRun, replay)) {
                    finished = true;
                    LOGGER.debug("[DungeonTrain] live decoder: {} played to its end", playlistUrl);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                LOGGER.debug("[DungeonTrain] live decoder: {}", e.toString());
            } finally {
                if (p != null) p.destroyForcibly();
                process = null;
            }
            if (closed || finished) break;
            status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
            try { Thread.sleep(backoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
        status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
    }

    /** Fills the staging image from one RGBA frame and swaps it in; false once the session is closed. */
    private boolean publish(IntBuffer pixels) {
        synchronized (fillLock) {
            if (closed) return false;
            NativeImage fill = staging;
            int i = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) fill.setPixelRGBA(x, y, pixels.get(i++));
            }
            synchronized (lock) {
                staging = back;
                back = fill;
                frameSeq++;
            }
            lastProgressMs = System.currentTimeMillis();
        }
        return true;
    }

    static String[] args(String playlistUrl, int width, int height, boolean replay) {
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-re"));
        if (replay) {
            a.addAll(List.of("-stream_loop", "-1", "-i", playlistUrl, "-an",
                "-vf", "scale=" + width + ":" + height + ",format=gray,noise=alls=18:allf=t+u,format=rgba"));
        } else {
            a.addAll(List.of("-live_start_index", "-1",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", playlistUrl, "-an", "-vf", "scale=" + width + ":" + height));
        }
        a.addAll(List.of("-f", "image2pipe", "-vcodec", "rawvideo", "-pix_fmt", "rgba", "-"));
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
        if (closed) return;
        closed = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
        Thread r = reader;
        if (r != null) r.interrupt();
        synchronized (fillLock) {
            synchronized (lock) {
                back.close();
                staging.close();
            }
        }
    }
}
