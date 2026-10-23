package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.Map;
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
 *
 * <h2>What is a stall</h2>
 * <p>The idle test alone was not enough. A paused integrated server does not stay parked in
 * {@code waitUntilNextTick}: its tick counter stands still while the thread spins through
 * {@code runServer}'s bookkeeping ({@code Util.getNanos}, {@code logFullTickTime},
 * {@code finishMeasuringTaskExecutionTime}, ModernFix's {@code preventTicks} wrapper,
 * {@code isPaused}), and a sample that lands on any of those frames reads as neither idle nor
 * paused. A player log of October 2026 carried 53 dumps of exactly that, the last claiming a stall
 * of ~9,480,025 ms, which filled the 512 KB log tail a bug report attaches and buried the lines
 * that matter.</p>
 *
 * <p>So besides the negative test there is a positive one: {@link #isInsideTick} asks whether the
 * thread is actually doing tick work. A stall is reported only when the stack carries
 * {@code MinecraftServer.tickServer} or {@code tickChildren} (work inside a tick),
 * {@code BlockableEventLoop.doRunTask} (a queued task between ticks) or the off-thread chunk-load
 * marshal {@code lambda$getChunk$0}. Checked against the 172 watchdog dumps in that sweep's
 * bug-report corpus: every one of the 84 genuine stalls (Sable's {@code inhabitChunk} chunk load,
 * the synchronous log flush, {@code saveAll}, the fluid post-process) carries one of those frames,
 * and none of the 88 pause dumps does.</p>
 *
 * <h2>Short stalls</h2>
 * <p>The first dump used to wait 5 s. What players report as "lag spikes" are mostly shorter than
 * that: a log of October 2026 (jumping off the train) held two real stalls of 2.6 s and 4.0 s with
 * the client at 100 fps, and the watchdog said nothing about either. So the first dump now comes at
 * {@link #STALL_LOG_MS} = 1.5 s, and the counter is sampled every 250 ms so a stall that short is
 * seen while it is still happening rather than on the tick after it.</p>
 *
 * <p>Short stalls are far more common than long ones (0 to 21 tick-lag warnings in the 1.5-5 s
 * band per session across 36 player logs, pause resumes included), so a stall that has not yet
 * reached {@link #LONG_STALL_MS} is dumped at most once per {@link #SHORT_DUMP_COOLDOWN_MS}. A
 * stall past that line is always dumped, exactly as before: the budget only ever drops short
 * reports. See {@link #mayDump}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerStallWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A tick stuck longer than this gets its first stack dump. */
    static final long STALL_LOG_MS = 1_500;
    /** A stall this long is always dumped; shorter ones share {@link #SHORT_DUMP_COOLDOWN_MS}. */
    static final long LONG_STALL_MS = 5_000;
    /** At most one short-stall dump this often, so a laggy session cannot fill the log tail. */
    static final long SHORT_DUMP_COOLDOWN_MS = 60_000;
    /** {@code lastShortDumpAt} before the first short dump. */
    static final long NEVER = -1;
    /** Repeat dumps at most this often while the same stall persists. */
    private static final long REDUMP_COOLDOWN_MS = 10_000;
    private static final long SAMPLE_INTERVAL_MS = 250;

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
     * The singleplayer server's own pause loop. While the game is paused the integrated server
     * never reaches {@code waitUntilNextTick}: {@code tickServer} short-circuits into
     * {@code tickPaused}, which is where the player log of 19 Sep 2026 found it "stalled" for
     * 225 s — a player sitting in the Esc menu, not a freeze.
     */
    private static final String INTEGRATED_SERVER_CLASS = "net.minecraft.client.server.IntegratedServer";
    private static final String PAUSED_METHOD = "tickPaused";

    /**
     * How far into the stack the idle frames are allowed to be, counting from the innermost.
     *
     * <p>The cap is what keeps the test honest. In an idle stack {@code waitUntilNextTick} sits six
     * frames down, under nothing but the park and the event loop; in a stall the blocking work is on
     * top and pushes anything like it past the cap or off the stack entirely. Without the cap a deep
     * enough stall that happened to pass through the tick loop would read as idle.</p>
     */
    private static final int IDLE_SCAN_DEPTH = 12;

    /**
     * The chunk source, and the two method names on it that tell the off-thread story.
     *
     * <p>{@code ServerChunkCache.getChunk(x, z, status, load)} called from any thread but the
     * server's marshals the load onto the server thread as {@code lambda$getChunk$0} and blocks the
     * caller on the future. The server thread then generates that chunk synchronously inside
     * {@code waitUntilNextTick}'s task drain — which is where a player log of 28 Sep 2026 found it
     * for 22 s, with the requester nowhere in the dump because only the server thread was dumped.
     * When the server stack carries the marshal frame, the requesters are the other threads
     * sitting in {@code ServerChunkCache.getChunk}; they are dumped alongside so the next such
     * stall names who asked.</p>
     */
    private static final String CHUNK_CACHE_CLASS = "net.minecraft.server.level.ServerChunkCache";
    private static final String CHUNK_CACHE_GET = "getChunk";
    private static final String OFF_THREAD_MARSHAL = "lambda$getChunk$0";
    /** Frames kept per requester thread — enough to reach the mod that asked, not the whole stack. */
    private static final int REQUESTER_FRAMES = 25;

    /** Methods on {@link #SERVER_CLASS} that mean the thread is inside a tick. */
    private static final String[] TICK_METHODS = {"tickServer", "tickChildren"};
    /** The event loop, and the method that runs one queued task between ticks (a chunk load, a save). */
    private static final String EVENT_LOOP_CLASS = "net.minecraft.util.thread.BlockableEventLoop";
    private static final String RUN_TASK_METHOD = "doRunTask";

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
        long lastShortDumpAt = NEVER;
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
                if (now - lastProbeAt < REDUMP_COOLDOWN_MS) continue;
                // Checked before the probe is stamped, so a short stall held back by the budget is
                // looked at again the moment it crosses LONG_STALL_MS rather than ten seconds on.
                if (!mayDump(stalled, lastShortDumpAt, now)) continue;
                lastProbeAt = now;

                Thread serverThread = server.getRunningThread();
                if (serverThread == null) continue;
                StackTraceElement[] stack = serverThread.getStackTrace();
                // Between ticks rather than stuck in one — a pause, not a freeze. Nothing to say.
                if (isIdleBetweenTicks(stack)) continue;
                // Not doing tick work either (the paused integrated server's bookkeeping spin).
                if (!isInsideTick(stack)) continue;

                dumpServerThread(server, serverThread, stack, stalled);
                if (stalled < LONG_STALL_MS) lastShortDumpAt = now;
                reported = true;
            }
        } catch (InterruptedException ignored) {
            // server stopping — exit quietly
        }
    }

    /**
     * Whether a stall this long may be dumped now: never under {@link #STALL_LOG_MS}, always from
     * {@link #LONG_STALL_MS}, and in between only once per {@link #SHORT_DUMP_COOLDOWN_MS}
     * ({@code lastShortDumpAt} is when the last short dump was written, or {@link #NEVER}).
     * Package-private and pure, unit-tested.
     */
    static boolean mayDump(long stalledMs, long lastShortDumpAt, long now) {
        if (stalledMs < STALL_LOG_MS) return false;
        if (stalledMs >= LONG_STALL_MS) return true;
        return lastShortDumpAt == NEVER || now - lastShortDumpAt >= SHORT_DUMP_COOLDOWN_MS;
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
            String method = frame.getMethodName();
            if (INTEGRATED_SERVER_CLASS.equals(frame.getClassName()) && method.contains(PAUSED_METHOD)) return true;
            if (!SERVER_CLASS.equals(frame.getClassName())) continue;
            for (String idle : IDLE_METHODS) {
                if (method.contains(idle)) return true;
            }
        }
        return false;
    }

    /**
     * True when this stack shows the server thread doing work a stall can be made of: inside
     * {@code tickServer}/{@code tickChildren}, running a queued task in
     * {@code BlockableEventLoop.doRunTask}, or servicing an off-thread chunk load — see the class
     * javadoc for the corpus this was validated against. The whole stack is scanned, with method
     * names matched as substrings because mixins rename the frames they wrap. Package-private,
     * Minecraft-free, unit-tested.
     */
    static boolean isInsideTick(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();
            if (SERVER_CLASS.equals(cls)) {
                for (String tick : TICK_METHODS) {
                    if (method.contains(tick)) return true;
                }
            } else if (EVENT_LOOP_CLASS.equals(cls) && method.contains(RUN_TASK_METHOD)) {
                return true;
            }
        }
        return isOffThreadChunkLoad(stack);
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

        if (isOffThreadChunkLoad(stack)) dumpChunkRequesters(serverThread);
    }

    /**
     * True when the server thread is servicing a chunk load that another thread asked for — the
     * {@code lambda$getChunk$0} marshal frame is on its stack. Matched as a substring because
     * mixins wrap and rename frames. Package-private, Minecraft-free — unit-tested.
     */
    static boolean isOffThreadChunkLoad(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            if (CHUNK_CACHE_CLASS.equals(frame.getClassName())
                && frame.getMethodName().contains(OFF_THREAD_MARSHAL)) return true;
        }
        return false;
    }

    /**
     * True when this (non-server) thread is inside {@code ServerChunkCache.getChunk} — i.e. it is
     * the requester blocked on the future the server thread is servicing. Unit-tested.
     */
    static boolean isChunkRequester(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (StackTraceElement frame : stack) {
            if (CHUNK_CACHE_CLASS.equals(frame.getClassName())
                && frame.getMethodName().contains(CHUNK_CACHE_GET)) return true;
        }
        return false;
    }

    /**
     * Dump every other thread blocked in {@code ServerChunkCache.getChunk}: the requesters whose
     * chunk loads the server thread is currently running. Only reached inside a stall that is
     * already being reported, on its cooldown, so the all-threads snapshot is paid rarely.
     */
    private static void dumpChunkRequesters(Thread serverThread) {
        StringBuilder sb = new StringBuilder(2048);
        int requesters = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (thread == serverThread || !isChunkRequester(stack)) continue;
            requesters++;
            sb.append("\n  \"").append(thread.getName()).append("\" state=").append(thread.getState());
            int depth = Math.min(stack.length, REQUESTER_FRAMES);
            for (int i = 0; i < depth; i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            if (stack.length > depth) sb.append("\n    ... ").append(stack.length - depth).append(" more");
        }
        if (requesters == 0) {
            LOGGER.warn("[DT-StallWatchdog] main thread is servicing an off-thread chunk load, but no other thread is inside ServerChunkCache.getChunk right now (requester already released?)");
            return;
        }
        LOGGER.warn("[DT-StallWatchdog] main thread is servicing off-thread chunk loads requested by {} thread(s):{}",
            requesters, sb);
    }
}
