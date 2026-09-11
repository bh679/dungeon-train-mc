package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Freeze forensics: a daemon thread that samples the server's tick counter
 * once a second and, when a single tick stalls past {@link #STALL_LOG_MS},
 * logs the server thread's live stack trace (repeating with a cooldown while
 * the stall persists). Exists because in-field freezes ("game locked up,
 * couldn't trade / quit hung") leave no trace once the process dies — this
 * turns the NEXT occurrence into an attributable stack in latest.log.
 *
 * <p>Pure diagnostics: never interrupts, never mutates game state. The
 * sampler thread is a daemon and exits on server stop.</p>
 *
 * <h2>What is not a stall</h2>
 * <p>A stopped tick counter is not by itself a freeze. In singleplayer the Esc menu, a book screen
 * and focus loss all stop the integrated server's tick loop outright, and the counter stands still
 * for as long as the player is away from the keyboard. Measured on the clock alone that is
 * indistinguishable from a hang, and it used to be reported as one: a player log reviewed in
 * September 2026 carried <b>64 stall warnings in one hour</b>, one of them claiming ~194 seconds,
 * every one of them a pause menu. A diagnostic that cries wolf sixty-four times an hour is one
 * nobody reads, which costs exactly the freeze this class exists to catch.</p>
 *
 * <p>So the clock says <i>when</i> to look and the stack says <i>whether it counts</i>. A server
 * thread parked in {@code MinecraftServer.waitUntilNextTick} has <b>finished</b> its tick and is
 * waiting for the next one — idle by definition, whatever stopped the clock (pause menu, laptop
 * sleep, the process suspended). {@link #isIdleBetweenTicks} looks for exactly those frames and the
 * sampler stays quiet when it finds them, so a pause produces no watchdog lines at all;
 * {@link ResumeWatchdog}'s "Resume after Nms pause" remains the one record of it.</p>
 *
 * <p><b>Narrow on purpose.</b> A real stall parks too — the fluid-cascade chunk load in issue #1335
 * sat in {@code ServerChunkCache.getChunk} through {@code managedBlock} and {@code LockSupport.park},
 * and matching on either of those would have suppressed the one report that diagnosed it. What it
 * does not do is go through {@code waitUntilNextTick}, because the work is inside {@code tickServer}.
 * Those frames are the whole signal, and nothing broader is tested.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerStallWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A tick stuck longer than this gets its first stack dump. */
    private static final long STALL_LOG_MS = 5_000;
    /** Repeat dumps at most this often while the same stall persists. */
    private static final long REDUMP_COOLDOWN_MS = 10_000;
    private static final long SAMPLE_INTERVAL_MS = 1_000;

    /** The class whose frames say the tick loop is between ticks rather than inside one. */
    private static final String SERVER_CLASS = "net.minecraft.server.MinecraftServer";

    /**
     * Method names on {@link #SERVER_CLASS} that mean "waiting for the next tick".
     *
     * <p>Matched as substrings because mixins rename the frames they wrap: ModernFix's
     * {@code wrapOperation$zpa0$modernfix$waitLongerForTasks} sits in the middle of this very stack
     * in the player logs this was written against. The real {@code waitUntilNextTick} and
     * {@code waitForTasks} frames are present alongside it, so the plain names would be enough —
     * the third entry costs nothing and survives a mixin that swallows one of them.</p>
     */
    private static final String[] IDLE_METHODS =
        {"waitUntilNextTick", "waitForTasks", "waitLongerForTasks"};

    /**
     * How far into the stack the idle frames are allowed to be, counting from the innermost.
     *
     * <p>The cap is what keeps the test honest. In an idle stack {@code waitUntilNextTick} sits six
     * frames down, under nothing but the park and the event loop; in a stall the blocking work is on
     * top and pushes anything like it past the cap or off the stack entirely. Without the cap a deep
     * enough stall that happened to pass through the tick loop would read as idle.</p>
     */
    private static final int IDLE_SCAN_DEPTH = 12;

    private static final AtomicReference<Thread> RUNNING = new AtomicReference<>();

    private ServerStallWatchdog() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Thread sampler = new Thread(() -> sampleLoop(server), "DT-Stall-Watchdog");
        sampler.setDaemon(true);
        Thread previous = RUNNING.getAndSet(sampler);
        if (previous != null) previous.interrupt();
        sampler.start();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Thread sampler = RUNNING.getAndSet(null);
        if (sampler != null) sampler.interrupt();
    }

    private static void sampleLoop(MinecraftServer server) {
        int lastTick = -1;
        long tickChangedAt = System.currentTimeMillis();
        // When the current stall was last looked at, and whether looking produced a report. Separate
        // because an idle stretch is probed on the same cooldown as a stall but never reported: one
        // stack trace per ten seconds while somebody sits in the pause menu, and no "recovered" line
        // afterwards for an episode that was never announced.
        long lastProbeAt = 0;
        boolean reported = false;
        try {
            while (server.isRunning() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(SAMPLE_INTERVAL_MS);
                int tick = server.getTickCount();
                long now = System.currentTimeMillis();
                if (tick != lastTick) {
                    long stalled = now - tickChangedAt;
                    if (reported) {
                        LOGGER.warn("[DT-StallWatchdog] server thread recovered after ~{} ms stall (tick {} -> {})",
                            stalled, lastTick, tick);
                        reported = false;
                    }
                    lastProbeAt = 0;
                    lastTick = tick;
                    tickChangedAt = now;
                    continue;
                }
                long stalled = now - tickChangedAt;
                if (stalled < STALL_LOG_MS || now - lastProbeAt < REDUMP_COOLDOWN_MS) continue;
                lastProbeAt = now;

                Thread serverThread = server.getRunningThread();
                if (serverThread == null) continue;
                StackTraceElement[] stack = serverThread.getStackTrace();
                // Between ticks rather than stuck in one — a pause, not a freeze. Nothing to say.
                if (isIdleBetweenTicks(stack)) continue;

                dumpServerThread(server, serverThread, stack, stalled);
                reported = true;
            }
        } catch (InterruptedException ignored) {
            // server stopping — exit quietly
        }
    }

    /**
     * True when this stack shows the tick loop waiting for its next tick rather than stuck inside
     * one — see the class javadoc for why that is the test and why it is drawn this narrowly.
     *
     * <p>Package-private and free of Minecraft types so it can be exercised against captured stacks
     * directly; {@code ServerStallWatchdogTest} holds one real idle stack and one real stall.</p>
     */
    static boolean isIdleBetweenTicks(StackTraceElement[] stack) {
        if (stack == null) return false;
        int depth = Math.min(stack.length, IDLE_SCAN_DEPTH);
        for (int i = 0; i < depth; i++) {
            StackTraceElement frame = stack[i];
            if (!SERVER_CLASS.equals(frame.getClassName())) continue;
            String method = frame.getMethodName();
            for (String idle : IDLE_METHODS) {
                if (method.contains(idle)) return true;
            }
        }
        return false;
    }

    /**
     * Write down a stall, with the stack that was already taken to classify it.
     *
     * <p>Handed the thread and its stack rather than reading them again: the point of a dump is the
     * moment it describes, and a second {@code getStackTrace()} a few microseconds later is a
     * different moment — and a second safepoint for the same report.</p>
     */
    private static void dumpServerThread(MinecraftServer server, Thread serverThread,
                                         StackTraceElement[] stack, long stalledMs) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[DT-StallWatchdog] server tick ").append(server.getTickCount())
            .append(" stalled for ~").append(stalledMs).append(" ms — server thread state=")
            .append(serverThread.getState()).append(", stack:");
        for (StackTraceElement frame : stack) {
            sb.append("\n    at ").append(frame);
        }
        LOGGER.warn(sb.toString());
    }
}
