package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.client.textures.web.IWebTexture;
import net.mehvahdjukaar.vista.client.web.IMediaSession;
import net.mehvahdjukaar.vista.client.web.MediaError;
import net.mehvahdjukaar.vista.client.web.MediaStatus;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import games.brennan.dungeontrain.mixin.client.NativeImageAccessor;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Decodes one live playlist with Vista's ffmpeg into a back buffer the {@link LiveFeedTexture}
 * copies from. One session per playlist URL, shared by every TV showing the feed.
 *
 * <p>ffmpeg reads the HLS directly ({@code -live_start_index -1} joins at the newest segment,
 * {@code -readrate 1} plays it at real speed instead of bursting a whole segment, and {@code
 * -readrate_catchup} lets it catch back up after a stall — {@code -re} on an ffmpeg too old for that),
 * scales to the viewer width, and pipes raw RGBA frames. The playlist is streamer-authored: ffmpeg may
 * only speak https (plain http just for a loopback test relay), every read times out, and before each
 * run the reader fetches the playlist itself and refuses one listing anything but our own relative
 * {@code segNNNNN.ts} names ({@link #safePlaylist}) — a refused session shows static for good. When the streamer stops, their last playlist carries ENDLIST:
 * ffmpeg plays the tail out and exits cleanly, and once a fetch of the playlist confirms the ENDLIST
 * (ffmpeg exits cleanly on a vanished one too) the session is {@link #finished()} — never
 * restarted, since rejoining a finished playlist would play it again from its first segment. Any
 * other exit (a 404, a network drop) backs off (2 s → 30 s) before trying again, keeping the last
 * frame on screen meanwhile. A playlist that vanishes without ENDLIST leaves ffmpeg waiting forever
 * instead of exiting, so a live run with no frame for {@link #STALL_MS} is killed into that same
 * backoff by {@link #watchdog}, which a client tick drives whether or not a TV is in view. {@link #release()} after 30 s with no viewer kills
 * the process; the texture survives.</p>
 *
 * <p>Frames arrive as RGBA, whose bytes are already {@link NativeImage}'s pixel layout, so the
 * reader reads each into a direct buffer and {@code memCopy}s it whole into a staging image under {@code fillLock} and swaps it with {@code back} under
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
    /** ffmpeg's read/write timeout, µs: a dead socket ends the run instead of blocking forever. */
    static final long RW_TIMEOUT_US = 15_000_000L;
    /** A playlist larger than this is not one of ours. */
    private static final int MAX_PLAYLIST_BYTES = 256 * 1024;
    /** The only entries a Live Feed playlist may list: the encoder's and the renditions' segment names. */
    private static final Pattern SEGMENT = Pattern.compile("seg\\d{5}\\.ts");
    private static final HttpClient ENDLIST_CHECK = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String playlistUrl;
    private final String mediaUrl;
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
    /** The playlist listed something other than our own segments: never decoded, shown as static. */
    private volatile boolean refused;
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
        this(playlistUrl, playlistUrl, width, height, replay);
    }

    /**
     * {@code playlistUrl} is the stream's identity — the source playlist the relay's status names,
     * which {@code playedToEnd} and the streamer's own-broadcast check compare against; {@code
     * mediaUrl} is what ffmpeg actually reads: the same URL, or one of the relay's smaller
     * renditions of it.
     */
    public LiveHlsSession(String playlistUrl, String mediaUrl, int width, int height, boolean replay) {
        this.playlistUrl = playlistUrl;
        this.mediaUrl = mediaUrl;
        this.replay = replay;
        this.width = width;
        this.height = height;
        this.back = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        this.staging = new NativeImage(NativeImage.Format.RGBA, width, height, false);
    }

    /** The source playlist this session stands for (not necessarily what it decodes). */
    public String playlistUrl() { return playlistUrl; }

    /** The playlist ffmpeg reads: the source, or a rendition of it. */
    public String mediaUrl() { return mediaUrl; }

    /** The decode width, in pixels. */
    public int width() { return width; }
    public long frameSequence() { return frameSeq; }
    public MediaStatus status() { return status; }

    /** True once a live playlist was played to its ENDLIST: the stream is over and this session is done. */
    public boolean finished() { return finished; }

    /**
     * Whether an ffmpeg run that just ended reached the end of the stream: a clean exit (0) after at
     * least one frame on a live playlist that really carries ENDLIST. ffmpeg also exits 0 when a live
     * playlist starts answering 404 — a vanished stream, not a finished one — so the exit code alone
     * is not enough. A replay loops forever.
     */
    static boolean reachedEnd(int exitCode, long framesThisRun, boolean replay, boolean playlistEnded) {
        return !replay && exitCode == 0 && framesThisRun > 0 && playlistEnded;
    }

    /** A playlist fetch shows the stream is over: it is there and closed with ENDLIST. */
    static boolean isEndlist(int httpStatus, @Nullable String body) {
        return httpStatus == 200 && body != null && body.contains("#EXT-X-ENDLIST");
    }

    /** True once the playlist was refused ({@link #safePlaylist}): the feed shows static, never this stream. */
    public boolean refused() { return refused; }

    /**
     * Whether a playlist body lists only what a Live Feed playlist may: relative {@code segNNNNN.ts}
     * entries, and no tag carrying a {@code URI=} (a key, map or alternate rendition ffmpeg would
     * fetch from wherever it says). Anything else — an absolute URL, a path, another name — would make
     * every viewer's ffmpeg fetch from a host the streamer picked.
     */
    static boolean safePlaylist(@Nullable String body) {
        if (body == null || !body.startsWith("#EXTM3U")) return false;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.toUpperCase(java.util.Locale.ROOT).contains("URI=")) return false;
            } else if (!SEGMENT.matcher(line).matches()) {
                return false;
            }
        }
        return true;
    }

    /** One playlist fetch: status plus body (null past {@link #MAX_PLAYLIST_BYTES}). */
    private record Fetched(int status, @Nullable String body) {}

    /** Reader thread: fetch the playlist once. */
    private Fetched fetchPlaylist() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(mediaUrl))
            .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<InputStream> r = ENDLIST_CHECK.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            byte[] bytes = in.readNBytes(MAX_PLAYLIST_BYTES + 1);
            String body = bytes.length > MAX_PLAYLIST_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
            return new Fetched(r.statusCode(), body);
        }
    }

    /** Reader thread: fetch the playlist once and check it for ENDLIST; any failure reads as "not ended". */
    private boolean playlistEnded() throws InterruptedException {
        try {
            Fetched f = fetchPlaylist();
            return isEndlist(f.status(), f.body());
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.debug("[DungeonTrain] live decoder: ENDLIST check for {} failed: {}", playlistUrl, e.toString());
            return false;
        }
    }

    /**
     * Reader thread, before each live run: whether the playlist is safe to hand ffmpeg. False on a
     * fetch failure too (the run is skipped into the backoff); a playlist that is there but lists
     * something else marks the session {@link #refused}.
     */
    private boolean playlistSafe() throws InterruptedException {
        Fetched f;
        try {
            f = fetchPlaylist();
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.debug("[DungeonTrain] live decoder: playlist check for {} failed: {}", mediaUrl, e.toString());
            return false;
        }
        if (f.status() != 200) {
            LOGGER.debug("[DungeonTrain] live decoder: playlist check for {} answered {}", mediaUrl, f.status());
            return false;
        }
        if (safePlaylist(f.body())) return true;
        refused = true;
        LOGGER.warn("[DungeonTrain] live decoder: refusing {}: it lists entries other than its own segments", mediaUrl);
        return false;
    }

    /**
     * Whether a live run has gone quiet for good: frames were decoded, then none for {@link #STALL_MS}.
     * A replay loops a local-length segment and never stalls this way; before the first frame the
     * waiting pattern is already showing.
     */
    static boolean stalled(long lastProgressMs, long nowMs, boolean replay, long framesSeen) {
        return !replay && framesSeen > 0 && nowMs - lastProgressMs >= STALL_MS;
    }

    /** The current ffmpeg run, or null between runs — for the {@code [live]} perf line's CPU sample. */
    @Nullable
    Process currentProcess() {
        return process;
    }

    /** Whether the decoder thread is still running. */
    boolean decoding() {
        Thread r = reader;
        return r != null && r.isAlive();
    }

    /** A TV wants this feed right now; (re)start decoding if idle. */
    public synchronized void touch(FFmpeg ffmpeg) {
        lastWantedMs = System.currentTimeMillis();
        if (closed || finished || refused) return;
        if (reader != null && reader.isAlive()) {
            killIfStalled(lastWantedMs);
            return;
        }
        reader = new Thread(() -> readLoop(ffmpeg), "dt-live-decoder");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Client tick: end a run nobody is watching ({@link #IDLE_KILL_MS} without a {@link #touch}) or one
     * that stalled — whether or not anything renders the feed. A signal, never a wait: the reader's
     * blocked read ends and its loop takes it from there.
     */
    public synchronized void watchdog(long nowMs) {
        if (closed) return;
        Process p = process;
        if (p == null) return;
        if (nowMs - lastWantedMs >= IDLE_KILL_MS) {
            p.destroyForcibly();
            return;
        }
        killIfStalled(nowMs);
    }

    /**
     * A stalled run shows buffering and its ffmpeg is killed — a signal, never a wait —
     * so the reader's blocked read ends and {@link #readLoop} falls into its backoff and retries.
     */
    private void killIfStalled(long nowMs) {
        Process p = process;
        if (p == null || p == stallKilled || !stalled(lastProgressMs, nowMs, replay, frameSeq)) return;
        stallKilled = p;
        status = MediaStatus.BUFFERING;
        LivePerfStats.stall();
        LOGGER.info("[DungeonTrain] live decoder: no frame for {} s, restarting {}",
            (nowMs - lastProgressMs) / 1000, playlistUrl);
        p.destroyForcibly();
    }

    private boolean idle() {
        return System.currentTimeMillis() - lastWantedMs >= IDLE_KILL_MS;
    }

    private void readLoop(FFmpeg ffmpeg) {
        ByteBuffer frame = MemoryUtil.memAlloc(width * height * 4);
        try {
            readLoop(ffmpeg, frame);
        } finally {
            MemoryUtil.memFree(frame);
        }
    }

    private void readLoop(FFmpeg ffmpeg, ByteBuffer frame) {
        boolean catchup = !replay && FfmpegSupport.hasReadrateCatchup(ffmpeg);
        long backoffMs = 2000;
        while (!closed && !idle()) {
            Process p = null;
            try {
                status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
                if (!replay && !playlistSafe()) {
                    if (refused) break;
                    throw new IOException("playlist not reachable");
                }
                LOGGER.info("[DungeonTrain] live decoder: {} at {}x{}{}", mediaUrl, width, height,
                    mediaUrl.equals(playlistUrl) ? "" : " (a rendition of " + playlistUrl + ")");
                p = ffmpeg.runFFmpeg(args(mediaUrl, width, height, replay, catchup));
                LivePerfStats.decoderRun();
                lastProgressMs = System.currentTimeMillis(); // a fresh run gets a whole stall window
                process = p;
                if (closed) break;
                FfmpegSupport.drain(p.getErrorStream(), "decoder");
                long framesThisRun = 0;
                try (InputStream in = p.getInputStream(); ReadableByteChannel ch = Channels.newChannel(in)) {
                    while (!closed) {
                        if (idle()) break;
                        if (!readFully(ch, frame)) break;
                        if (!publish(frame)) break;
                        framesThisRun++;
                        status = MediaStatus.READY;
                        backoffMs = 2000;
                    }
                }
                if (!closed && p.waitFor(2, TimeUnit.SECONDS) && reachedEnd(p.exitValue(), framesThisRun, replay, true)) {
                    // A clean exit: the end of the stream, or its playlist vanished. Only ENDLIST is the end.
                    if (playlistEnded()) {
                        finished = true;
                        LOGGER.debug("[DungeonTrain] live decoder: {} played to its end", playlistUrl);
                    } else {
                        LOGGER.info("[DungeonTrain] live decoder: {} stopped without ENDLIST, retrying", playlistUrl);
                    }
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
            if (closed || finished || refused || idle()) break;
            status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
            try { Thread.sleep(backoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            backoffMs = Math.min(backoffMs * 2, 30_000);
        }
        status = frameSeq == 0 ? MediaStatus.LOADING : MediaStatus.BUFFERING;
    }

    /** Copies one whole RGBA frame into the staging image and swaps it in; false once the session is closed. */
    private boolean publish(ByteBuffer frame) {
        long startNs = System.nanoTime();
        synchronized (fillLock) {
            if (closed) return false;
            NativeImage fill = staging;
            MemoryUtil.memCopy(MemoryUtil.memAddress(frame), ((NativeImageAccessor) (Object) fill).dungeontrain$pixels(),
                (long) width * height * 4);
            synchronized (lock) {
                staging = back;
                back = fill;
                frameSeq++;
            }
            lastProgressMs = System.currentTimeMillis();
            LivePerfStats.decoded(System.nanoTime() - startNs);
        }
        return true;
    }

    /**
     * {@code catchup}: this ffmpeg has {@code -readrate_catchup} (7.1+), so a live run paces with
     * {@code -readrate 1} and may run ahead after a stall; without it, {@code -re}. The replay loop
     * always uses {@code -re} — {@code -stream_loop} would otherwise spin through it.
     */
    static String[] args(String playlistUrl, int width, int height, boolean replay, boolean catchup) {
        String protocols = playlistUrl.startsWith("http://") ? "http,https,tls,tcp" : "https,tls,tcp";
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
            "-protocol_whitelist", protocols, "-rw_timeout", Long.toString(RW_TIMEOUT_US)));
        if (replay || !catchup) a.add("-re");
        else a.addAll(List.of("-readrate", "1", "-readrate_catchup", "2"));
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

    /** Fills {@code frame} from position 0; false at end of stream. */
    private static boolean readFully(ReadableByteChannel ch, ByteBuffer frame) throws IOException {
        frame.clear();
        while (frame.hasRemaining()) {
            if (ch.read(frame) < 0) return false;
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
