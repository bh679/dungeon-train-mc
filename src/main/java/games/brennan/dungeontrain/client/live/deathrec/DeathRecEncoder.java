package games.brennan.dungeontrain.client.live.deathrec;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import games.brennan.dungeontrain.client.live.LiveFrameGrabber;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The death recorder's ffmpeg: grabbed frames (and, with sound, the mirror's mix from a loopback
 * port, as the Live Feed's {@code LiveEncoder} reads it) in, MPEG-TS out on stdout, which a reader
 * thread hands to the {@link DeathRecRing}. Nothing is written to disk.
 *
 * <p>The writer paces at exactly {@code fps}, sending the newest grabbed frame or the previous one
 * again, so the raw-video input is constant-rate and the sound, paced against
 * {@link #framesWritten()}, stays in step. It is the Live Feed encoder's loop without the stream's
 * perf counters. A keyframe every second keeps what a trimmed ring starts with short.</p>
 */
final class DeathRecEncoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int VIDEO_KBPS = 6_000;
    static final int AUDIO_KBPS = 128;
    static final int THREADS = 2;
    static final int INPUT_QUEUE = 512;
    private static final int READ_BUFFER = 64 * 1024;
    private static final long JOIN_MS = 1_000;

    private final LiveFrameGrabber grabber;
    private final Process process;
    private final Thread writer;
    private final Thread reader;
    private final int fps;
    private final AtomicLong framesWritten = new AtomicLong();
    private volatile boolean running = true;
    private volatile boolean died;

    /** {@code audioPort} 0 = silent. */
    DeathRecEncoder(FFmpeg ffmpeg, LiveFrameGrabber grabber, int fps, int audioPort, DeathRecRing ring) throws IOException {
        this.grabber = grabber;
        this.fps = fps;
        this.process = ffmpeg.runFFmpeg(args(grabber.width(), grabber.height(), fps, audioPort,
            FfmpegSupport.inputQueueOption()));
        FfmpegSupport.drain(process.getErrorStream(), "death-rec");
        this.reader = new Thread(() -> readLoop(ring), "dt-death-rec-read");
        this.reader.setDaemon(true);
        this.reader.start();
        this.writer = new Thread(this::writeLoop, "dt-death-rec-write");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    static String[] args(int w, int h, int fps, int audioPort) {
        return args(w, h, fps, audioPort, true);
    }

    /** {@code inputQueue}: give each input {@code -thread_queue_size}; off where ffmpeg rejects it (FFmpeg 9+). */
    static String[] args(int w, int h, int fps, int audioPort, boolean inputQueue) {
        boolean audio = audioPort > 0;
        boolean queue = audio && inputQueue;
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin"));
        if (queue) a.addAll(List.of("-thread_queue_size", String.valueOf(INPUT_QUEUE)));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-"));
        if (audio) {
            if (queue) a.addAll(List.of("-thread_queue_size", String.valueOf(INPUT_QUEUE)));
            a.addAll(List.of(
                "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", "tcp://127.0.0.1:" + audioPort,
                "-map", "0:v:0", "-map", "1:a:0"));
        }
        a.addAll(List.of(
            "-vf", "vflip",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
            "-pix_fmt", "yuv420p", "-threads", String.valueOf(THREADS),
            "-b:v", VIDEO_KBPS + "k", "-maxrate", VIDEO_KBPS + "k", "-bufsize", (VIDEO_KBPS * 2) + "k",
            "-g", String.valueOf(fps), "-keyint_min", String.valueOf(fps), "-sc_threshold", "0"));
        if (audio) a.addAll(List.of("-c:a", "aac", "-b:a", AUDIO_KBPS + "k", "-ar", "48000", "-ac", "2"));
        a.addAll(List.of("-f", "mpegts", "-flush_packets", "1", "pipe:1"));
        return a.toArray(new String[0]);
    }

    /** Video frames fed so far: the clock the sound is paced against. */
    long framesWritten() {
        return framesWritten.get();
    }

    /** True once ffmpeg stopped on its own; the recorder then starts a new one. */
    boolean died() {
        return died || !process.isAlive();
    }

    private void readLoop(DeathRecRing ring) {
        byte[] buf = new byte[READ_BUFFER];
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) ring.append(buf, n, System.currentTimeMillis());
            }
        } catch (IOException e) {
            if (running) LOGGER.warn("[DungeonTrain] death recording output closed: {}", e.toString());
        }
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
                }
                next += periodNs;
                long sleep = next - System.nanoTime();
                if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep); else next = System.nanoTime();
                if (!process.isAlive()) { died = true; break; }
            }
        } catch (IOException e) {
            if (running) { LOGGER.warn("[DungeonTrain] death recording encoder pipe closed: {}", e.toString()); died = true; }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            if (last != null) grabber.recycle(last);
        }
    }

    /** Stop feeding and end ffmpeg within {@code graceMs}. Blocks: never on the render thread. */
    void close(long graceMs) {
        running = false;
        writer.interrupt();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!process.waitFor(Math.max(0, graceMs), TimeUnit.MILLISECONDS)) process.destroyForcibly();
            writer.join(JOIN_MS);
            reader.join(JOIN_MS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(3_000);
    }
}
