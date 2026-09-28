package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Wraps the overworld's <b>erosion</b> noise so the upside-down band's source terrain stays low near the
 * track — see {@link UpsideDownTrackFlatten} for the why and the maths. Installed by
 * {@code RandomStateMixin} in place of every {@code minecraft:erosion} noise node, so it reaches both the
 * terrain splines (offset / factor / jaggedness — the terrain's height) and the router's climate
 * {@code erosion} the biome source samples (the biome pick): the two move together.
 *
 * <p>Reads {@link UpsideDownTrackFlatten#current()} lazily; a missing or disabled context is a pure
 * pass-through, so terrain before the context is published — and in worlds without the band — is
 * exactly vanilla. Installed at runtime, never serialized.</p>
 *
 * <p>The mountain gate ({@link UpsideDownTrackFlatten#mountainGate}) samples this node's <em>own</em>
 * untouched child on the track centre line once per X, so each generator (the overworld, a preset band's)
 * gates on its own terrain.</p>
 */
public final class TrackErosionDensityFunction implements DensityFunction {

    /**
     * Per-worker memo of the X-only parts of the weight — the band weight (a short distance search near
     * the band's ends) and each side's edge openness (a few octaves of noise). Direct-mapped by X; a miss
     * simply recomputes the same pure value. Guarded by context identity so a republish never serves a
     * stale weight.
     */
    private static final int X_MASK = 63;

    private static final class XMemo {
        UpsideDownTrackFlatten.Context ctx;
        DensityFunction owner;
        final int[] x = new int[X_MASK + 1];
        final boolean[] present = new boolean[X_MASK + 1];
        final double[] w = new double[X_MASK + 1];
        final double[] openPos = new double[X_MASK + 1];
        final double[] openNeg = new double[X_MASK + 1];
    }

    private static final ThreadLocal<XMemo> X_MEMO = ThreadLocal.withInitial(XMemo::new);

    private final DensityFunction wrapped;

    public TrackErosionDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    /**
     * The memo slot for {@code worldX}, filled (band weight × mountain gate + both sides' edge openness) on
     * a miss. Keyed on this node too: another generator's erosion answers its own gate.
     */
    private int slot(XMemo memo, UpsideDownTrackFlatten.Context ctx, int worldX) {
        if (memo.ctx != ctx || memo.owner != wrapped) {
            java.util.Arrays.fill(memo.present, false);
            memo.ctx = ctx;
            memo.owner = wrapped;
        }
        int i = worldX & X_MASK;
        if (memo.present[i] && memo.x[i] == worldX) return i;
        double w = UpsideDownTrackFlatten.bandWeight(ctx.cycle(), worldX);
        if (w > 0.0) {
            // only where the untouched line would run into a mountain
            double line = wrapped.compute(new DensityFunction.SinglePointContext(worldX, 0, ctx.trackCenterZ()));
            w *= UpsideDownTrackFlatten.mountainGate(line);
        }
        memo.x[i] = worldX;
        memo.w[i] = w;
        if (w > 0.0) {   // edge noise only matters where the band weight does
            memo.openPos[i] = UpsideDownTrackFlatten.edgeOpenness(ctx.seed(), worldX, true);
            memo.openNeg[i] = UpsideDownTrackFlatten.edgeOpenness(ctx.seed(), worldX, false);
        }
        memo.present[i] = true;
        return i;
    }

    private double adjust(UpsideDownTrackFlatten.Context ctx, int worldX, int worldZ, double erosion) {
        if (erosion >= UpsideDownTrackFlatten.EROSION_FLOOR) return erosion;
        if (Math.abs(worldZ - ctx.trackCenterZ()) >= UpsideDownTrackFlatten.TRACK_OUTER_MAX) return erosion;
        XMemo memo = X_MEMO.get();
        int i = slot(memo, ctx, worldX);
        double wx = memo.w[i];
        if (wx <= 0.0) return erosion;
        double open = worldZ >= ctx.trackCenterZ() ? memo.openPos[i] : memo.openNeg[i];
        double wz = UpsideDownTrackFlatten.trackWeight(worldZ, ctx.trackCenterZ(), open);
        if (wz <= 0.0) return erosion;
        return UpsideDownTrackFlatten.apply(erosion, wx * wz);
    }

    @Override
    public double compute(FunctionContext fc) {
        double e = wrapped.compute(fc);
        UpsideDownTrackFlatten.Context ctx = UpsideDownTrackFlatten.current();
        if (ctx == null || !ctx.enabled()) return e;
        long t0 = GenProfiler.t0();
        double out = adjust(ctx, fc.blockX(), fc.blockZ(), e);
        GenProfiler.add(GenProfiler.Bucket.DF, t0);
        return out;
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        wrapped.fillArray(values, provider);
        UpsideDownTrackFlatten.Context ctx = UpsideDownTrackFlatten.current();
        if (ctx == null || !ctx.enabled()) return;
        long t0 = GenProfiler.t0();
        for (int i = 0; i < values.length; i++) {
            FunctionContext fc = provider.forIndex(i);
            values[i] = adjust(ctx, fc.blockX(), fc.blockZ(), values[i]);
        }
        GenProfiler.add(GenProfiler.Bucket.DF, t0);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new TrackErosionDensityFunction(wrapped.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        // Only ever raises erosion, so the child's minimum still bounds it from below.
        return wrapped.minValue();
    }

    @Override
    public double maxValue() {
        return Math.max(wrapped.maxValue(), UpsideDownTrackFlatten.EROSION_FLOOR);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
