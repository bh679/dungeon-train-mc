package games.brennan.dungeontrain.client.perf;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Freeze forensics for the client: the render-thread twin of
 * {@link games.brennan.dungeontrain.event.ServerStallWatchdog}. A daemon thread checks four times a
 * second when the last frame started and, once no frame has started for {@link #STALL_LOG_MS}, logs
 * the render thread's live stack (and the sound thread's) to {@code latest.log}.
 *
 * <p>Exists because a Lag report of 29 Oct 2026 ("lagged when I threw a trident") held a single
 * 13,129 ms frame with the server ticking normally throughout: the client hung, and nothing in either
 * log said where. The server has had a watchdog for that since #1335; the client had none.</p>
 *
 * <h2>What is a freeze</h2>
 * <p>Simpler than the server's question. The render loop keeps drawing frames in the pause menu, in
 * every screen and on loading screens, so a frame that has not started for two seconds is a hang
 * whatever the player is doing — there is no idle state to filter out. The one thing that looks like a
 * hang and may not be is macOS holding the main thread in {@code glfwPollEvents} while the window is
 * dragged or resized. That is labelled ({@code osEventLoop=true}), not skipped: a driver hang sits in
 * the same frames.</p>
 *
 * <h2>The sound thread</h2>
 * <p>Every dump also carries the top of the {@code Sound engine} thread's stack. The 29 Oct freeze was
 * followed at once by the train engine loop being re-created, which only happens when the sound
 * manager has dropped it, so the sound system is the first suspect — and a render thread waiting on
 * the sound executor shows only the wait, not what the sound thread is doing.</p>
 *
 * <h2>Budget</h2>
 * <p>The same rules as the server's, for the same reason (the 512 KB log tail a bug report attaches):
 * a freeze from {@link #LONG_STALL_MS} is always dumped; a shorter one at most once per
 * {@link #SHORT_DUMP_COOLDOWN_MS} and {@link #MAX_SHORT_DUMPS} times per session; a freeze that
 * persists is re-dumped every {@link #REDUMP_COOLDOWN_MS}. See {@link #mayDump}.</p>
 *
 * <p>Cost: one volatile write per frame, and a sampler that sleeps 250 ms between reads of it. Stacks
 * are taken only inside a freeze already two seconds old. Never interrupts, never touches game state.
 * Runs in release builds — player reports are the point.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientStallWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A frame that has not started for this long gets its first stack dump. */
    static final long STALL_LOG_MS = 2_000;
    /** A freeze this long is always dumped; shorter ones share {@link #SHORT_DUMP_COOLDOWN_MS}. */
    static final long LONG_STALL_MS = 5_000;
    /** At most one short-freeze dump this often, so a hitchy session cannot fill the log tail. */
    static final long SHORT_DUMP_COOLDOWN_MS = 60_000;
    /** At most this many short-freeze dumps per session; long freezes are not counted or capped. */
    static final int MAX_SHORT_DUMPS = 10;
    /** {@code lastShortDumpAt} before the first short dump. */
    static final long NEVER = -1;
    /** Repeat dumps at most this often while the same freeze persists. */
    private static final long REDUMP_COOLDOWN_MS = 10_000;
    private static final long SAMPLE_INTERVAL_MS = 250;

    /** Vanilla's {@code SoundEngineExecutor} thread name. */
    private static final String SOUND_THREAD_NAME = "Sound engine";
    /** Frames kept from the sound thread — enough to see what it is doing, not the whole loop. */
    private static final int SOUND_FRAMES = 25;

    /** GLFW's binding class and the event-pump method macOS blocks in during a window drag. */
    private static final String GLFW_CLASS = "org.lwjgl.glfw.GLFW";
    private static final String POLL_EVENTS = "PollEvents";
    /** How far from the top the event pump may sit and still be what the thread is blocked in. */
    private static final int OS_SCAN_DEPTH = 6;

    /** {@code System.nanoTime()} at the start of the last frame; 0 before the first. */
    private static volatile long lastFrameNanos;
    private static Thread renderThread;

    private ClientStallWatchdog() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFrame(RenderFrameEvent.Pre event) {
        lastFrameNanos = System.nanoTime();
        if (renderThread == null) start(Thread.currentThread());
    }

    private static void start(Thread render) {
        renderThread = render;
        Thread sampler = new Thread(() -> sampleLoop(render), "DT-Client-Stall-Watchdog");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void sampleLoop(Thread render) {
        long frozenFrame = 0;      // lastFrameNanos of the frame the current report is about; 0 = none
        long lastProbeAt = 0;
        long lastShortDumpAt = NEVER;
        int shortDumps = 0;
        try {
            while (render.isAlive()) {
                Thread.sleep(SAMPLE_INTERVAL_MS);
                long frame = lastFrameNanos;
                long now = System.currentTimeMillis();
                long stalled = (System.nanoTime() - frame) / 1_000_000L;
                if (frozenFrame != 0 && frame != frozenFrame) {
                    // Both stamps are frame starts, so this is the frozen frame's length to the frame.
                    LOGGER.warn("[DT-ClientStallWatchdog] render thread recovered after ~{} ms freeze",
                        (frame - frozenFrame) / 1_000_000L);
                    frozenFrame = 0;
                    lastProbeAt = 0;
                }
                if (now - lastProbeAt < REDUMP_COOLDOWN_MS) continue;
                if (!mayDump(stalled, lastShortDumpAt, shortDumps, now)) continue;

                StackTraceElement[] stack = render.getStackTrace();
                // The frame may have started between reading the clock and taking the stack.
                if (lastFrameNanos != frame) continue;
                dump(render, stack, stalled);
                lastProbeAt = now;
                frozenFrame = frame;
                if (stalled < LONG_STALL_MS) {
                    lastShortDumpAt = now;
                    shortDumps++;
                    if (shortDumps == MAX_SHORT_DUMPS) {
                        LOGGER.warn("[DT-ClientStallWatchdog] {} short-freeze dumps written; freezes under {} ms are no longer dumped this session",
                            MAX_SHORT_DUMPS, LONG_STALL_MS);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            // JVM shutting down — exit quietly
        }
    }

    /**
     * Whether a freeze this long may be dumped now: never under {@link #STALL_LOG_MS}, always from
     * {@link #LONG_STALL_MS}, and in between only once per {@link #SHORT_DUMP_COOLDOWN_MS} and while
     * fewer than {@link #MAX_SHORT_DUMPS} short dumps have been written ({@code lastShortDumpAt} is
     * when the last one was written, or {@link #NEVER}). Package-private and pure, unit-tested.
     */
    static boolean mayDump(long stalledMs, long lastShortDumpAt, int shortDumps, long now) {
        if (stalledMs < STALL_LOG_MS) return false;
        if (stalledMs >= LONG_STALL_MS) return true;
        if (shortDumps >= MAX_SHORT_DUMPS) return false;
        return lastShortDumpAt == NEVER || now - lastShortDumpAt >= SHORT_DUMP_COOLDOWN_MS;
    }

    /**
     * True when the thread is blocked in GLFW's event pump — where macOS parks the main thread while
     * the window is dragged or resized. Only the top {@link #OS_SCAN_DEPTH} frames count: deeper down
     * the pump is just the caller of whatever is really blocking. Package-private, Minecraft-free,
     * unit-tested.
     */
    static boolean isOsEventLoop(StackTraceElement[] stack) {
        if (stack == null) return false;
        int depth = Math.min(stack.length, OS_SCAN_DEPTH);
        for (int i = 0; i < depth; i++) {
            StackTraceElement frame = stack[i];
            if (GLFW_CLASS.equals(frame.getClassName()) && frame.getMethodName().contains(POLL_EVENTS)) {
                return true;
            }
        }
        return false;
    }

    private static void dump(Thread render, StackTraceElement[] stack, long stalledMs) {
        Minecraft mc = Minecraft.getInstance();
        // Racy reads from another thread, for the label only: the render thread is stuck, so these
        // are not changing under us, and a stale value costs nothing but a slightly wrong label.
        Screen screen = mc.screen;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("[DT-ClientStallWatchdog] render thread frozen for ~").append(stalledMs)
            .append(" ms — state=").append(render.getState())
            .append(", inWorld=").append(mc.level != null)
            .append(", screen=").append(screen == null ? "none" : screen.getClass().getSimpleName())
            .append(", osEventLoop=").append(isOsEventLoop(stack))
            .append(", stack:");
        for (StackTraceElement frame : stack) {
            sb.append("\n    at ").append(frame);
        }
        appendSoundThread(sb);
        LOGGER.warn(sb.toString());
    }

    private static void appendSoundThread(StringBuilder sb) {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (!SOUND_THREAD_NAME.equals(thread.getName())) continue;
            StackTraceElement[] stack = entry.getValue();
            sb.append("\n  \"").append(SOUND_THREAD_NAME).append("\" state=").append(thread.getState());
            int depth = Math.min(stack.length, SOUND_FRAMES);
            for (int i = 0; i < depth; i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            if (stack.length > depth) sb.append("\n    ... ").append(stack.length - depth).append(" more");
            return;
        }
        sb.append("\n  (no \"").append(SOUND_THREAD_NAME).append("\" thread)");
    }
}
