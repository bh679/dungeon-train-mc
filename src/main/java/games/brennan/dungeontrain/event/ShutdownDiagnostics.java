package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Observational diagnostics for the world-exit "Saving world…" hang reported
 * by the user. Purely read-only: never mutates server state.
 *
 * <p>Hypothesis under test: after {@link ShipShutdownEvents}' fixed 1 s
 * per-dim drain returns on {@link ServerStoppingEvent}, vanilla's
 * {@code MinecraftServer.stopServer()} loops on each {@code ChunkMap.hasWork()}
 * and that flag stays {@code true} because of upstream Sable issue #679 —
 * leaving the "Saving world…" screen hung until the JVM is killed.
 *
 * <p>This class doesn't fix it. It captures direct evidence:
 * <ol>
 *   <li>Total wall-clock time between {@link ServerStoppingEvent} and
 *       {@link ServerStoppedEvent} — the headline duration of the hang.</li>
 *   <li>{@code ChunkMap.hasWork()} per {@link ServerLevel}, immediately
 *       after our drain and at periodic checkpoints, via reflection.</li>
 *   <li>{@code ChunkMap.pendingUnloads} and {@code ChunkMap.updatingChunkMap}
 *       sizes — tells us which queue is failing to drain.</li>
 *   <li>Stack trace of the {@code Server thread} at each checkpoint — pinpoints
 *       exactly where vanilla is parked.</li>
 * </ol>
 *
 * <p>Implementation: one daemon thread named {@code DT-Shutdown-Watchdog}
 * polls every {@value #POLL_INTERVAL_MS} ms for up to {@value #WATCHDOG_MAX_MS}
 * ms, logging state transitions and dumping snapshots at the
 * {@link #CHECKPOINTS_MS} thresholds. Exits cleanly when
 * {@link ServerStoppedEvent} fires.
 *
 * <p>Reflection lookups are lazy and wrapped in try/catch — if vanilla
 * mappings shift, the diag degrades to {@code "diag unavailable"} log lines
 * rather than throwing. All log entries are prefixed {@value #TAG} so they're
 * grep-friendly in {@code run/logs/latest.log}.
 *
 * <p>Common-scope (NOT {@code Dist}-gated) — the hang affects integrated and
 * dedicated servers alike.
 *
 * <p>See {@link ShipShutdownEvents} for the upstream Sable bug context and
 * the existing drain that this diagnostic observes from the outside.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ShutdownDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[DT-ShutdownDiag]";

    private static final long WATCHDOG_MAX_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long[] CHECKPOINTS_MS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L};

    private static volatile boolean shutdownComplete = false;
    private static volatile long shutdownStartNanos = 0L;

    private static Method chunkMapHasWork;
    private static Field chunkMapPendingUnloads;
    private static Field chunkMapUpdatingChunkMap;
    private static boolean reflectionInited = false;

    private ShutdownDiagnostics() {}

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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        initReflection();
        shutdownComplete = false;
        shutdownStartNanos = System.nanoTime();
        MinecraftServer server = event.getServer();

        LOGGER.info("{} ServerStopping fired (after ShipShutdownEvents drain) — snapshotting post-drain state", TAG);
        List<LevelHandle> handles = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            LevelHandle h = LevelHandle.of(level);
            handles.add(h);
            snapshot(h, 0L);
        }

        Thread watchdog = new Thread(() -> watchdogLoop(handles), "DT-Shutdown-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        shutdownComplete = true;
        long elapsedMs = (System.nanoTime() - shutdownStartNanos) / 1_000_000L;
        LOGGER.info("{} ServerStopped — total elapsed since ServerStopping: {} ms", TAG, elapsedMs);
    }

    private static void watchdogLoop(List<LevelHandle> handles) {
        try {
            boolean[] lastHasWork = new boolean[handles.size()];
            for (int i = 0; i < handles.size(); i++) {
                lastHasWork[i] = safeHasWork(handles.get(i));
            }

            long startNs = System.nanoTime();
            int checkpointIdx = 0;
            while (!shutdownComplete) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                if (elapsedMs >= WATCHDOG_MAX_MS) {
                    LOGGER.warn("{} watchdog hit {} ms cap — likely true hang. Final snapshot:", TAG, WATCHDOG_MAX_MS);
                    snapshotAll(handles, elapsedMs);
                    dumpServerThread();
                    return;
                }

                for (int i = 0; i < handles.size(); i++) {
                    boolean now = safeHasWork(handles.get(i));
                    if (now != lastHasWork[i]) {
                        LOGGER.info("{} hasWork {}->{} at T+{} ms (level={})",
                            TAG, lastHasWork[i], now, elapsedMs, handles.get(i).dim);
                        lastHasWork[i] = now;
                    }
                }

                while (checkpointIdx < CHECKPOINTS_MS.length
                       && elapsedMs >= CHECKPOINTS_MS[checkpointIdx]) {
                    boolean anyWork = false;
                    for (boolean w : lastHasWork) {
                        if (w) { anyWork = true; break; }
                    }
                    if (anyWork) {
                        LOGGER.warn("{} CHECKPOINT T+{} ms — still has work, dumping state", TAG, CHECKPOINTS_MS[checkpointIdx]);
                        snapshotAll(handles, elapsedMs);
                        dumpServerThread();
                    }
                    checkpointIdx++;
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            long finalMs = (System.nanoTime() - startNs) / 1_000_000L;
            LOGGER.info("{} watchdog exiting cleanly at T+{} ms (ServerStoppedEvent fired)", TAG, finalMs);
        } catch (Throwable t) {
            LOGGER.warn("{} watchdog crashed (non-fatal — diag only)", TAG, t);
        }
    }

    private static void snapshotAll(List<LevelHandle> handles, long elapsedMs) {
        for (LevelHandle h : handles) snapshot(h, elapsedMs);
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

    private static void dumpServerThread() {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (!"Server thread".equals(t.getName())) continue;
                StringBuilder sb = new StringBuilder();
                sb.append(TAG).append(" Server thread state=").append(t.getState()).append('\n');
                for (StackTraceElement frame : e.getValue()) {
                    sb.append("    at ").append(frame).append('\n');
                }
                LOGGER.warn(sb.toString());
                return;
            }
            LOGGER.warn("{} Server thread not found in current thread set", TAG);
        } catch (Throwable t) {
            LOGGER.warn("{} stack dump failed (non-fatal)", TAG, t);
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
