package games.brennan.dungeontrain.client.lod;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Frametime sampler for the LOD A/B measurement. Off unless
 * {@code -Ddungeontrain.lodPerfSample=true} is set at launch, so a normal install pays one
 * boolean check per frame.
 *
 * <h2>Why percentiles, and why not {@code Minecraft#getFps()}</h2>
 *
 * <p>The existing {@code [fps]} line reports {@code getFps()}, a once-per-second integer counter.
 * Averaged frames-per-second is the wrong statistic for judging an LOD: the failure mode of a
 * baked-mesh tier is a <em>hitch</em> when several carriages cross the threshold together and bake
 * on the same frame. A mean — or an integer FPS counter — hides exactly that, and would happily
 * report an improvement for a change that made the experience worse. p95/p99 are where a hitch
 * shows up, so those are what this reports.</p>
 *
 * <p>Sampling hooks {@link RenderLevelStageEvent} at a single stage, which fires once per frame
 * while a level is rendering. That deliberately excludes menu and loading frames, which would
 * otherwise dilute the window with frames that have no carriages in them.</p>
 *
 * <h2>Reading the output</h2>
 *
 * <p>One {@code [lodperf]} line per {@link #WINDOW_MS}, reporting the distribution over that
 * window only (not cumulative), plus the LOD state and tier split that produced it. Each line is
 * self-describing so a harness can select the lines inside its measurement window and needs no
 * separate record of what the client was configured to do.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LodPerfSampler {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Launch switch. Read once — this is measurement scaffolding, not a runtime setting. */
    public static final boolean ENABLED =
        Boolean.getBoolean("dungeontrain.lodPerfSample");

    /**
     * Reporting window, overridable with {@code -Ddungeontrain.lodPerfWindowMs=<n>}.
     *
     * <p>Default 10s for interactive use. The A/B harness sets it to the full measurement period
     * so each arm produces <b>one</b> line describing every frame in that period. That matters:
     * aggregating short windows would mean taking percentiles of percentiles, which is not a
     * meaningful statistic — the p99 of a set of p99s is neither the p99 of the frames nor
     * anything else in particular.</p>
     */
    private static final long WINDOW_MS =
        Long.getLong("dungeontrain.lodPerfWindowMs", 10_000L);

    /**
     * Ring capacity. 65536 covers a 120s window at ~545fps, so a full harness arm fits without
     * dropping samples and the percentiles describe every frame rather than a truncated prefix.
     * If a window ever does overflow, {@code dropped} says so rather than quietly biasing the
     * result toward the early part of the window.
     */
    private static final int MAX_SAMPLES = 65536;

    private static int dropped;

    private static final RenderLevelStageEvent.Stage STAGE =
        RenderLevelStageEvent.Stage.AFTER_LEVEL;

    private static final long[] samples = new long[MAX_SAMPLES];
    private static int count;
    private static long lastFrameNanos;
    private static long windowStartMs;

    /**
     * Optional raw per-frame dump: {@code -Ddungeontrain.lodPerfCsv=<path>} writes one
     * {@code epochMillis,frameNanos} row per frame.
     *
     * <p>This exists because the aggregate {@code [lodperf]} window is anchored to the first
     * rendered frame, not to whatever moment the harness cares about — so a window boundary can
     * (and did) fall mid-measurement, leaving the only reported window straddling the join, the
     * teleport and part of the hold. Timestamped raw samples let the report select exactly the
     * frames inside the measurement period and compute true pooled percentiles over them, instead
     * of trusting a window whose bounds nobody controls.</p>
     */
    private static final String CSV_PATH = System.getProperty("dungeontrain.lodPerfCsv");

    private static java.io.BufferedWriter csv;
    private static boolean csvFailed;
    private static long lastFlushMs;

    private LodPerfSampler() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ENABLED) return;
        if (event.getStage() != STAGE) return;

        long now = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        if (lastFrameNanos != 0) {
            long delta = now - lastFrameNanos;
            if (count < MAX_SAMPLES) samples[count++] = delta;
            else dropped++;
            writeCsv(nowMs, delta);
        } else {
            // First frame of the session: no previous timestamp, so there is no delta to record.
            windowStartMs = nowMs;
        }
        lastFrameNanos = now;

        if (nowMs - windowStartMs >= WINDOW_MS) {
            report(nowMs - windowStartMs);
            count = 0;
            dropped = 0;
            windowStartMs = nowMs;
        }
    }

    private static void report(long windowMs) {
        if (count == 0) return;

        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);

        Minecraft mc = Minecraft.getInstance();
        LOGGER.info(
            "[lodperf] lod={} frames={} dropped={} windowMs={} p50={} p95={} p99={} max={} meanFps={} near={} far={} baked={}",
            CarriageLodController.ENABLED ? "on" : "off",
            count,
            dropped,
            windowMs,
            ms(percentile(sorted, 50)),
            ms(percentile(sorted, 95)),
            ms(percentile(sorted, 99)),
            ms(sorted[sorted.length - 1]),
            String.format("%.1f", count * 1000.0 / windowMs),
            CarriageLodController.lastNear(),
            CarriageLodController.lastFar(),
            CarriageMeshCache.size());

        if (mc.level == null) {
            LOGGER.info("[lodperf] (no level — window discarded)");
        }
    }

    /**
     * Append one raw sample. Best-effort: a dump failure disables the dump and logs once, rather
     * than throwing on the render thread — losing the measurement file is bad, crashing the client
     * mid-measurement is worse.
     */
    private static void writeCsv(long nowMs, long frameNanos) {
        if (CSV_PATH == null || csvFailed) return;
        try {
            if (csv == null) {
                csv = java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(CSV_PATH));
                csv.write("epochMillis,frameNanos,lod,throttled\n");
                lastFlushMs = nowMs;
            }
            csv.write(nowMs + "," + frameNanos
                + "," + (CarriageLodController.ENABLED ? 1 : 0)
                + "," + (isThrottled() ? 1 : 0) + "\n");
            // Flush on a timer so a killed client still leaves a usable file — the harness stops
            // the JVM without a clean shutdown, so an unflushed tail would be lost.
            if (nowMs - lastFlushMs >= 1000L) {
                csv.flush();
                lastFlushMs = nowMs;
            }
        } catch (Exception e) {
            csvFailed = true;
            LOGGER.warn("[lodperf] raw dump disabled: {}", e.toString());
        }
    }

    /**
     * Whether DT's idle framerate throttle is capping this frame.
     *
     * <p>Recorded per frame because it silently invalidates a frametime comparison: the throttle
     * caps the client to 30fps whenever the window is unfocused, and an unattended harness window
     * is <em>always</em> unfocused. An early A/B run measured 47.7 vs 29.3 fps and looked like a
     * large regression; it was two arms throttled by different amounts, with the tails of both
     * pinned to the cap. Recording it lets the report reject such a run instead of reporting the
     * cap as a finding about the LOD.</p>
     */
    private static boolean isThrottled() {
        Minecraft mc = Minecraft.getInstance();
        return games.brennan.dungeontrain.client.FramerateThrottle.shouldThrottle(
            mc.isPaused(),
            mc.isWindowActive(),
            games.brennan.dungeontrain.config.ClientDisplayConfig.isFramerateThrottleEnabled(),
            games.brennan.dungeontrain.client.VrCompat.isVivecraftPresent());
    }

    /** Nearest-rank percentile over an ascending array. */
    private static long percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static String ms(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
