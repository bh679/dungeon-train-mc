package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
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
 * linear ramp that crosses 0 at the per-column target top {@code T}, so the column reads solid
 * below {@code T} and air above. {@code max} (never lowers the child) keeps natural caves/ores
 * below the original surface and only ADDS the mountain on top.</p>
 *
 * <p>The mixin installs it over <b>both</b> router densities that drive the surface:</p>
 * <ul>
 *   <li>{@code finalDensity} — the terrain solidity used by {@code fillFromNoise} (blocks) and
 *       {@code getBaseHeight} (structure placement). Raising it lifts the actual terrain top.</li>
 *   <li>{@code initialDensityWithoutJaggedness} — from which {@code NoiseChunk} derives the
 *       <em>preliminary surface level</em> (the first Y where it exceeds {@code 0.390625}) that the
 *       {@code minecraft:above_preliminary_surface} surface-rule gate keys off. Raising it the same
 *       way lifts that gate to the new top, so the mountain paints grass/dirt instead of bare rock
 *       (and avoids aquifer water pockets mid-mountain). Without this the fix would be incomplete.</li>
 * </ul>
 *
 * <p>Installed at runtime by {@code RandomStateMixin} (gated to the overworld via
 * {@link NetherBandHooks#CONSTRUCTING_OVERWORLD}); never serialized. Determinism comes from the
 * pure {@link WorldGenCycle} layout math (snapshotted once at construction).</p>
 *
 * <p><b>M1 prototype:</b> {@code T} is a fixed plateau height gated only on
 * {@link WorldGenCycle#netherHeightRamp} {@code > 0}. M2 replaces {@link #targetTop} with the real
 * {@code MountainNoise}-driven ramp and the per-world enable/seed gate.</p>
 */
public final class NetherBandTerrainDensityFunction implements DensityFunction {

    // --- M1 prototype constants (replaced by the real ramp in M2) -----------------------------
    /** Fixed plateau top (world-Y) the band raises terrain to in the prototype. */
    private static final double PROTOTYPE_TARGET_TOP = 150.0;
    /** Slope {@code k} of the {@code k·(T − y)} ramp — small; only the sign at the surface matters
     *  and the 4×16 interpolation cell softens the step (a large k buys nothing). */
    private static final double RAISE_SLOPE = 0.08;
    /** Generous lower world-Y bound used only to bound {@link #maxValue()} (err high, never low). */
    private static final int RAISE_MAX_Y_FLOOR = -64;

    private final DensityFunction wrapped;
    private final WorldGenCycle cycle;

    public NetherBandTerrainDensityFunction(DensityFunction wrapped) {
        this(wrapped, WorldGenCycle.fromConfig());
    }

    private NetherBandTerrainDensityFunction(DensityFunction wrapped, WorldGenCycle cycle) {
        this.wrapped = wrapped;
        this.cycle = cycle;
    }

    /** True where the nether band raises terrain at this world-X (overworld gating is done at install time). */
    private boolean bandActive(int worldX) {
        return cycle.netherHeightRamp(worldX) > 0.0;
    }

    /** Per-column mountain target top (world-Y). M1: a fixed plateau; M2: the real ramp. */
    private double targetTop(int worldX, int worldZ) {
        return PROTOTYPE_TARGET_TOP;
    }

    private double raised(int worldX, int worldZ, int worldY, double base) {
        double t = targetTop(worldX, worldZ);
        return Math.max(base, RAISE_SLOPE * (t - worldY));
    }

    @Override
    public double compute(FunctionContext ctx) {
        double base = wrapped.compute(ctx);
        int x = ctx.blockX();
        if (!bandActive(x)) return base;
        return raised(x, ctx.blockZ(), ctx.blockY(), base);
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        wrapped.fillArray(values, contextProvider);
        for (int i = 0; i < values.length; i++) {
            FunctionContext ctx = contextProvider.forIndex(i);
            int x = ctx.blockX();
            if (!bandActive(x)) continue;
            values[i] = raised(x, ctx.blockZ(), ctx.blockY(), values[i]);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new NetherBandTerrainDensityFunction(wrapped.mapAll(visitor), cycle));
    }

    @Override
    public double minValue() {
        // max(...) never lowers the wrapped value, so the child's minimum is a valid lower bound.
        return wrapped.minValue();
    }

    @Override
    public double maxValue() {
        // The ramp peaks at the lowest sampled Y; bound generously (err high — a too-high bound is
        // safe, it only disables a per-cell skip optimisation; a too-low bound would be a bug).
        double raiseMax = RAISE_SLOPE * (PROTOTYPE_TARGET_TOP - RAISE_MAX_Y_FLOOR);
        return Math.max(wrapped.maxValue(), raiseMax);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        // Installed at runtime, never serialized — a unit codec keeps any stray mapAll/debug path safe.
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
