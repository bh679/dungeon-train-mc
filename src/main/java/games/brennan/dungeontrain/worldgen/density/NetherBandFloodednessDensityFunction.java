package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Router wrapper over {@code fluidLevelFloodednessNoise} that keeps the Nether-band mountains' interior
 * <b>dry</b> — except for their lush caves, away from the track. Under a mountain top the vanilla aquifer
 * decides fluid from the floodedness noise (a water level per 40-block band, ±10), which floods carver caves
 * inside the band and would put pools on the rails inside the caverns ({@code worldgen/feature/CavernNoise}).
 * Returning {@code −1} fails both floodedness branches ({@code f > 0.4} and, for a column that already had
 * fluid at the surface, {@code f > −0.8}), so the aquifer yields "no fluid" — air — and no stone barrier
 * forms against the neighbouring wet cells (vanilla's own empty-vs-full neighbour case).
 *
 * <p>A lush-cave column ({@link NetherBandBiomeSet#isLushCave}, the same pick the biome source makes) outside
 * {@link #inTrackDryCorridor the track's dry corridor} passes vanilla's floodedness through instead, so those
 * caves get vanilla's aquifer lakes — most of a vanilla lush cave's water. Without a resolved biome set the
 * column stays dry.</p>
 *
 * <p>Whole raised column, every Y: the aquifer samples cell points up to ~24 blocks below the block they
 * decide, so a sea-level gate would still let a below-sea point flood a cavern floor. Off-band columns
 * are a pure pass-through (same column memo as {@link NetherBandTerrainDensityFunction}). Installed at
 * runtime by {@code RandomStateMixin}; must re-wrap in {@link #mapAll} because {@code NoiseChunk} maps
 * the router before it builds the aquifer.</p>
 */
public final class NetherBandFloodednessDensityFunction implements DensityFunction {

    /** Well under the aquifer's lowest floodedness branch ({@code −0.8}). */
    static final double DRY = -1.0;

    /**
     * Blocks either side of the widest track ({@code z 0..MAX_WIDTH−1}) kept dry even in lush caves. The
     * aquifer decides each block from its nearest cell points (16-block grid, jittered), so every point that
     * could feed the rails lies inside this margin.
     */
    static final int TRACK_DRY_MARGIN = 40;

    private final DensityFunction wrapped;

    public NetherBandFloodednessDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    /**
     * Whether {@code worldZ} is within {@link #TRACK_DRY_MARGIN} of the track at any carriage width. Also the
     * span where the crossfade's water drain keeps going in lush caves ({@code NetherTransitionFeature}).
     */
    public static boolean inTrackDryCorridor(int worldZ) {
        return worldZ >= -TRACK_DRY_MARGIN && worldZ <= CarriageDims.MAX_WIDTH - 1 + TRACK_DRY_MARGIN;
    }

    /** Whether the aquifer must be kept dry at column {@code (worldX, worldZ)}. */
    static boolean keepsDry(int worldX, int worldZ) {
        if (!NetherBandTerrainDensityFunction.columnRaises(worldX, worldZ)) return false;
        if (inTrackDryCorridor(worldZ)) return true;
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx == null || ctx.highlandBiomes() == null || ctx.cycle() == null) return true;
        // Quart-aligned, as the biome source picks the cave biome per quart.
        int qx = worldX & ~3;
        int qz = worldZ & ~3;
        return !ctx.highlandBiomes().isLushCave(ctx, qx, qz, ctx.cycle().netherDeepDarkSide(qx));
    }

    @Override
    public double compute(FunctionContext ctx) {
        double base = wrapped.compute(ctx);
        return keepsDry(ctx.blockX(), ctx.blockZ()) ? DRY : base;
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        wrapped.fillArray(values, contextProvider);
        for (int i = 0; i < values.length; i++) {
            FunctionContext fc = contextProvider.forIndex(i);
            if (keepsDry(fc.blockX(), fc.blockZ())) values[i] = DRY;
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new NetherBandFloodednessDensityFunction(wrapped.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return Math.min(wrapped.minValue(), DRY);
    }

    @Override
    public double maxValue() {
        return wrapped.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(MapCodec.unit(this));
    }
}
