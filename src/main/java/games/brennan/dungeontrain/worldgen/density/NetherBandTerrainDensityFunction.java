package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
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

    private final DensityFunction wrapped;

    public NetherBandTerrainDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    /** Apply the mountain raise at this position, or return {@code base} unchanged when out of band. */
    private double raisedOrBase(int worldX, int worldZ, int worldY, double base) {
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx == null || !ctx.enabled()) return base;
        WorldGenCycle cycle = ctx.cycle();
        if (!NetherMountainTerrain.raises(cycle, worldX)) return base;
        double t = NetherMountainTerrain.targetTop(cycle, ctx.generationSeed(), worldX, worldZ,
                ctx.seaLevel(), ctx.worldCeiling(), ctx.netherTop(), ctx.baseRelief());
        return Math.max(base, RAISE_SLOPE * (t - worldY));
    }

    @Override
    public double compute(FunctionContext ctx) {
        return raisedOrBase(ctx.blockX(), ctx.blockZ(), ctx.blockY(), wrapped.compute(ctx));
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        wrapped.fillArray(values, contextProvider);
        for (int i = 0; i < values.length; i++) {
            FunctionContext ctx = contextProvider.forIndex(i);
            values[i] = raisedOrBase(ctx.blockX(), ctx.blockZ(), ctx.blockY(), values[i]);
        }
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
