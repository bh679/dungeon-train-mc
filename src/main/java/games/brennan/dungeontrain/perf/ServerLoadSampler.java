package games.brennan.dungeontrain.perf;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.primitives.AABBdc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * The non-physics half of the {@code [mspt]} line: what else the server thread spent a slow window
 * on. A review of 19 player Lag logs (v0.983–0.1001) found 10–40 ms of each tick that
 * {@code physMs=} did not cover and nothing else in the log could attribute — GC pauses on full
 * 4 GB heaps, synchronous chunk loads (7 of 9 stall dumps), entity AI on carriages. Each counter
 * here answers one of those, sampled over the same 40-tick window {@code TrainTickEvents} logs.
 *
 * <p>Cheap by construction: the GC beans are read once per window, the chunk wait is two
 * {@code nanoTime} calls per synchronous chunk miss (fed by {@code ServerChunkCacheWaitTimingMixin}),
 * the entity count is one walk of the level's entities every 40 ticks, tested against the resident
 * carriages' boxes (a dozen or so per train),
 * and the tick max reads 40 slots of the server's own tick-time ring.</p>
 *
 * <p>GC and chunk-wait counters are process-wide and drained by whichever {@code [mspt]} line fires
 * first — the same caveat {@code PhysicsStepTimer} has. Only the train dimension logs in practice.</p>
 */
public final class ServerLoadSampler {

    private static final LongAdder chunkWaitNanos = new LongAdder();
    private static final LongAdder chunkWaits = new LongAdder();

    /** Pause-time collectors, resolved on first use (the set is fixed for the JVM's life). */
    private static volatile List<GarbageCollectorMXBean> pauseCollectors;
    // Cumulative totals at the last drain — only touched from the server thread.
    private static long lastGcMillis = -1;
    private static long lastGcCount = -1;

    private ServerLoadSampler() {}

    /** One {@code [mspt]} window's worth of the counters. */
    public record Window(long gcMillis, long gcCount, long heapUsedMb, long heapMaxMb,
                         long chunkWaitNanos, long chunkWaits, int chunksLoaded, int pendingChunkTasks,
                         int entities, int onCarriages, long tickMaxNanos) {
        public double chunkWaitMs() { return chunkWaitNanos / 1_000_000.0; }
        public double tickMaxMs() { return tickMaxNanos / 1_000_000.0; }
    }

    /** Called by the chunk-cache mixin after each outermost server-thread chunk wait. */
    public static void recordChunkWait(long nanos) {
        if (nanos < 0) return;
        chunkWaitNanos.add(nanos);
        chunkWaits.increment();
    }

    /** Read and reset the windowed counters, and snapshot the gauges — once per {@code [mspt]} line. */
    public static Window drain(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById, int windowTicks) {
        long[] gc = drainGc();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long max = rt.maxMemory() >> 20;

        ServerChunkCache chunks = level.getChunkSource();
        double[] boxes = carriageBoxes(trainsById);
        int entities = 0, onCarriages = 0;
        for (Entity e : level.getAllEntities()) {
            entities++;
            if (!(e instanceof Player) && insideAny(e.getX(), e.getY(), e.getZ(), boxes)) onCarriages++;
        }
        long tickMax = maxTickNanos(level.getServer().getTickTimesNanos(),
            level.getServer().getTickCount(), windowTicks);

        return new Window(gc[0], gc[1], used, max,
            chunkWaitNanos.sumThenReset(), chunkWaits.sumThenReset(),
            chunks.getLoadedChunksCount(), chunks.getPendingTasksCount(),
            entities, onCarriages, tickMax);
    }

