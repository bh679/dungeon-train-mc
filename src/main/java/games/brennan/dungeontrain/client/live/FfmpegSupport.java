package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.VistaModClient;
import net.minecraft.Util;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Vista's downloaded ffmpeg, as the Live Feed uses it. Vista fetches a static build per client the
 * first time its welcome screen is accepted ({@code VistaModClient.getFFmpegFuture()}); DT never
 * downloads anything itself. {@link State} tells the UI what to say while waiting.
 */
public final class FfmpegSupport {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** An {@code -encoders} line for ffmpeg's own AAC encoder: flags starting with A (audio), then {@code aac}. */
    private static final Pattern AAC_ENCODER = Pattern.compile("(?m)^\\s*A\\S*\\s+aac\\s");
    private static final long PROBE_TIMEOUT_S = 5;
    @Nullable private static CompletableFuture<Boolean> aac;
    @Nullable private static volatile Boolean readrateCatchup;

    public enum State {
        /** Vista has not been asked to fetch ffmpeg (its welcome screen was declined or not yet shown). */
        OFF,
        /** Vista is downloading or verifying the binary. */
        DOWNLOADING,
        /** Vista tried and failed; no ffmpeg this session. */
        FAILED,
        READY
    }

    private FfmpegSupport() {}

    public static State state() {
        CompletableFuture<FFmpeg> f = VistaModClient.getFFmpegFuture();
        if (f == null) return State.OFF;
        if (!f.isDone()) return State.DOWNLOADING;
        return f.isCompletedExceptionally() ? State.FAILED : State.READY;
    }

    @Nullable
    public static FFmpeg get() {
        return state() == State.READY ? VistaModClient.getFFmpeg() : null;
    }

    /** Resolves to Vista's ffmpeg once ready; fails if Vista's download failed or is off. */
    public static CompletableFuture<FFmpeg> whenReady() {
        CompletableFuture<FFmpeg> f = VistaModClient.getFFmpegFuture();
        if (f == null) return CompletableFuture.failedFuture(new IllegalStateException("vista ffmpeg off"));
        return f;
    }

    /**
     * Whether this ffmpeg can encode AAC (stream audio). Asked once per launch with
     * {@code ffmpeg -encoders}, on a pool thread once Vista's ffmpeg is ready — never on the render
     * thread. A failed, missing or slow answer counts as no. Any thread.
     */
    public static synchronized CompletableFuture<Boolean> aacEncoder() {
        CompletableFuture<Boolean> known = aac;
        if (known != null) return known;
        // Vista not asked yet: answer no for now, and ask again once it has been.
        if (VistaModClient.getFFmpegFuture() == null) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> probe = whenReady()
            .thenApplyAsync(FfmpegSupport::probeAac, Util.ioPool())
            .exceptionally(e -> false);
        aac = probe;
        return probe;
    }

    /**
     * Blocking: runs {@code ffmpeg -encoders} with stdout drained on its own thread, so a stuck
     * process is bounded by {@link #PROBE_TIMEOUT_S} rather than by its pipe closing.
     */
    private static boolean probeAac(FFmpeg ffmpeg) {
        try {
            Process p = ffmpeg.runFFmpeg("-hide_banner", "-encoders");
            drain(p.getErrorStream(), "encoders-err");
            CompletableFuture<String> out = readAll(p.getInputStream(), "encoders-out");
            if (!p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOGGER.warn("[DungeonTrain] ffmpeg -encoders took over {} s; stream audio off", PROBE_TIMEOUT_S);
                return false;
            }
            return listsAacEncoder(out.get(PROBE_TIMEOUT_S, TimeUnit.SECONDS));
        } catch (IOException | ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] could not ask ffmpeg for its encoders: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Reads a process stream to its end on a daemon thread. */
    private static CompletableFuture<String> readAll(InputStream in, String tag) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (in) {
                result.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        }, "dt-live-" + tag + "-read");
        t.setDaemon(true);
        t.start();
        return result;
    }

    /**
     * Whether this ffmpeg has {@code -readrate_catchup} (7.1+), which lets the Live Feed decoder pace
     * at real speed yet catch up after a stall. Vista prefers a system ffmpeg on PATH, which can be
     * older. Asked once per launch with {@code ffmpeg -h long}; a failed or slow answer counts as no.
     */
    public static boolean hasReadrateCatchup(FFmpeg ffmpeg) {
        Boolean known = readrateCatchup;
        if (known != null) return known;
        boolean found = false;
        try {
            Process p = ffmpeg.runFFmpeg("-hide_banner", "-h", "long");
            drain(p.getErrorStream(), "help-err");
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)) found = listsReadrateCatchup(out); else p.destroyForcibly();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] could not ask ffmpeg for its options: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        readrateCatchup = found;
        LOGGER.info("[DungeonTrain] live decoder: ffmpeg {} -readrate_catchup", found ? "has" : "lacks");
        return found;
    }

    /** True when {@code ffmpeg -h long} output lists the {@code -readrate_catchup} option. */
    static boolean listsReadrateCatchup(String helpOutput) {
        return helpOutput.contains("-readrate_catchup ");
    }

    /** True when {@code ffmpeg -encoders} output lists the native {@code aac} encoder. */
    static boolean listsAacEncoder(String encodersOutput) {
        return AAC_ENCODER.matcher(encodersOutput).find();
    }

    /** Drain a process stream to the log on a daemon thread — ffmpeg blocks on a full stderr pipe. */
    public static void drain(InputStream in, String tag) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) LOGGER.debug("[DungeonTrain] {}: {}", tag, line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "dt-live-" + tag + "-drain");
        t.setDaemon(true);
        t.start();
    }
}
