package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts cold lookups of Big Lost City templates by the kind of thread that made them. A cold lookup is a
 * {@code StructureTemplateManager#get} that found the template missing from the cache — the caller then either
 * loads and datafixes it (~0.5 s) or waits for the thread that is. On the pre-load thread that is the plan;
 * anywhere else it is a stall, and this is the number that says whether the pre-load is doing its job
 * ({@code /dungeontrain debug lost-city-templates}). Fed by {@code mixin/StructureTemplateManagerLoadMixin}.
 */
public final class LostCityTemplateLoads {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Name of {@code LostCityTemplatePreloadEvents}' background thread. */
    public static final String PRELOAD_THREAD_NAME = "DungeonTrain-lostcity-preload";

    /** Who made a cold lookup. */
    public enum ThreadKind {
        /** The background pre-load — the intended place. */
        PRELOAD,
        /** A vanilla worldgen worker: chunk generation waited. */
        WORLDGEN,
        /** A Distant Horizons LOD generator worker: an LOD waited, the server did not. */
        DISTANT_HORIZONS,
        /** The server thread: the tick waited. */
        SERVER,
        OTHER
    }

    /** Counts and total nanoseconds per {@link ThreadKind}, indexed by ordinal. */
    public record Snapshot(long[] counts, long[] nanos) {

        public long count(ThreadKind kind) {
            return counts[kind.ordinal()];
        }

        public long millis(ThreadKind kind) {
            return nanos[kind.ordinal()] / 1_000_000L;
        }

        /** Cold lookups made anywhere but the pre-load thread. */
        public long offThreadCount() {
            long n = 0;
            for (ThreadKind kind : ThreadKind.values()) if (kind != ThreadKind.PRELOAD) n += count(kind);
            return n;
        }
    }

    private static final int KINDS = ThreadKind.values().length;
    private static final AtomicLongArray COUNTS = new AtomicLongArray(KINDS);
    private static final AtomicLongArray NANOS = new AtomicLongArray(KINDS);

    private LostCityTemplateLoads() {}

    /** The kind of a thread named {@code threadName}. */
    public static ThreadKind kindOf(String threadName) {
        if (threadName == null) return ThreadKind.OTHER;
        if (threadName.startsWith(PRELOAD_THREAD_NAME)) return ThreadKind.PRELOAD;
        if (LodGeneration.isLodThreadName(threadName)) return ThreadKind.DISTANT_HORIZONS;
        if (threadName.equals("Server thread")) return ThreadKind.SERVER;
        if (threadName.startsWith("Worker-")) return ThreadKind.WORLDGEN;
        return ThreadKind.OTHER;
    }

    /** A cold lookup of {@code id} on the current thread took {@code nanos}. */
    public static void record(ResourceLocation id, long nanos) {
        String thread = Thread.currentThread().getName();
        ThreadKind kind = kindOf(thread);
        COUNTS.incrementAndGet(kind.ordinal());
        NANOS.addAndGet(kind.ordinal(), nanos);
        if (kind != ThreadKind.PRELOAD) {
            // at most one per template per eviction cycle, so the line is no log cost
            LOGGER.debug("[DungeonTrain] Lost City template cold lookup off the pre-load thread: {} took {} ms on {} ({})",
                    id, nanos / 1_000_000L, thread, kind);
        }
    }

    public static Snapshot snapshot() {
        long[] counts = new long[KINDS];
        long[] nanos = new long[KINDS];
        for (int i = 0; i < KINDS; i++) {
            counts[i] = COUNTS.get(i);
            nanos[i] = NANOS.get(i);
        }
        return new Snapshot(counts, nanos);
    }

    public static void reset() {
        for (int i = 0; i < KINDS; i++) {
            COUNTS.set(i, 0L);
            NANOS.set(i, 0L);
        }
    }
}
