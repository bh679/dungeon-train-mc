package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.ShutdownTrace;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Watches a server shutdown from its first step to its last, so a freeze on Save &amp; Quit says where
 * it is stuck. Purely observational: never mutates server state.
 *
 * <p><b>Phases.</b> A shutdown runs NeoForge's {@link ServerStoppingEvent} listeners (DT's
 * {@link ShipShutdownEvents} drain among them), then vanilla's {@code stopServer()} saves players and
 * chunks, unloads each level ({@link LevelEvent.Unload}), and finally fires {@link ServerStoppedEvent}.
 * This class marks each boundary: {@link Phase#LISTENERS} from the HIGHEST-priority stopping
 * listener, {@link Phase#VANILLA_SAVE} from the LOWEST, {@link Phase#CLOSING_LEVELS} at the first
 * level unload, and done at stopped. It arms at HIGHEST on purpose: until 2026-11 it armed at LOWEST
 * and so never saw a freeze inside an earlier listener (the 2026-11-24 report froze right there).</p>
 *
 * <p><b>Watchdog.</b> One daemon thread, {@code DT-Shutdown-Watchdog}, polls every
 * {@value #POLL_INTERVAL_MS} ms. At each checkpoint ({@link ShutdownTrace#checkpointMs}: 5 s, 15 s,
 * 30 s, 60 s, then every minute to 15 min) it logs the phase, the Server thread's stack, every thread
 * caught in file I/O or a log4j appender (so a stalled disk reads as one), and the chunk-map state of
 * each level. A clean shutdown takes a few seconds and so logs no dump.</p>
 *
 * <p><b>Breadcrumb.</b> The watchdog also writes {@link ShutdownTrace}'s file outside log4j, which a
 * stalled disk can block, on each phase change and each checkpoint; {@link ServerStoppedEvent}
 * deletes it. The next launch reports a leftover one once ({@link #reportUnfinishedPreviousShutdown}).</p>
 *
 * <p><b>Chunk map.</b> {@code ChunkMap.hasWork()}, {@code pendingUnloads} and {@code updatingChunkMap}
 * per level, by reflection, as the original diagnostic for the Sable #679 hang (see
 * {@link ShipShutdownEvents}). Reflection is lazy and degrades to {@code -1} values.</p>
 *
 * <p>All log lines start {@value #TAG}. Common-scope: integrated and dedicated servers alike.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ShutdownDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[DT-ShutdownDiag]";

    private static final long POLL_INTERVAL_MS = 250L;
    /** Frames of the Server thread kept in the breadcrumb; the log gets the whole stack. */
    private static final int TRACE_FRAMES = 40;
    /** Frames shown for each thread caught in I/O. */
    private static final int IO_FRAMES = 12;
    /** How deep a frame may sit and still count as the thread being "in" I/O. */
    private static final int IO_SCAN_DEPTH = 4;
    /** Dev builds only: a stopping listener sleeps this long, to reproduce a hang. */
    private static final String DEV_HANG_PROPERTY = "dungeontrain.debug.shutdownHangMs";

    /** Where the shutdown is. The order is the order a shutdown moves through them. */
    enum Phase {
        LISTENERS("stopping listeners (mods, DT train cleanup)"),
        VANILLA_SAVE("vanilla saving players and chunks"),
        CLOSING_LEVELS("vanilla closing levels"),
        STOPPED("stopped");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private static volatile Phase phase = Phase.STOPPED;
    /** Bumped on every arm, so a watchdog from an earlier shutdown stops when the next one starts. */
    private static volatile int generation = 0;
    private static volatile long shutdownStartNanos = 0L;
    private static volatile String startedAt = "";

    private static Method chunkMapHasWork;
    private static Field chunkMapPendingUnloads;
    private static Field chunkMapUpdatingChunkMap;
    private static boolean reflectionInited = false;

    private ShutdownDiagnostics() {}

    // ---- phase markers ------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStoppingFirst(ServerStoppingEvent event) {
        initReflection();
        MinecraftServer server = event.getServer();
        List<LevelHandle> handles = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) handles.add(LevelHandle.of(level));

        int gen = ++generation;
        shutdownStartNanos = System.nanoTime();
        startedAt = Instant.now().toString();
        phase = Phase.LISTENERS;
        LOGGER.info("{} shutdown watch armed: {}", TAG, Phase.LISTENERS.label);

        Thread watchdog = new Thread(() -> watchdogLoop(gen, handles), "DT-Shutdown-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Dev builds only: sleep inside a stopping listener so Gate 2 can watch a hang get dumped. */
    @SubscribeEvent
    public static void onServerStoppingDevHang(ServerStoppingEvent event) {
        long hangMs = Long.getLong(DEV_HANG_PROPERTY, 0L);
        if (hangMs <= 0L || !DungeonTrain.isDevBuild()) return;
        LOGGER.warn("{} dev hang: sleeping {} ms in a stopping listener ({})", TAG, hangMs, DEV_HANG_PROPERTY);
        try {
            Thread.sleep(hangMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStoppingLast(ServerStoppingEvent event) {
        phase = Phase.VANILLA_SAVE;
        LOGGER.info("{} stopping listeners done in {} ms; next: {}", TAG, elapsedMs(), Phase.VANILLA_SAVE.label);
        for (ServerLevel level : event.getServer().getAllLevels()) {
            snapshot(LevelHandle.of(level), elapsedMs());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (phase == Phase.VANILLA_SAVE && event.getLevel() instanceof ServerLevel) {
            phase = Phase.CLOSING_LEVELS;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        phase = Phase.STOPPED;
        // On this thread, not the watchdog's: the JVM may exit right after, before a daemon runs again,
        // and a trace left behind would be reported as an unfinished shutdown on the next launch.
        ShutdownTrace.clear(dataRoot());
        LOGGER.info("{} ServerStopped, total elapsed since ServerStopping: {} ms", TAG, elapsedMs());
    }

    // ---- next launch ---------------------------------------------------------------------------

    /**
     * Log, once, a shutdown that never finished last time: its last phase and the stacks its watchdog
     * caught. Called from common setup; goes to the log (and so to a {@code /bug} report), never to
     * the player.
     */
    public static void reportUnfinishedPreviousShutdown() {
        try {
            ShutdownTrace.takePrevious(dataRoot()).ifPresent(previous -> LOGGER.warn(
                "{} previous shutdown did not finish: last phase '{}', {} ms in. Its trace (kept at {}):\n{}",
                TAG, previous.phase(), previous.elapsedMs(), ShutdownTrace.PREVIOUS_FILE_NAME, previous.text()));
        } catch (Throwable t) {
            LOGGER.warn("{} could not check for an unfinished previous shutdown: {}", TAG, t.toString());
        }
    }

    // ---- watchdog ------------------------------------------------------------------------------

    private static void watchdogLoop(int gen, List<LevelHandle> handles) {
        try {
            Path root = dataRoot();
            Phase written = null;
            boolean[] lastHasWork = new boolean[handles.size()];
            for (int i = 0; i < handles.size(); i++) lastHasWork[i] = safeHasWork(handles.get(i));
            int checkpointIdx = 0;

            while (generation == gen && phase != Phase.STOPPED) {
                long elapsed = elapsedMs();
                Phase now = phase;
                if (now != written) {
                    ShutdownTrace.write(root, ShutdownTrace.render(startedAt, now.label, elapsed, ""));
                    written = now;
                }

                for (int i = 0; i < handles.size(); i++) {
                    boolean w = safeHasWork(handles.get(i));
                    if (w != lastHasWork[i]) {
                        LOGGER.info("{} hasWork {}->{} at T+{} ms (level={})", TAG, lastHasWork[i], w, elapsed,
                            handles.get(i).dim);
                        lastHasWork[i] = w;
                    }
                }

                long due = ShutdownTrace.checkpointMs(checkpointIdx);
                if (due < 0L) {
                    LOGGER.warn("{} watchdog stopping after {} ms, still in phase: {}", TAG,
                        ShutdownTrace.WATCHDOG_MAX_MS, now.label);
                    return;
                }
                if (elapsed >= due) {
                    checkpoint(root, handles, now, elapsed);
                    checkpointIdx++;
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("{} watchdog crashed (non-fatal, diag only)", TAG, t);
        }
    }

    /** One checkpoint: the breadcrumb first (it doesn't go through log4j), then the log. */
    private static void checkpoint(Path root, List<LevelHandle> handles, Phase now, long elapsed) {
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        String server = serverThreadStack(stacks, Integer.MAX_VALUE);
        String io = ioThreads(stacks);
        ShutdownTrace.write(root, ShutdownTrace.render(startedAt, now.label, elapsed,
            serverThreadStack(stacks, TRACE_FRAMES) + io));
        LOGGER.warn("{} T+{} ms, still stopping, in phase: {}\n{}{}", TAG, elapsed, now.label, server,
            io.isEmpty() ? "No thread is in file I/O.\n" : io);
        for (LevelHandle h : handles) snapshot(h, elapsed);
    }

    private static String serverThreadStack(Map<Thread, StackTraceElement[]> stacks, int maxFrames) {
        for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
            if ("Server thread".equals(e.getKey().getName())) {
                return describe(e.getKey(), e.getValue(), maxFrames);
            }
        }
        return "Server thread not found\n";
    }

    /** Every thread whose top frames are in file I/O or a log4j appender: the mark of a stalled disk. */
    private static String ioThreads(Map<Thread, StackTraceElement[]> stacks) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
            if ("Server thread".equals(e.getKey().getName()) || !inIo(e.getValue())) continue;
            if (sb.isEmpty()) sb.append("Threads in file I/O:\n");
            sb.append(describe(e.getKey(), e.getValue(), IO_FRAMES));
        }
        return sb.toString();
    }

    static boolean inIo(StackTraceElement[] stack) {
        if (stack == null) return false;
        for (int i = 0; i < Math.min(stack.length, IO_SCAN_DEPTH); i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("sun.nio.ch.FileDispatcherImpl")
                || cls.startsWith("sun.nio.ch.FileChannelImpl")
                || cls.equals("java.io.RandomAccessFile")
                || cls.equals("java.io.FileOutputStream")
                || cls.startsWith("org.apache.logging.log4j.core.appender")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Thread t, StackTraceElement[] stack, int maxFrames) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(t.getName()).append("\" state=").append(t.getState()).append('\n');
        int n = Math.min(stack.length, maxFrames);
        for (int i = 0; i < n; i++) sb.append("    at ").append(stack[i]).append('\n');
        if (stack.length > n) sb.append("    ... ").append(stack.length - n).append(" more\n");
        return sb.toString();
    }

    private static long elapsedMs() {
        return (System.nanoTime() - shutdownStartNanos) / 1_000_000L;
    }

    private static Path dataRoot() {
        return PlayerDataPaths.root();
    }

    // ---- chunk map ------------------------------------------------------------------------------

    private static synchronized void initReflection() {
        if (reflectionInited) return;
        reflectionInited = true;
        StringBuilder errors = new StringBuilder();
        try {
            chunkMapHasWork = ChunkMap.class.getDeclaredMethod("hasWork");
            chunkMapHasWork.setAccessible(true);
        } catch (Throwable t) {
            errors.append("hasWork: ").append(t).append("; ");
        }
        try {
            chunkMapPendingUnloads = ChunkMap.class.getDeclaredField("pendingUnloads");
            chunkMapPendingUnloads.setAccessible(true);
        } catch (Throwable t) {
            errors.append("pendingUnloads: ").append(t).append("; ");
        }
        try {
            chunkMapUpdatingChunkMap = ChunkMap.class.getDeclaredField("updatingChunkMap");
            chunkMapUpdatingChunkMap.setAccessible(true);
        } catch (Throwable t) {
            errors.append("updatingChunkMap: ").append(t).append("; ");
        }
        if (errors.length() > 0) {
            LOGGER.warn("{} diag partially unavailable: {}", TAG, errors);
        }
    }

    private static void snapshot(LevelHandle h, long elapsedMs) {
        boolean hw = safeHasWork(h);
        int pending = safeMapSize(chunkMapPendingUnloads, h.chunkMap);
        int updating = safeMapSize(chunkMapUpdatingChunkMap, h.chunkMap);
        LOGGER.info("{} T+{} ms level={} hasWork={} pendingUnloads={} updatingChunkMap={}",
            TAG, elapsedMs, h.dim, hw, pending, updating);
    }

    private static boolean safeHasWork(LevelHandle h) {
        if (chunkMapHasWork == null || h.chunkMap == null) return false;
        try {
            Object result = chunkMapHasWork.invoke(h.chunkMap);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int safeMapSize(Field field, ChunkMap chunkMap) {
        if (field == null || chunkMap == null) return -1;
        try {
            Object obj = field.get(chunkMap);
            if (obj == null) return -1;
            if (obj instanceof Map<?, ?> m) return m.size();
            Method sizeMethod = obj.getClass().getMethod("size");
            Object size = sizeMethod.invoke(obj);
            return size instanceof Integer n ? n : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static final class LevelHandle {
        final String dim;
        final ChunkMap chunkMap;

        private LevelHandle(String dim, ChunkMap chunkMap) {
            this.dim = dim;
            this.chunkMap = chunkMap;
        }

        static LevelHandle of(ServerLevel level) {
            String dim = level.dimension().location().toString();
            ChunkMap cm = null;
            try {
                ServerChunkCache cache = level.getChunkSource();
                cm = cache.chunkMap;
            } catch (Throwable t) {
                LOGGER.warn("{} could not resolve ChunkMap for level {}", TAG, dim, t);
            }
            return new LevelHandle(dim, cm);
        }
    }
}
