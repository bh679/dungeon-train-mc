package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.SpheresProgressionConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one bounded thread pool the offline samplers share — {@link EndBandSampler} and
 * {@link ForeignSphereSampler} both run full End / Nether decoration here, on top of vanilla's own
 * worldgen threads.
 *
 * <p>Each sampler used to start its own threads (2 each by default), so four busy threads competed with the
 * live chunk generation the train waits on — on a 2–4 core server, badly. Now there is one pool, sized by
 * {@code worldgenSamplerThreads}; auto ({@link SpheresProgressionConfig#AUTO_SAMPLER_THREADS}) takes a
 * quarter of the cores ({@link #resolveThreads}): vanilla already keeps about {@code cores − 1} worldgen
 * threads plus the server thread busy, so a quarter caps the extra load while still giving big machines
 * the old total of four.</p>
 *
 * <p>Dedicated daemon threads ({@code DungeonTrain-sampler-N}), never {@code Util.backgroundExecutor()}:
 * {@code fillFromNoise} schedules onto that pool and joins, and a job that joined from inside it would
 * starve it (the chunk-dimension portal room's lesson).</p>
 */
public final class SamplerPool {

    /** Most threads auto sizing picks — the old two samplers' combined default. */
    static final int AUTO_MAX_THREADS = 4;

    private static volatile ThreadPoolExecutor executor;

    private SamplerPool() {}

    /** Run {@code task} on a sampler thread. Never blocks; the pool is started on first use. */
    public static void execute(Runnable task) {
        executor().execute(task);
    }

    /**
     * The pool size for a configured value: {@code configured} itself if positive, else a quarter of
     * {@code cores}, at least 1 and at most {@link #AUTO_MAX_THREADS}.
     */
    static int resolveThreads(int configured, int cores) {
        if (configured > 0) return configured;
        return Math.max(1, Math.min(AUTO_MAX_THREADS, cores / 4));
    }

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor e = executor;
        if (e != null) return e;
        synchronized (SamplerPool.class) {
            if (executor == null) {
                int threads = resolveThreads(SpheresProgressionConfig.samplerThreads(),
                        Runtime.getRuntime().availableProcessors());
                AtomicInteger n = new AtomicInteger();
                executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(), task -> {
                            Thread thread = new Thread(task, "DungeonTrain-sampler-" + n.incrementAndGet());
                            thread.setDaemon(true);
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            return thread;
                        });
            }
            return executor;
        }
    }
}
