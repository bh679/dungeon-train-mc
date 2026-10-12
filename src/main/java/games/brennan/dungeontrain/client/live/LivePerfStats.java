package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * The {@code [live]} perf line: once a minute while this client streams or decodes the Live Feed,
 * one summary on {@link DtLogging#PERF} (the uncapped logger lag reports are read from, at DEBUG like
 * {@code [mspt]}) — frame grabs, encoder writes and CPU, decoded frames, texture uploads, decoder CPU,
 * and the frame rate around them. Nothing logs while Live Eye is idle.
 *
 * <p>The hot paths only bump {@link LivePerfWindow} counters. Encoder and decoder CPU come from
 * {@link ProcessHandle.Info#totalCpuDuration()} sampled at the window boundary — a syscall, never a
 * spawned process. Java reads it on Windows and Linux; on macOS it reports none for another process
 * (checked 2026-10-12 on JDK 21), so a Mac's line says {@code encCpu=n/a decCpu=n/a}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LivePerfStats {

    static final long WINDOW_NS = 60_000_000_000L;

    private static final Logger PERF = LoggerFactory.getLogger(DtLogging.PERF);
    private static final LivePerfWindow WINDOW = new LivePerfWindow();
    private static final CpuSampler ENCODER_CPU = new CpuSampler();
    private static final CpuSampler DECODER_CPU = new CpuSampler();

    private static volatile boolean active;
    private static boolean activeSeen;
    private static long windowStartNs = System.nanoTime();
    private static long lastFrameNs;

    private LivePerfStats() {}

    // ---- hot-path hooks (any thread) ----------------------------------------------------------

    static void grab(long ns) { WINDOW.grab(ns); }
    static void grabSkipped() { WINDOW.grabSkipped(); }
    static void encoderWrote(boolean fresh) { WINDOW.encoderWrote(fresh); }
    static void decoded() { WINDOW.decoded(); }
    static void decoderRun() { WINDOW.decoderRun(); }
    static void stall() { WINDOW.stall(); }
    static void upload(long ns) { WINDOW.upload(ns); }

    // ---- frame interval and window boundary (render thread) -----------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrame(RenderFrameEvent.Pre event) {
        if (!active) {
            lastFrameNs = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNs != 0) WINDOW.frame(now - lastFrameNs);
        lastFrameNs = now;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LiveStreamController controller = LiveStreamController.get();
        boolean streaming = controller.streaming();
        LiveHlsSession session = LiveFeedSource.MAIN.currentSession();
        int sessions = LiveFeedSource.MAIN.decodingSessions();
        active = streaming || sessions > 0;
        activeSeen |= active;

        long now = System.nanoTime();
        long wall = now - windowStartNs;
        if (wall < WINDOW_NS) return;
        windowStartNs = now;
        LivePerfWindow.Snapshot snapshot = WINDOW.drain();
        LiveEncoder encoder = controller.encoder();
        Double encCpu = ENCODER_CPU.sample(encoder == null ? null : encoder.process(), wall);
        Double decCpu = DECODER_CPU.sample(session == null ? null : session.currentProcess(), wall);
        if (activeSeen) {
            LiveFrameGrabber grabber = controller.grabber();
            String size = grabber == null ? null
                : grabber.width() + "x" + grabber.height() + "@" + LiveFeedClientConfig.captureFps();
            LivePerfWindow.Context context = new LivePerfWindow.Context(streaming, size, encCpu,
                sessions, session == null ? 0 : session.width(), decCpu, Minecraft.getInstance().getFps());
            PERF.debug(LivePerfWindow.format(snapshot, context));
        }
        activeSeen = active;
    }

    /** CPU % of one core for one process across windows; restarts its baseline when the process changes. */
    private static final class CpuSampler {
        private long pid = -1;
        @Nullable private Duration last;

        @Nullable
        Double sample(@Nullable Process process, long wallNs) {
            if (process == null) {
                pid = -1;
                last = null;
                return null;
            }
            ProcessHandle.Info info;
            try {
                info = process.toHandle().info();
            } catch (RuntimeException e) {
                return null;
            }
            Duration now = info.totalCpuDuration().orElse(null);
            Double pct;
            if (process.pid() == pid) {
                pct = LivePerfWindow.cpuPercent(last, now, wallNs);
            } else {
                // A process new this window: its CPU since it started, over its lifetime so far.
                Instant start = info.startInstant().orElse(null);
                long life = start == null ? 0 : Duration.between(start, Instant.now()).toNanos();
                pct = LivePerfWindow.cpuPercent(Duration.ZERO, now, life);
            }
            pid = process.pid();
            last = now;
            return pct;
        }
    }
}
