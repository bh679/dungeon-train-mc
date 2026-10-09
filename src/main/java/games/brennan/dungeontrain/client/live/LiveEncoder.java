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

/**
 * Feeds grabbed frames to an ffmpeg process that writes live HLS into a session directory.
 *
 * <p>A writer thread paces at exactly {@code fps}: every tick it sends the newest frame the
 * {@link LiveFrameGrabber} produced, or the previous one again if the game rendered nothing new,
 * so ffmpeg's raw-video input is genuinely constant-rate however the frame rate wobbles. One GOP
 * per segment ({@code -g fps*segment}) keeps every segment independently decodable, which is what
 * lets a viewer join at any segment boundary.</p>
 *
 * <p>Output: {@code seg00000.ts, seg00001.ts, …} and {@code live.m3u8} in {@code dir}; ffmpeg
 * deletes segments that fall out of the 6-entry window itself, writes each segment to a temp name
 * until complete, and rewrites the playlist only when a segment is whole — so the uploader can
 * trust the playlist as its manifest of finished files.</p>
 */
public final class LiveEncoder implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LIST_SIZE = 6;

    private final LiveFrameGrabber grabber;
    private final Process process;
    private final Thread writer;
    private final int fps;
    private volatile boolean running = true;
    private volatile boolean died;

    public LiveEncoder(FFmpeg ffmpeg, LiveFrameGrabber grabber, Path dir, int fps, int bitrateKbps, int segmentSeconds) throws IOException {
        this.grabber = grabber;
        this.fps = fps;
        this.process = ffmpeg.runFFmpeg(args(grabber.width(), grabber.height(), fps, bitrateKbps, segmentSeconds, dir));
        FfmpegSupport.drain(process.getErrorStream(), "encoder");
        FfmpegSupport.drain(process.getInputStream(), "encoder-out");
        this.writer = new Thread(this::writeLoop, "dt-live-encoder");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    static String[] args(int w, int h, int fps, int bitrateKbps, int segmentSeconds, Path dir) {
        int gop = fps * segmentSeconds;
        List<String> a = new ArrayList<>(List.of(
            "-hide_banner", "-loglevel", "error", "-nostdin",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-",
            "-vf", "vflip",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-pix_fmt", "yuv420p",
            "-b:v", bitrateKbps + "k", "-maxrate", bitrateKbps + "k", "-bufsize", (bitrateKbps * 2) + "k",
            "-g", String.valueOf(gop), "-keyint_min", String.valueOf(gop), "-sc_threshold", "0",
            "-f", "hls", "-hls_time", String.valueOf(segmentSeconds), "-hls_list_size", String.valueOf(LIST_SIZE),
            "-hls_flags", "delete_segments+independent_segments+temp_file",
            "-hls_segment_filename", dir.resolve("seg%05d.ts").toString(),
            dir.resolve(LivePlaylist.FILE_NAME).toString()));
        return a.toArray(new String[0]);
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

    /** Stop feeding, let ffmpeg finish the last segment (it writes ENDLIST), then kill it. */
    @Override
    public void close() {
        running = false;
        writer.interrupt();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
