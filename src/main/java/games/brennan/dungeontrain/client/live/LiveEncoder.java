package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds grabbed frames to an ffmpeg process that writes live HLS into a session directory.
 *
 * <p>A writer thread paces at exactly {@code fps}: every tick it sends the newest frame the
 * {@link LiveFrameGrabber} produced, or the previous one again if the game rendered nothing new,
 * so ffmpeg's raw-video input is genuinely constant-rate however the frame rate wobbles. A keyframe
 * every {@value #KEYFRAME_SECONDS} s ({@code -g fps*2}, five per 10 s segment) is what Twitch's RTMP
 * ingest requires of a stream the relay forwards with {@code -c:v copy}; because the segment length is
 * a multiple of it, every segment still opens on a keyframe and a viewer can join at any boundary.</p>
 *
 * <p>Output: {@code seg00000.ts, seg00001.ts, …} and {@code live.m3u8} in {@code dir}; ffmpeg
 * deletes segments that fall out of the 6-entry window itself, writes each segment to a temp name
 * until complete, and rewrites the playlist only when a segment is whole — so the uploader can
 * trust the playlist as its manifest of finished files.</p>
 *
 * <p>With stream audio on, ffmpeg also reads the game's sound as a second input from a loopback port
 * ({@code LiveAudioPump}), paced against {@link #framesWritten()}, and muxes it as AAC into the same
 * segments. The picture's encoding and segmenting are the same either way.</p>
 */
public final class LiveEncoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LIST_SIZE = 6;
    /** Keyframe cadence in seconds. Twitch accepts at most 4 s; 2 s is its recommendation. */
    static final int KEYFRAME_SECONDS = 2;
    /** libx264 defaults to ~1.5 threads per core (sliced under zerolatency) and competes with the game; 720p20 veryfast needs under one core. */
    static final int ENCODER_THREADS = 2;
    /** Save bandwidth: a slower preset finds the same picture in fewer bits, at several times veryfast's CPU. */
    static final String SAVE_BANDWIDTH_PRESET = "medium";
    /** medium needs more than veryfast's two threads to keep up at 720p20; still short of every core. */
    static final int SAVE_BANDWIDTH_THREADS = 4;
    /** Save bandwidth sends this share of {@code bitrateKbps}: medium without zerolatency looks about the same at it. */
    static final double SAVE_BANDWIDTH_BITRATE_FACTOR = 0.55;
    /** The lowest bitrate save-bandwidth mode goes to, matching the config's own floor. */
    static final int MIN_BITRATE_KBPS = 500;
    /** Stream audio bitrate: AAC stereo, plenty for game sound. */
    static final int AUDIO_KBPS = 96;
    /** Input queue for each input when there are two, so neither blocks ffmpeg's reader on the other. */
    static final int INPUT_QUEUE = 512;
    /** ffmpeg's time to finish after its input ends, on a teardown off the render thread. */
    public static final long ASYNC_GRACE_MS = 15_000;
    /** The same on game shutdown or the render thread, where nothing may wait long. */
    public static final long SHUTDOWN_GRACE_MS = 3_000;

    private final LiveFrameGrabber grabber;
    private final Process process;
    private final Thread writer;
    private final int fps;
    private final boolean saveBandwidth;
    private volatile boolean running = true;
    private volatile boolean died;
    private final AtomicLong framesWritten = new AtomicLong();

    /**
     * {@code audioPort} 0 = no sound (as before); otherwise the loopback port ffmpeg reads the sound from.
     * {@code saveBandwidth} = compress harder (see {@link #args}).
     */
    public LiveEncoder(FFmpeg ffmpeg, LiveFrameGrabber grabber, Path dir, int fps, int bitrateKbps, int segmentSeconds,
                       int audioPort, boolean saveBandwidth) throws IOException {
        this.grabber = grabber;
        this.fps = fps;
        this.saveBandwidth = saveBandwidth;
        this.process = ffmpeg.runFFmpeg(args(grabber.width(), grabber.height(), fps, bitrateKbps, segmentSeconds, dir,
            audioPort, saveBandwidth));
        FfmpegSupport.drain(process.getErrorStream(), "encoder");
        FfmpegSupport.drain(process.getInputStream(), "encoder-out");
        this.writer = new Thread(this::writeLoop, "dt-live-encoder");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    static String[] args(int w, int h, int fps, int bitrateKbps, int segmentSeconds, Path dir) {
        return args(w, h, fps, bitrateKbps, segmentSeconds, dir, 0);
    }

    static String[] args(int w, int h, int fps, int bitrateKbps, int segmentSeconds, Path dir, int audioPort) {
        return args(w, h, fps, bitrateKbps, segmentSeconds, dir, audioPort, false);
    }

    /**
     * With {@code saveBandwidth} the picture costs less upload for more CPU: {@value #SAVE_BANDWIDTH_PRESET}
     * instead of veryfast, no {@code zerolatency} (B-frames and lookahead are most of the saving; a 10 s
     * segment stream doesn't miss the latency), {@value #SAVE_BANDWIDTH_THREADS} threads and
     * {@link #effectiveBitrateKbps} of the bitrate. Keyframes, segments and the codec are unchanged, so
     * Twitch's {@code -c:v copy} and the TVs' decoder take it as before.
     */
    static String[] args(int w, int h, int fps, int bitrateKbps, int segmentSeconds, Path dir, int audioPort,
                         boolean saveBandwidth) {
        int kbps = effectiveBitrateKbps(bitrateKbps, saveBandwidth);
        int gop = fps * KEYFRAME_SECONDS;
        boolean audio = audioPort > 0;
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
        if (audio) a.addAll(List.of("-thread_queue_size", String.valueOf(INPUT_QUEUE)));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-"));
        if (audio) {
            a.addAll(List.of("-thread_queue_size", String.valueOf(INPUT_QUEUE)));
            a.addAll(List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "tcp://127.0.0.1:" + audioPort,
                "-map", "0:v:0", "-map", "1:a:0"));
        }
        a.addAll(List.of(
            "-vf", "vflip",
            "-c:v", "libx264"));
        if (saveBandwidth) a.addAll(List.of("-preset", SAVE_BANDWIDTH_PRESET));
        else a.addAll(List.of("-preset", "veryfast", "-tune", "zerolatency"));
        a.addAll(List.of(
            "-pix_fmt", "yuv420p",
            "-threads", String.valueOf(saveBandwidth ? SAVE_BANDWIDTH_THREADS : ENCODER_THREADS),
            "-b:v", kbps + "k", "-maxrate", kbps + "k", "-bufsize", (kbps * 2) + "k",
            "-g", String.valueOf(gop), "-keyint_min", String.valueOf(gop), "-sc_threshold", "0",
            "-force_key_frames", "expr:gte(t,n_forced*" + KEYFRAME_SECONDS + ")"));
        if (audio) a.addAll(List.of("-c:a", "aac", "-b:a", AUDIO_KBPS + "k", "-ar", "48000", "-ac", "2"));
        a.addAll(List.of(
            "-f", "hls", "-hls_time", String.valueOf(segmentSeconds), "-hls_list_size", String.valueOf(LIST_SIZE),
            "-hls_flags", "delete_segments+independent_segments+temp_file",
            "-hls_segment_filename", dir.resolve("seg%05d.ts").toString(),
            dir.resolve(LivePlaylist.FILE_NAME).toString()));
        return a.toArray(new String[0]);
    }

    /** The video bitrate actually sent: {@code bitrateKbps}, or its save-bandwidth share (never under {@value #MIN_BITRATE_KBPS}). */
    static int effectiveBitrateKbps(int bitrateKbps, boolean saveBandwidth) {
        if (!saveBandwidth) return bitrateKbps;
        return Math.max(MIN_BITRATE_KBPS, (int) Math.round(bitrateKbps * SAVE_BANDWIDTH_BITRATE_FACTOR));
    }

    /** Whether this encoder runs in save-bandwidth mode; a restart keeps the mode it started in. */
    public boolean saveBandwidth() {
        return saveBandwidth;
    }

    /** The ffmpeg process, for the {@code [live]} perf line's CPU sample. */
    Process process() {
        return process;
    }

    /** Video frames written to ffmpeg so far: the clock the stream's sound is paced against. */
    public long framesWritten() {
        return framesWritten.get();
    }

    /** True once ffmpeg exited on its own (bad binary, disk full…). The controller stops the stream. */
    public boolean died() {
        return died;
    }

    private void writeLoop() {
        long periodNs = 1_000_000_000L / fps;
        long next = System.nanoTime();
        ByteBuffer last = null;
        try (OutputStream raw = process.getOutputStream(); WritableByteChannel out = Channels.newChannel(raw)) {
            while (running) {
                ByteBuffer fresh = grabber.latest();
                if (fresh != null) {
                    if (last != null) grabber.recycle(last);
                    last = fresh;
                }
                if (last != null) {
                    last.position(0);
                    while (last.hasRemaining()) out.write(last);
                    framesWritten.incrementAndGet();
                    LivePerfStats.encoderWrote(fresh != null);
                }
                next += periodNs;
                long sleep = next - System.nanoTime();
                if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep); else next = System.nanoTime();
                if (!process.isAlive()) { died = true; break; }
            }
        } catch (IOException e) {
            if (running) { LOGGER.warn("[DungeonTrain] live encoder pipe closed: {}", e.toString()); died = true; }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            if (last != null) grabber.recycle(last);
        }
    }

    /** How long close() waits for the writer thread to hand its last frame back. */
    private static final long WRITER_JOIN_MS = 1_000;

    /**
     * Stop feeding, let ffmpeg finish the last segment (it writes ENDLIST), then kill it — with the
     * {@link #ASYNC_GRACE_MS} grace, for a teardown off the render thread. Blocks for seconds — never
     * call it on the render or tick thread. Joins the writer last, so the frame it held is back with
     * the grabber before the grabber is closed.
     */
    @Override
    public void close() {
        close(ASYNC_GRACE_MS);
    }

    /**
     * {@link #close()} with {@code graceMs} for ffmpeg to drain its input queue and write the last
     * segment and ENDLIST: a CPU-starved streamer can have many seconds of frames still queued
     * ({@link #INPUT_QUEUE}), so only game shutdown, with its own deadline, uses the short grace.
     */
    public void close(long graceMs) {
        running = false;
        writer.interrupt();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!process.waitFor(Math.max(0, graceMs), TimeUnit.MILLISECONDS)) process.destroyForcibly();
            writer.join(WRITER_JOIN_MS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
