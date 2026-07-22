package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density-router wrapper that raises the overworld terrain into the nether-transition band's
 * <b>mountains as REAL terrain</b> — so vanilla surface rules (grass/dirt/snow), tree/vegetation
 * decoration, and structures all land on the raised ground naturally, instead of being buried by
 * a post-process stamp (the old {@code NetherTransitionFeature.fillMountainColumn} behaviour).
 *
 * <p>It applies one transform — {@code max(child, k·(T − y))} — to a wrapped density: a downward
 * linear ramp that crosses 0 at the per-column target top {@code T} ({@link NetherMountainTerrain#targetTop}),
 * so the column reads solid below {@code T} and air above. {@code max} (never lowers the child)
 * keeps natural caves/ores below the original surface and lets naturally-higher ground show
 * through, so the mountain is layered onto real terrain rather than replacing it.</p>
 *
 * <p>The mixin installs it over <b>both</b> router densities that drive the surface:</p>
 * <ul>
 *   <li>{@code finalDensity} — terrain solidity for {@code fillFromNoise} (blocks) and
 *       {@code getBaseHeight} (structure placement).</li>
 *   <li>{@code initialDensityWithoutJaggedness} — from which {@code NoiseChunk} derives the
 *       preliminary surface level the {@code minecraft:above_preliminary_surface} surface-rule gate
 *       keys off. Raising it the same way lifts that gate to the new top, so the mountain paints
 *       grass/dirt (not bare rock) and avoids aquifer water pockets mid-mountain.</li>
 * </ul>
 *
 * <p>Per-world shaping (seed, sea level, ceiling, nether-core height, the layout {@link WorldGenCycle})
 * is read <em>lazily</em> from {@link NetherBandContext} — which is published at server start, after
 * the router is built. A {@code null} / disabled context, or a column outside the band
 * ({@link NetherMountainTerrain#raises}), makes this a pass-through. Installed at runtime by
 * {@code RandomStateMixin} (gated to the overworld via {@link NetherBandHooks}); never serialized.</p>
 */
public final class NetherBandTerrainDensityFunction implements DensityFunction {

    /** Slope {@code k} of the {@code k·(T − y)} ramp — small; only the sign at the surface matters
     *  and the 4×16 interpolation cell softens the step (a large k buys nothing). */
    private static final double RAISE_SLOPE = 0.08;
    /** Generous bounds (world-Y) used only to bound {@link #maxValue()} across all Y-variants
     *  (err high — a too-high bound only disables a per-cell skip optimisation; too low is a bug). */
    private static final double MAX_TARGET_TOP_BOUND = 512.0;
    private static final double MIN_Y_BOUND = -64.0;

    /**
     * Extra X-window slack (each side) for the whole-batch reject in {@link #fillArray}, on top of the
     * probed first/last-sample bounds and the edge-wave margin. A NoiseChunk batch spans at most one
     * chunk + one interpolation cell (16 + cellWidth ≤ 16 → 32 blocks); 64 is a 2× safety factor over
     * that worst case. Oversizing only costs skipped optimisation near band edges — never correctness.
     */
    private static final int BATCH_X_SLACK = 64;

    private final DensityFunction wrapped;

    public NetherBandTerrainDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    /**
     * Per-worker <b>column memo</b>: caches the Y-independent {@code {skip, targetTop}} decision of
     * {@link #raisedOrBase} so a column's 4-octave {@code MountainNoise} evaluations (inside
     * {@code wavyX} and {@code targetTop}) run once per {@code (x,z)} column instead of once per Y
     * sample. Direct-mapped by {@code (x,z)}; a miss or hash collision simply recomputes the exact
     * same value — the memo is a pure function of the inputs, so it is byte-identical to the
     * un-memoised path (worst case it degrades to today's recompute, never to a wrong value). Guarded
     * by the {@link NetherBandContext} identity so a world/config republish can never serve a stale
     * target (the target depends on the seed / sea level / ceiling captured in the context).
     *
     * <p>Off-band columns (the ride's majority) memoise {@code skip=true} after one {@code wavyX};
     * in-band columns memoise the raise target. The table is small and per-thread, so concurrent
     * {@code fillArray} workers never contend, and it is robust to fill order (unlike a single-entry
     * cache): a chunk has only 16×16 columns, well under the table size.</p>
     */
    private static final int COL_BITS = 8;
    private static final int COL_SIZE = 1 << COL_BITS;   // 256 — > the 256 columns of a chunk
    private static final int COL_MASK = COL_SIZE - 1;

    private static final class ColumnMemo {
        NetherBandContext ctx;                    // identity guard — republish drops every cached column
        final long[] key = new long[COL_SIZE];
        final boolean[] present = new boolean[COL_SIZE];
        final boolean[] skip = new boolean[COL_SIZE];
        final double[] target = new double[COL_SIZE];
    }

    private static final ThreadLocal<ColumnMemo> COLUMN_MEMO = ThreadLocal.withInitial(ColumnMemo::new);

    /**
     * Resolve the per-worker memo for {@code ctx}, dropping every cached column when the context identity
     * changed (a world/config republish). The identity guard is a batch invariant — {@code ctx} is constant
     * across a whole {@code fillArray} cell — so callers run this once per batch, not once per sample.
     */
    private static ColumnMemo memoFor(NetherBandContext ctx) {
        ColumnMemo memo = COLUMN_MEMO.get();
        if (memo.ctx != ctx) {                    // world/config republish → drop every stale column
            java.util.Arrays.fill(memo.present, false);
            memo.ctx = ctx;
        }
        return memo;
    }

    /**
     * Apply the mountain raise at this position, or return {@code base} unchanged when out of band — the
     * hot per-sample core, with every batch-invariant already resolved by the caller ({@code ctx}, its
     * {@code cycle}/seed/sea-level/ceiling/nether-top/base-relief, and the per-worker {@code memo}). Keeping
     * these out of the loop removes a {@link NetherBandContext#current()} static read, a {@link ThreadLocal}
     * probe, an {@code enabled()} call and the identity-guard from every density sample; only the column-memo
     * lookup + {@code max} remain. Package-private so the byte-identical memo behaviour is unit-testable
     * (via {@link #raisedOrBase}) without a {@code NoiseChunk} / {@code DensityFunction} stub.
     */
    private static double raise(ColumnMemo memo, WorldGenCycle cycle, long seed, int seaLevel, int ceiling,
                                int netherTop, int baseRelief, int worldX, int worldZ, int worldY, double base) {
        long ckey = (((long) worldX) << 32) ^ (worldZ & 0xFFFFFFFFL);
        int idx = (worldX * 31 + worldZ) & COL_MASK;
        boolean skip;
        double t;
        if (memo.present[idx] && memo.key[idx] == ckey) {
            skip = memo.skip[idx];
            t = memo.target[idx];
        } else if (games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED
                && !cycle.netherInfluence(worldX, NetherMountainTerrain.maxEdgeShift())) {
            // Off-band early-out: no x′ within the edge-wave margin can be in the nether segment, so
            // raises(wavyX(x)) is provably false — skip without paying the 4-octave wavyX noise.
            // Byte-identical to the full evaluation below (the predicate is a conservative superset,
            // proven by the stride-1 sweep in WorldGenCycleTest); still memoised so repeat samples of
            // this column take the fast present-hit path.
            skip = true;
            t = 0.0;
            memo.key[idx] = ckey;
            memo.skip[idx] = true;
            memo.target[idx] = 0.0;
            memo.present[idx] = true;
        } else {
            // Evaluate the band at the edge-waved X so the leading/trailing front undulates across Z rather
            // than starting at one straight X (matched in the biome source + post-process feature).
            int wx = NetherMountainTerrain.wavyX(seed, worldX, worldZ);
            skip = !NetherMountainTerrain.raises(cycle, wx);
            t = skip ? 0.0 : NetherMountainTerrain.targetTop(cycle, seed, wx, worldZ,
                    seaLevel, ceiling, netherTop, baseRelief);
            memo.key[idx] = ckey;
            memo.skip[idx] = skip;
            memo.target[idx] = t;
            memo.present[idx] = true;
        }

        if (skip) return base;
        return Math.max(base, RAISE_SLOPE * (t - worldY));
    }

    /**
     * Single-sample convenience over {@link #raise}: resolves the context + memo (a null/disabled context is
     * a pure pass-through) then delegates. Used by {@link #compute} and the unit tests; the hot
     * {@link #fillArray} path hoists the same resolution out of its loop instead.
     */
    double raisedOrBase(int worldX, int worldZ, int worldY, double base) {
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx == null || !ctx.enabled()) return base;
        ColumnMemo memo = memoFor(ctx);
        return raise(memo, ctx.cycle(), ctx.generationSeed(), ctx.seaLevel(), ctx.worldCeiling(),
                ctx.netherTop(), ctx.baseRelief(), worldX, worldZ, worldY, base);
    }

    @Override
    public double compute(FunctionContext ctx) {
        double base = wrapped.compute(ctx);             // vanilla child — not DT's added tax
        long t0 = GenProfiler.t0();
        double raised = raisedOrBase(ctx.blockX(), ctx.blockZ(), ctx.blockY(), base);
        GenProfiler.add(GenProfiler.Bucket.DF, t0);
        return raised;
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        wrapped.fillArray(values, contextProvider);     // vanilla child — not DT's added tax
        long t0 = GenProfiler.t0();
        // Batch invariants (context identity + per-column shaping inputs + the memo) are constant across the
        // whole cell, so resolve them ONCE here rather than per density sample inside the loop. A null/
        // disabled context makes the whole batch a pure pass-through — the child's output is already in place.
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx != null && ctx.enabled()) {
            ColumnMemo memo = memoFor(ctx);
            WorldGenCycle cycle = ctx.cycle();
            long seed = ctx.generationSeed();
            int seaLevel = ctx.seaLevel(), ceiling = ctx.worldCeiling();
            int netherTop = ctx.netherTop(), baseRelief = ctx.baseRelief();
            // Per-X off-band memo — method-local ONLY (this instance is shared across worker threads
            // and chunks; latching per-instance chunk state would race). Cell iteration is Y-outer /
            // X-mid / Z-inner, so the few distinct X of a cell recur thousands of times: a small
            // direct-mapped table (x & mask; collisions just recompute) answers all repeats, and the
            // hoisted Influence snapshot makes the misses a handful of compares. When an X plane is
            // provably off-band we skip the per-sample key build + memo probes entirely (the ride's
            // overworld-gap majority). Byte-identical: skipping leaves the child's value in place,
            // exactly what raise() returns for an off-band column.
            WorldGenCycle.Influence inf = cycle.influence();
            int margin = NetherMountainTerrain.maxEdgeShift();
            // Whole-batch O(1) reject — the load-bearing early-out. Measured floor of the per-sample
            // loop below is ~2 ms/chunk of pure forIndex()/blockX() virtual-call overhead even when
            // every sample skips, so off-band chunks (the ride's majority) must skip the LOOP, not the
            // samples. This DF is installed at runtime on the noise router and only ever driven by
            // NoiseChunk during chunk gen, whose batches span one chunk plus at most one interpolation
            // cell in X (16 + cellWidth ≤ 16 → ≤ 32 blocks); the first and last sample bound the
            // per-layer X range under its Y-outer/X-mid/Z-inner order. Both endpoints are probed and
            // BATCH_X_SLACK doubles the worst-case span on top of the edge-wave margin, so the reject
            // window is a strict superset of every X the batch can contain — false positives only ever
            // fall through to the exact per-sample path (never a wrong skip). Seam-safety is pinned by
            // the fillArray byte-identity test and the pre/post region-palette diff.
            // A/B kill-switch: with early-outs OFF this whole method degrades to the pre-change
            // per-sample raise loop below (byte-identical output either way; only timing differs).
            boolean earlyOuts = games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED;
            if (earlyOuts && values.length > 0) {
                int xFirst = contextProvider.forIndex(0).blockX();
                int xLast = contextProvider.forIndex(values.length - 1).blockX();
                int lo = Math.min(xFirst, xLast), hi = Math.max(xFirst, xLast);
                int center = lo + (hi - lo) / 2;
                int half = (hi - lo + 1) / 2;
                if (!inf.nether(center, half + margin + BATCH_X_SLACK)) {
                    GenProfiler.add(GenProfiler.Bucket.DF, t0);
                    return;                                   // whole batch provably off-band
                }
            }
            final int xMask = 63;
            int[] memoX = new int[xMask + 1];
            byte[] memoSkip = new byte[xMask + 1];            // 0 = empty, 1 = skip, 2 = evaluate
            for (int i = 0; i < values.length; i++) {
                FunctionContext fc = contextProvider.forIndex(i);
                int bx = fc.blockX();
                if (earlyOuts) {
                    int xi = bx & xMask;
                    byte state = (memoX[xi] == bx) ? memoSkip[xi] : 0;
                    if (state == 0) {
                        state = inf.nether(bx, margin) ? (byte) 2 : (byte) 1;
                        memoX[xi] = bx;
                        memoSkip[xi] = state;
                    }
                    if (state == 1) continue;
                }
                values[i] = raise(memo, cycle, seed, seaLevel, ceiling, netherTop, baseRelief,
                        bx, fc.blockZ(), fc.blockY(), values[i]);
            }
        }
        GenProfiler.add(GenProfiler.Bucket.DF, t0);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new NetherBandTerrainDensityFunction(wrapped.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        // max(...) never lowers the wrapped value, so the child's minimum is a valid lower bound.
        return wrapped.minValue();
    }

    @Override
    public double maxValue() {
        double raiseMax = RAISE_SLOPE * (MAX_TARGET_TOP_BOUND - MIN_Y_BOUND);
        return Math.max(wrapped.maxValue(), raiseMax);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // Installed at runtime, never serialized — a unit codec keeps any stray mapAll/debug path safe.
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
