package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.VistaModClient;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Vista's downloaded ffmpeg, as the Live Feed uses it. Vista fetches a static build per client the
 * first time its welcome screen is accepted ({@code VistaModClient.getFFmpegFuture()}); DT never
 * downloads anything itself. {@link State} tells the UI what to say while waiting.
 */
public final class FfmpegSupport {

    private static final Logger LOGGER = LogUtils.getLogger();

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
