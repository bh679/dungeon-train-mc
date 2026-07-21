package games.brennan.dungeontrain.worldgen;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-window attribution of DT-owned <b>chunk-generation</b> cost, surfaced as the
 * {@code [gen.timing]} DEBUG line alongside {@code [mspt]} (see
 * {@code games.brennan.dungeontrain.event.TrainTickEvents}).
 *
 * <p>The moving-train server tick is dominated by chunk generation, which runs on worldgen
 * <b>worker threads</b> and is therefore invisible to the main-thread {@code [stuck.timing]}
 * handler breakdown. This profiler buckets the DT-owned slices of that worker-thread cost
 * (density-function raise, nether-transition feature, real-Nether core replace, biome forcing,
 * upside-down mirror precompute, track-bed feature) so the biggest DT contributor can be read
 * straight from the log on a real forward ride — no {@code /spark} or JFR required.</p>
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li>Each timed gen hook wraps its work in one {@code nanoTime} pair per <em>call</em> (never
 *       per block / per density sample) via {@link #t0()} + {@link #add(Bucket, long)}, adding the
 *       elapsed nanos into a per-bucket {@link LongAdder} — the right structure for many concurrent
 *       worker writers and a rare single-threaded read.</li>
 *   <li>{@link #countChunk()} tallies newly-<em>generated</em> chunks reaching {@code FULL}
 *       (fired from a main-thread {@code ChunkEvent.Load} + {@code isNewChunk()} filter).</li>
 *   <li>The {@code [mspt]} logger drains every 40 ticks via {@link #sampleAndReset()} and logs the
 *       per-bucket window totals plus a <b>per-chunk</b> normalisation — the load-bearing number,
 *       independent of worker-thread count and window boundaries.</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * All accumulation is gated on {@link #enabled()} (default OFF; the mod constructor turns it ON for
 * dev builds, and {@code /dungeontrain debug gen-timing on|off} toggles it at runtime). This keeps
 * production at zero cost: the {@code BIOME_FORCE} timer in particular sits on a per-quart path
 * (~1.5k calls/chunk), fine as a diagnostic but not something to pay for always-on. When disabled,
 * {@link #t0()} returns {@code 0} so {@link #add(Bucket, long)} is a no-op — the only residual cost
 * is a predictable-branch volatile read.
 *
 * <h2>Reading the numbers (pitfalls)</h2>
 * The window ms are a <b>parallel sum across worker threads</b>, so {@code dtGenTotalMs} over a 2 s
 * window can exceed 2000 ms and must never be compared to the main-thread {@code avgTickMs}. Trust the
 * <b>per-chunk</b> figures, and read several consecutive windows (a chunk's FULL promotion can lag its
 * worker-thread gen by a window in bursty streaming). Pure diagnostic — never gates gameplay.
 */
public final class GenProfiler {

    /** DT-owned gen cost buckets. {@link #CORE_REPLACE} is a sub-portion of {@link #NETHER_FEATURE}
     *  (reported informationally, not double-counted in the DT total). */
    public enum Bucket {
        /** {@code NetherBandTerrainDensityFunction} raise loop — DT's per-sample density tax. */
        DF,
        /** {@code NetherTransitionFeature.place} — crossfade + core replace + corridor clearance. */
        NETHER_FEATURE,
        /** {@code NetherTransitionFeature.fillNetherColumn} — real-Nether router sampling (sub-portion of NETHER_FEATURE). */
        CORE_REPLACE,
        /** {@code MultiNoiseBiomeSourceMixin} highland/nether/end biome forcing (per quart). */
        BIOME_FORCE,
        /** {@code ChunkStatusSpawnMixin} upside-down mirror precompute. */
        MIRROR_PRECOMPUTE,
        /** {@code TrackBedFeature.place} — track/rail/pillar stamping. */
        TRACK_FEATURE,
        /** {@code DisintegrationFeature.place} — real-End router island sampling for the void-fade band (worker-thread). */
        DISINTEGRATION,
        /** {@code WorldDisintegrationEvents.onChunkLoad} — void erosion of the fade band (MAIN-thread; reported
         *  separately, not in {@link Sample#dtTotalMs} which is a worker-thread parallel sum). */
        EROSION,
        /** {@code WorldChuncksEvents.onChunkLoad} — chuncks-band top-down slice erosion (MAIN-thread, like
         *  {@link #EROSION}; excluded from {@link Sample#dtTotalMs}). The band's only real gen cost — void
         *  chunks skip fill + decoration, so this bucket staying ~0 confirms the band is near-free at gen. */
        CHUNCKS_SLICE,
        /** {@code NoiseBasedChunkGeneratorMixin} ocean-band sea build — raised-water + seabed fill on the
         *  worldgen WORKER thread (replaces the noise fill, so it is part of the parallel gen cost). */
        OCEAN_FILL
    }

    private static final int N = Bucket.values().length;
    private static final LongAdder[] NANOS = new LongAdder[N];
    private static final LongAdder CHUNKS = new LongAdder();

    static {
        for (int i = 0; i < N; i++) NANOS[i] = new LongAdder();
    }

    /**
     * Runtime gate. Default OFF (production) — the mod constructor calls {@link #setEnabled(boolean)}
     * with {@code DungeonTrain.isDevBuild()} so a dev ride profiles with no command, and
     * {@code /dungeontrain debug gen-timing} flips it at runtime. Kept independent of any Minecraft /
     * mod class at load time so the profiler is unit-testable in isolation.
     */
    private static volatile boolean enabled = false;

    private GenProfiler() {}

    public static boolean enabled() { return enabled; }

    public static void setEnabled(boolean value) { enabled = value; }

    /** Window start stamp, or {@code 0} when disabled (so {@link #add} is a no-op — the whole timer folds away). */
    public static long t0() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** Accumulate {@code now - start} nanos into {@code bucket}. No-op when {@code start == 0} (disabled / unstarted). */
    public static void add(Bucket bucket, long start) {
        if (start != 0L) NANOS[bucket.ordinal()].add(System.nanoTime() - start);
    }

    /** Tally one newly-generated chunk reaching FULL (main-thread call site). No-op when disabled. */
    public static void countChunk() {
        if (enabled) CHUNKS.increment();
    }

    /** A drained window: chunk count + per-bucket nanos. */
    public record Sample(long chunks, long[] nanos) {

        public double ms(Bucket b) {
            return nanos[b.ordinal()] / 1_000_000.0;
        }

        public double perChunkMs(Bucket b) {
            return chunks > 0 ? ms(b) / chunks : 0.0;
        }

        /** DT's total added worker-thread gen cost this window — excludes {@link Bucket#CORE_REPLACE}
         *  (already inside NETHER_FEATURE) and {@link Bucket#EROSION} (main-thread, not a worker slice). */
        public double dtTotalMs() {
            return ms(Bucket.DF) + ms(Bucket.NETHER_FEATURE) + ms(Bucket.BIOME_FORCE)
                    + ms(Bucket.MIRROR_PRECOMPUTE) + ms(Bucket.TRACK_FEATURE) + ms(Bucket.DISINTEGRATION);
        }

        public double dtTotalPerChunkMs() {
            return chunks > 0 ? dtTotalMs() / chunks : 0.0;
        }
    }

    /**
     * Drain every bucket + the chunk counter for one window, resetting them. Uses
     * {@link LongAdder#sumThenReset()} (approximately-atomic across concurrent writers — correct for a
     * rate sample). Call from exactly one place per window (the overworld-gated {@code [mspt]} flush)
     * or buckets will be reset twice.
     */
    public static Sample sampleAndReset() {
        long chunks = CHUNKS.sumThenReset();
        long[] out = new long[N];
        for (int i = 0; i < N; i++) out[i] = NANOS[i].sumThenReset();
        return new Sample(chunks, out);
    }
}
