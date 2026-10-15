package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The moment a live streamer died, as a short silent clip: the last {@link #BEFORE_S} seconds before
 * death and a beat after, cut from the stream's own segments. It loops under the first death page
 * ({@link DeathClipBackground}) and goes to the relay as {@code death.mp4}, which the #live-eye
 * "has gone offline — died" post turns into its GIF.
 *
 * <p>The encoder cuts {@code segNNNNN.ts} exactly every segment length S, each starting on a
 * keyframe, so segment k holds stream time [kS, (k+1)S). The death's stream time is the encoder's
 * frame count over the frame rate. The segment that holds the death is still being written when it
 * happens; ffmpeg only gives it its final name once it is finished, so the clip waits for that,
 * copies the segments out (the encoder deletes old ones as it goes, and the whole folder when the
 * stream ends) and cuts them on its own thread. Anything going wrong just means no clip: the first
 * page keeps its ride photo and the post keeps its photo.</p>
 *
 * <p>Render thread, except the cut and upload.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveDeathClip {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final double BEFORE_S = 5.0;
    static final double AFTER_S = 0.5;
    /** The clip's width; height follows (16:9). */
    static final int WIDTH = 640;
    /** On top of one segment, how long to wait for the death's segment to finish before giving up. */
    static final long EXTRA_WAIT_MS = 10_000;
    static final long CUT_TIMEOUT_S = 30;
    static final String UPLOAD_NAME = "death.mp4";
    private static final ExecutorService CUTTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dt-live-death-clip");
        t.setDaemon(true);
        return t;
    });

    /** Which segments hold the clip, where in them it starts, and how long it runs. */
    record Window(int firstSegment, int lastSegment, double offsetS, double durationS) {}

    private record Pending(Path dir, Window window, String token, long deadlineMs, int generation) {}

    @Nullable private static Pending pending;
    private static int generation;
    /** The finished clip for the latest death, or null while there is none (yet). */
    @Nullable private static volatile Path ready;

    private LiveDeathClip() {}

    /** The segments and cut for a death at {@code deathS} seconds into a stream cut every {@code segmentS}. */
    static Window window(double deathS, int segmentS) {
        double start = Math.max(0, deathS - BEFORE_S);
        double end = deathS + AFTER_S;
        int first = (int) Math.floor(start / segmentS);
        int last = (int) Math.floor(end / segmentS);
        return new Window(first, last, start - (double) first * segmentS, end - start);
    }

    static String segmentName(int index) {
        return String.format(Locale.ROOT, "seg%05d.ts", index);
    }

    /** ffmpeg's cut: the copied segments joined, trimmed to the window, small and silent. */
    static String[] cutArgs(List<Path> segments, Window w, Path out) {
        StringBuilder concat = new StringBuilder("concat:");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) concat.append('|');
            concat.append(segments.get(i).toAbsolutePath());
        }
        return new String[] {"-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-i", concat.toString(),
            "-ss", String.format(Locale.ROOT, "%.3f", w.offsetS()),
            "-t", String.format(Locale.ROOT, "%.3f", w.durationS()),
            "-an", "-vf", "scale=" + WIDTH + ":-2", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toAbsolutePath().toString()};
    }

    /** The finished clip for the latest death, or null. */
    @Nullable
    public static Path ready() {
        return ready;
    }

    static Path clipsRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dungeontrain").resolve("live-clips");
    }

    /** The streamer died {@code deathS} seconds into a broadcast written to {@code dir}. */
    static void begin(Path dir, double deathS, int segmentS, String token) {
        generation++;
        ready = null;
        DeathClipBackground.close();
        Window w = window(deathS, segmentS);
        long deadline = System.currentTimeMillis() + segmentS * 1000L + EXTRA_WAIT_MS;
        pending = new Pending(dir, w, token, deadline, generation);
        LOGGER.info("[DungeonTrain] live: death clip from {} (segments {}-{}, +{}s)",
            String.format(Locale.ROOT, "%.1fs", deathS), w.firstSegment(), w.lastSegment(),
            String.format(Locale.ROOT, "%.2f", w.offsetS()));
    }

    /** Client tick: once the death's segment is finished, copy the segments out and cut them. */
    static void tick() {
        Pending p = pending;
        if (p == null) return;
        if (!Files.exists(p.dir().resolve(segmentName(p.window().lastSegment())))) {
            if (System.currentTimeMillis() > p.deadlineMs() || !Files.isDirectory(p.dir())) {
                LOGGER.info("[DungeonTrain] live: no death clip — its segments never finished");
                pending = null;
            }
            return;
        }
        pending = null;
        FFmpeg ffmpeg = FfmpegSupport.get();
        if (ffmpeg == null) return;
        List<Path> copies;
        try {
            copies = copyOut(p);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] live: no death clip — could not copy its segments", e);
            return;
        }
        CUTTER.execute(() -> cut(ffmpeg, p, copies));
    }

    /** On the render thread, before the encoder can delete them: a copy of every segment the clip needs. */
    private static List<Path> copyOut(Pending p) throws IOException {
        Path work = clipsRoot().resolve("work");
        Files.createDirectories(work);
        List<Path> out = new ArrayList<>();
        for (int k = p.window().firstSegment(); k <= p.window().lastSegment(); k++) {
            Path dst = work.resolve(segmentName(k));
            Files.copy(p.dir().resolve(segmentName(k)), dst, StandardCopyOption.REPLACE_EXISTING);
            out.add(dst);
        }
        return out;
    }

    private static void cut(FFmpeg ffmpeg, Pending p, List<Path> segments) {
        Path root = clipsRoot();
        Path tmp = root.resolve("death.tmp.mp4");
        Path out = root.resolve("death.mp4");
        try {
            Process proc = ffmpeg.runFFmpeg(cutArgs(segments, p.window(), tmp));
            FfmpegSupport.drain(proc.getInputStream(), "death clip");
            FfmpegSupport.drain(proc.getErrorStream(), "death clip");
            if (!proc.waitFor(CUT_TIMEOUT_S, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                LOGGER.warn("[DungeonTrain] live: death clip cut timed out");
                return;
            }
            if (proc.exitValue() != 0 || !Files.exists(tmp) || Files.size(tmp) == 0) {
                LOGGER.warn("[DungeonTrain] live: death clip cut failed (exit {})", proc.exitValue());
                return;
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[DungeonTrain] live: death clip ready ({} bytes)", Files.size(out));
            Minecraft.getInstance().execute(() -> {
                if (p.generation() == generation) ready = out;
            });
            upload(p.token(), out);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] live: death clip failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (Path s : segments) {
                try { Files.deleteIfExists(s); } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    /**
     * To the relay as this session's {@code death.mp4}, for the #live-eye offline post. The relay takes
     * it from the stream's own token, even after the stream ended; after a takeover the token is stale
     * and it is refused, and the post keeps the death photo.
     */
    private static void upload(String token, Path clip) {
        LiveFeedClient.presign(token, List.of(UPLOAD_NAME)).thenCompose(r -> {
            SignedPut target = LiveFeedClient.parsePresign(r).get(UPLOAD_NAME);
            if (target == null) {
                LOGGER.info("[DungeonTrain] live: death clip not uploaded (relay said {})", r.status());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return LiveFeedClient.upload(target, clip).thenAccept(u ->
                LOGGER.info("[DungeonTrain] live: death clip uploaded ({})", u.status()));
        }).exceptionally(e -> {
            LOGGER.warn("[DungeonTrain] live: death clip upload failed: {}", e.toString());
            return null;
        });
    }

    /** A respawn (a server) or leaving the world: that death's clip is done with. */
    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        forget();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        forget();
    }

    /** A new stream, a respawn or leaving: last death's clip is old news. */
    static void forget() {
        pending = null;
        ready = null;
        generation++;
        DeathClipBackground.close();
    }
}