    /**
     * Resident carriages' world boxes, flattened to {@code minX,minY,minZ,maxX,maxY,maxZ} runs and
     * inflated like {@code ContentsEntitySnapshot#sweepableMobsAboard} (1 block around, 2 above) —
     * live contents mobs ride in world space once Sable has re-anchored them, not at the shipyard
     * coordinates they were placed at, so the carriage's world box is where they are counted.
     */
    private static double[] carriageBoxes(Map<UUID, List<Trains.Carriage>> trainsById) {
        int n = 0;
        for (List<Trains.Carriage> t : trainsById.values()) n += t.size();
        double[] out = new double[n * 6];
        int i = 0;
        for (List<Trains.Carriage> train : trainsById.values()) {
            for (Trains.Carriage c : train) {
                ManagedShip ship = c.ship();
                if (!ship.isResident()) continue;
                AABBdc b = ship.worldAABB();
                out[i++] = b.minX() - 1; out[i++] = b.minY() - 1; out[i++] = b.minZ() - 1;
                out[i++] = b.maxX() + 1; out[i++] = b.maxY() + 2; out[i++] = b.maxZ() + 1;
            }
        }
        return i == out.length ? out : java.util.Arrays.copyOf(out, i);
    }

    /** Whether a point lies in any of the flattened boxes from {@link #carriageBoxes}. */
    static boolean insideAny(double x, double y, double z, double[] boxes) {
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (x >= boxes[i] && x <= boxes[i + 3]
                && y >= boxes[i + 1] && y <= boxes[i + 4]
                && z >= boxes[i + 2] && z <= boxes[i + 5]) return true;
        }
        return false;
    }

    /** GC pause millis and collections since the last call; the first call reports zero. */
    private static long[] drainGc() {
        List<GarbageCollectorMXBean> beans = pauseCollectors;
        if (beans == null) {
            beans = resolvePauseCollectors();
            pauseCollectors = beans;
        }
        long millis = 0, count = 0;
        for (GarbageCollectorMXBean b : beans) {
            millis += Math.max(0, b.getCollectionTime());
            count += Math.max(0, b.getCollectionCount());
        }
        long dMillis = lastGcMillis < 0 ? 0 : Math.max(0, millis - lastGcMillis);
        long dCount = lastGcCount < 0 ? 0 : Math.max(0, count - lastGcCount);
        lastGcMillis = millis;
        lastGcCount = count;
        return new long[] {dMillis, dCount};
    }

    private static List<GarbageCollectorMXBean> resolvePauseCollectors() {
        List<GarbageCollectorMXBean> all = ManagementFactory.getGarbageCollectorMXBeans();
        List<String> names = new ArrayList<>(all.size());
        for (GarbageCollectorMXBean b : all) names.add(b.getName());
        List<GarbageCollectorMXBean> out = new ArrayList<>();
        for (GarbageCollectorMXBean b : all) {
            if (isPauseCollector(b.getName(), names)) out.add(b);
        }
        return List.copyOf(out);
    }

    /**
     * Whether a collector bean's time is stop-the-world pause time — the part that stalls the tick.
     * ZGC and Shenandoah publish separate "… Pauses" and "… Cycles" beans; when any "Pauses" bean
     * exists, only those count. Otherwise concurrent-cycle beans (JDK 21's "G1 Concurrent GC") are
     * dropped and the rest (G1 Young/Old, Parallel, Serial) are pause collectors.
     */
    static boolean isPauseCollector(String name, List<String> allNames) {
        boolean hasPauseBeans = false;
        for (String n : allNames) {
            if (n.contains("Pauses")) {
                hasPauseBeans = true;
                break;
            }
        }
        if (hasPauseBeans) return name.contains("Pauses");
        return !name.contains("Concurrent") && !name.contains("Cycles");
    }

    /**
     * Longest single tick among the last {@code window} completed ticks of the server's tick-time
     * ring. The ring slot for {@code tickCount} is written at the end of that tick, so from inside a
     * level tick the newest completed one is {@code tickCount - 1}.
     */
    static long maxTickNanos(long[] ring, int tickCount, int window) {
        if (ring == null || ring.length == 0) return 0;
        int n = Math.min(Math.min(window, ring.length), Math.max(0, tickCount));
        long max = 0;
        for (int i = 1; i <= n; i++) {
            max = Math.max(max, ring[Math.floorMod(tickCount - i, ring.length)]);
        }
        return max;
    }
}
