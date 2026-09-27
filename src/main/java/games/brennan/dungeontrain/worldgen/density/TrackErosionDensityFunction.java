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
 */
public final class TrackErosionDensityFunction implements DensityFunction {

    /**
     * Per-worker memo of the X-only band weight — the only non-trivial part of the weight (a short
     * distance search near the band's ends). Direct-mapped by X; a miss simply recomputes the same
     * pure value. Guarded by context identity so a republish never serves a stale weight.
     */
    private static final int X_MASK = 63;

    private static final class XMemo {
        UpsideDownTrackFlatten.Context ctx;
        final int[] x = new int[X_MASK + 1];
        final boolean[] present = new boolean[X_MASK + 1];
        final double[] w = new double[X_MASK + 1];
    }

    private static final ThreadLocal<XMemo> X_MEMO = ThreadLocal.withInitial(XMemo::new);

    private final DensityFunction wrapped;

    public TrackErosionDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    private static double bandWeight(UpsideDownTrackFlatten.Context ctx, int worldX) {
        XMemo memo = X_MEMO.get();
        if (memo.ctx != ctx) {
            java.util.Arrays.fill(memo.present, false);
            memo.ctx = ctx;
        }
        int i = worldX & X_MASK;
        if (memo.present[i] && memo.x[i] == worldX) return memo.w[i];
        double w = UpsideDownTrackFlatten.bandWeight(ctx.cycle(), worldX);
        memo.x[i] = worldX;
        memo.w[i] = w;
        memo.present[i] = true;
        return w;
    }

    private static double adjust(UpsideDownTrackFlatten.Context ctx, int worldX, int worldZ, double erosion) {
        if (erosion >= UpsideDownTrackFlatten.EROSION_FLOOR) return erosion;
        double wz = UpsideDownTrackFlatten.trackWeight(worldZ, ctx.trackCenterZ());
        if (wz <= 0.0) return erosion;
        double wx = bandWeight(ctx, worldX);
        if (wx <= 0.0) return erosion;
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
