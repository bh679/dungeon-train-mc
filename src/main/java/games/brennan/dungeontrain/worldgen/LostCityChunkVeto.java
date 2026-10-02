package games.brennan.dungeontrain.worldgen;

/**
 * Per-thread <b>chunk memo</b> for the Lost City start veto ({@code StructureBasementMixin}).
 *
 * <p>A rejected start makes vanilla's structure-set loop retry the set's other entries in the same chunk
 * — 71 of them for the Lost City set — so the veto was asked the same question up to 71 times per
 * placement chunk. Its chunk part ({@link LostCityStructures#allowedAt(long, WorldGenCycle, int, int)} and
 * whether the chunk is in the WWOO stretch) depends only on the chunk, the seed and the cycle, never the
 * structure; this memo computes it once per chunk and answers the retries from a small direct-mapped table.
 * Only the structure-dependent tail (the WWOO building pick) stays per call, and it is a set lookup.</p>
 *
 * <p><b>Byte-identical by construction:</b> a slot is reused only when the chunk, the seed, the cycle
 * instance (the memoised config cycle — any reload is a new one) and the live reverse slide
 * ({@link WorldGenCycle#reverseSlide()}, read by the layout lookups behind the cycle's back) all match the
 * call that filled it; a miss recomputes the same pure value. {@link #ENABLED} is the A/B seam — OFF is the
 * exact per-call path; same pattern as {@code ColumnBiomePlan}.</p>
 */
public final class LostCityChunkVeto {

    private LostCityChunkVeto() {}

    /** Live A/B gate; {@code volatile} so worldgen workers observe a flip immediately. Default ON. */
    public static volatile boolean ENABLED = true;

    /** The structure-independent verdict for a chunk. */
    public enum Verdict {
        /** No Lost City structure may start here. */
        DENY,
        /** Any Lost City structure may start here. */
        ALLOW,
        /** A WWOO-stretch chunk that kept its roll: only the world's picked buildings may start. */
        ALLOW_IF_WWOO_BUILDING
    }

    private static final int MASK = 63;

    private static final class Memo {
        final int[] chunkX = new int[MASK + 1];
        final int[] chunkZ = new int[MASK + 1];
        final long[] seed = new long[MASK + 1];
        final long[] slide = new long[MASK + 1];
        final WorldGenCycle[] cycle = new WorldGenCycle[MASK + 1];
        final Verdict[] verdict = new Verdict[MASK + 1];
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    /** {@link #compute}, memoised per worker thread by chunk, seed, cycle and reverse slide. */
    public static Verdict verdict(long seed, WorldGenCycle cycle, int chunkX, int chunkZ) {
        if (!ENABLED) return compute(seed, cycle, chunkX, chunkZ);
        Memo memo = MEMO.get();
        long slide = WorldGenCycle.reverseSlide();
        int i = (chunkX * 31 + chunkZ) & MASK;
        Verdict cached = memo.verdict[i];
        if (cached != null && memo.cycle[i] == cycle && memo.chunkX[i] == chunkX && memo.chunkZ[i] == chunkZ
                && memo.seed[i] == seed && memo.slide[i] == slide) {
            return cached;
        }
        Verdict fresh = compute(seed, cycle, chunkX, chunkZ);
        memo.chunkX[i] = chunkX;
        memo.chunkZ[i] = chunkZ;
        memo.seed[i] = seed;
        memo.slide[i] = slide;
        memo.cycle[i] = cycle;
        memo.verdict[i] = fresh;
        return fresh;
    }

    /** The direct, un-memoised verdict: the era rule with its roll, then whether the WWOO pick applies. */
    public static Verdict compute(long seed, WorldGenCycle cycle, int chunkX, int chunkZ) {
        if (!LostCityStructures.allowedAt(seed, cycle, chunkX, chunkZ)) return Verdict.DENY;
        return LostCityStructures.inWwooStretch(cycle, chunkX) ? Verdict.ALLOW_IF_WWOO_BUILDING : Verdict.ALLOW;
    }
}
