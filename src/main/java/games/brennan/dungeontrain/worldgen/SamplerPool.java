package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

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
 * <p>Dedicated daemon threads ({@code DungeonTrain-sampler-g<world>-<n>}), never
 * {@code Util.backgroundExecutor()}: {@code fillFromNoise} schedules onto that pool and joins, and a job
 * that joined from inside it would starve it (the chunk-dimension portal room's lesson).</p>
 *
 * <p><b>One queue.</b> Both samplers' jobs wait in a single {@link EndBandJobQueue}, taken nearest player
 * first. The executor's own queue holds only wake-up tokens: a worker drains the job queue until it is
 * empty, so a token that finds the token queue full can be thrown away — the ones already waiting will take
 * the job.</p>
 *
 * <p><b>Bounded.</b> At most {@link #DEFAULT_MAX_QUEUED} jobs wait. A full queue evicts its furthest
 * <em>droppable</em> job — one whose chunk will ask again (End-band samples: the pending sweep or the
 * chunk's next load) — and otherwise refuses the newcomer ({@link #submit} returns {@code false}); it never
 * drops a job nothing would re-request.</p>
 *
 * <p><b>Per world.</b> {@link #close} on server stop drops every waiting job and shuts the threads down;
 * the next world's first job starts new ones, re-reading the configured size. A job already running at stop
 * finishes on the old threads ({@link #awaitQuiescent}); the samplers' epoch checks discard its result.</p>
 */
public final class SamplerPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Most threads auto sizing picks — the old two samplers' combined default. */
    static final int AUTO_MAX_THREADS = 4;
    /** Most jobs that wait at once: a few players' worth of sphere chunks arriving together. */
    static final int DEFAULT_MAX_QUEUED = 4096;
    /** JVM property overriding {@link #DEFAULT_MAX_QUEUED}, for exercising the full-queue path in a dev run. */
    static final String MAX_QUEUED_PROPERTY = "dungeontrain.samplerQueueCap";

    /** Which sampler a job belongs to. */
    public enum Kind { END_BAND, SPHERE }

    /** Queue depth and what the cap has cost since the pool was last opened. */
    public record Stats(int queued, int peakQueued, long evicted, long refused) {}

    private record Key(Kind kind, long id) {}

    private record Job(Kind kind, int cx, int cz, Runnable work, Runnable onDrop) {}

    private static final SamplerPool SHARED = new SamplerPool(SpheresProgressionConfig::samplerThreads,
            () -> Runtime.getRuntime().availableProcessors(),
            Integer.getInteger(MAX_QUEUED_PROPERTY, DEFAULT_MAX_QUEUED));

    private final IntSupplier configuredThreads;
    private final IntSupplier cores;
    private final EndBandJobQueue<Job> queue;
    /** Guards {@link #open}, {@link #executor} and {@link #retired}, and keeps a submit from landing behind a close. */
    private final Object lifecycle = new Object();
    private final AtomicInteger peakQueued = new AtomicInteger();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    private boolean open = true;
    private ThreadPoolExecutor executor;
    /** The last closed world's threads, until {@link #awaitQuiescent} has seen them finish. */
    private ThreadPoolExecutor retired;
    private int generation;
    private volatile int keepRadius = Integer.MAX_VALUE;

    SamplerPool(IntSupplier configuredThreads, IntSupplier cores, int maxQueued) {
        this.configuredThreads = configuredThreads;
        this.cores = cores;
        this.queue = new EndBandJobQueue<>(maxQueued);
    }

    /** The pool both samplers run on. */
    public static SamplerPool shared() {
        return SHARED;
    }

    /**
     * The pool size for a configured value: {@code configured} itself if positive, else a quarter of
     * {@code cores}, at least 1 and at most {@link #AUTO_MAX_THREADS}.
     */
    static int resolveThreads(int configured, int cores) {
        if (configured > 0) return configured;
        return Math.max(1, Math.min(AUTO_MAX_THREADS, cores / 4));
    }

    /**
     * Queue {@code work} for chunk {@code (cx, cz)} under {@code (kind, id)}. Never blocks; the threads are
     * started on the first job after {@link #open}. {@code droppable} says the chunk will ask again if the
     * job is thrown away. {@code onDrop} runs if the job, once queued, is removed without running (evicted,
     * out of range, {@link #forget}, {@link #cancel}, {@link #close}).
     *
     * @return {@code false} if the job was not queued — the pool is closed, or the queue is full and nothing
     *         may give way; {@code onDrop} is <em>not</em> run, the caller releases what it holds
     */
    public boolean submit(Kind kind, long id, int cx, int cz, boolean droppable, Runnable work, Runnable onDrop) {
        synchronized (lifecycle) {
            if (!open) return false;
            Job job = new Job(kind, cx, cz, work, onDrop);
            if (!queue.offer(new Key(kind, id), cx, cz, job, droppable, this::evict)) {
                refused.incrementAndGet();
                return false;
            }
            peakQueued.accumulateAndGet(queue.size(), Math::max);
            ThreadPoolExecutor e = executor;
            if (e == null) e = executor = start();
            ThreadPoolExecutor self = e;
            e.execute(() -> drain(self));
            return true;
        }
    }

    /**
     * The players the queue is ordered by, and how far (in chunks) from the nearest of them a waiting
     * droppable job may be before it is dropped. Server thread, every tick.
     */
    public void updatePlayers(EndBandJobQueue.Players players, int dropBeyondChunks) {
        keepRadius = dropBeyondChunks;
        queue.setPlayers(players);
    }

    /** Drop {@code kind}'s waiting jobs for chunk {@code (cx, cz)} — it unloaded, and asks again when it loads. */
    public void forget(Kind kind, int cx, int cz) {
        if (queue.size() == 0) return;
        queue.removeIf(job -> job.kind() == kind && job.cx() == cx && job.cz() == cz, SamplerPool::dropped);
    }

    /** Drop every waiting job of {@code kind}. */
    public void cancel(Kind kind) {
        queue.removeIf(job -> job.kind() == kind, SamplerPool::dropped);
    }

    /** True while another job can be queued without the cap coming into play. */
    public boolean hasRoom() {
        return queue.size() < queue.capacity();
    }

    public Stats stats() {
        return new Stats(queue.size(), peakQueued.get(), evicted.get(), refused.get());
    }

    /** The current world's sampler threads; {@code 0} until its first job. */
    int threads() {
        synchronized (lifecycle) {
            return executor == null ? 0 : executor.getCorePoolSize();
        }
    }

    /** Accept jobs again — a server is about to start. The threads still wait for the first job. */
    public void open() {
        synchronized (lifecycle) {
            open = true;
            peakQueued.set(0);
            evicted.set(0);
            refused.set(0);
        }
    }

    /**
     * The server is stopping: refuse new jobs, drop every waiting one and let the threads end. A job
     * already running finishes ({@link #awaitQuiescent}) — its noise fill can't be interrupted, so
     * interrupting would only turn it into a logged failure. Returns how many waiting jobs were dropped.
     */
    public int close() {
        int[] waiting = {0};
        Stats before;
        synchronized (lifecycle) {
            open = false;
            before = stats();
            queue.clear(job -> {
                waiting[0]++;
                dropped(job);
            });
            queue.setPlayers(EndBandJobQueue.Players.NONE);
            keepRadius = Integer.MAX_VALUE;
            if (executor != null) {
                executor.shutdown();
                retired = executor;
                executor = null;
            }
        }
        LOGGER.debug("[DungeonTrain] Sampler pool closed: {} waiting job(s) dropped, peak queue {}, {} evicted, {} refused",
                waiting[0], before.peakQueued(), before.evicted(), before.refused());
        return waiting[0];
    }

    /**
     * Wait up to {@code timeoutMillis} for the closed world's threads to finish the jobs they were running.
     * True once none is left (or nothing was closed); false if one is still going.
     */
    public boolean awaitQuiescent(long timeoutMillis) {
        ThreadPoolExecutor old;
        synchronized (lifecycle) {
            old = retired;
        }
        if (old == null) return true;
        try {
            if (!old.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (lifecycle) {
            if (retired == old) retired = null;
        }
        return true;
    }

    private ThreadPoolExecutor start() {
        int threads = resolveThreads(configuredThreads.getAsInt(), cores.getAsInt());
        int world = ++generation;
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads), task -> {
                    Thread thread = new Thread(task, "DungeonTrain-sampler-g" + world + "-" + n.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    /** One token's work: run waiting jobs, nearest a player first, until none is left or this world's pool has closed. Sampler thread. */
    private void drain(ThreadPoolExecutor self) {
        while (!self.isShutdown()) {
            Job job = queue.poll(keepRadius, SamplerPool::dropped);
            if (job == null) return;
            try {
                job.work().run();
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Sampler job threw", t);
            }
        }
    }

    private void evict(Job job) {
        evicted.incrementAndGet();
        dropped(job);
    }

    private static void dropped(Job job) {
        try {
            job.onDrop().run();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Sampler job's drop hook threw", t);
        }
    }
}
