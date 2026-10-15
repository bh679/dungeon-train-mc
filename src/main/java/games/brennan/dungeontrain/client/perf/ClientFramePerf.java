package games.brennan.dungeontrain.client.perf;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code [frame]} perf line: every 10 s while a world is loaded, one summary on
 * {@link DtLogging#PERF} (the uncapped logger lag reports are read from, at DEBUG like {@code [mspt]})
 * — measured frame rate, frame-time percentiles and hitch counts ({@link FrameTimeWindow}).
 *
 * <p>The {@code [live]} line carries {@code fps=}/{@code worstFrameMs=} too, but only once a minute
 * and only while the Live Feed runs; this one is there for every lag report. The interval is taken at
 * {@code RenderFrameEvent.Pre}, the same point {@code LivePerfStats} times, so the two agree. Nothing
 * logs at the title screen, and the first frame after a world loads starts the clock afresh rather
 * than counting the load as one long frame.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientFramePerf {

    static final long WINDOW_NS = 10_000_000_000L;

    /** {@code Options#framerateLimit} at its top step means no limit ("Unlimited" in the slider). */
    private static final int UNLIMITED_FRAMERATE = 260;

    private static final Logger PERF = LoggerFactory.getLogger(DtLogging.PERF);
    private static final FrameTimeWindow WINDOW = new FrameTimeWindow();

    private static long lastFrameNs;
    private static long windowStartNs;

    private ClientFramePerf() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            lastFrameNs = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameNs != 0) WINDOW.record(now - lastFrameNs, mc.isPaused());
        lastFrameNs = now;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mc.level == null) {
            // Out of a world: drop whatever the last one left, and start the next window on entry.
            WINDOW.drain();
            windowStartNs = 0;
            return;
        }
        if (windowStartNs == 0) {
            windowStartNs = now;
            return;
        }
        long wall = now - windowStartNs;
        if (wall < WINDOW_NS) return;
        windowStartNs = now;
        FrameTimeWindow.Snapshot snapshot = WINDOW.drain();
        int limit = mc.getWindow().getFramerateLimit();
        FrameTimeWindow.Context context = new FrameTimeWindow.Context(wall,
            limit >= UNLIMITED_FRAMERATE ? "max" : Integer.toString(limit),
            mc.options.enableVsync().get(),
            mc.isWindowActive(),
            mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        PERF.debug(FrameTimeWindow.format(snapshot, context));
    }
}
