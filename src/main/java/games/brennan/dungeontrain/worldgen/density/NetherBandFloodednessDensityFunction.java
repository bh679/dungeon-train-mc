package games.brennan.dungeontrain.worldgen.density;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Router wrapper over {@code fluidLevelFloodednessNoise} that keeps the Nether-band mountains' interior
 * <b>dry</b>. Under a mountain top the vanilla aquifer decides fluid from the floodedness noise (a water
 * level per 40-block band, ±10), which is what floods carver caves inside the band today and would put
 * pools on the rails inside the new caverns ({@code worldgen/feature/CavernNoise}). Returning {@code −1}
 * fails both floodedness branches ({@code f > 0.4} and, for a column that already had fluid at the
 * surface, {@code f > −0.8}), so the aquifer yields "no fluid" — air — and no stone barrier forms
 * against the neighbouring wet cells (vanilla's own empty-vs-full neighbour case).
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

    private final DensityFunction wrapped;

    public NetherBandFloodednessDensityFunction(DensityFunction wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double base = wrapped.compute(ctx);
        return NetherBandTerrainDensityFunction.columnRaises(ctx.blockX(), ctx.blockZ()) ? DRY : base;
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        wrapped.fillArray(values, contextProvider);
        for (int i = 0; i < values.length; i++) {
            FunctionContext fc = contextProvider.forIndex(i);
            if (NetherBandTerrainDensityFunction.columnRaises(fc.blockX(), fc.blockZ())) values[i] = DRY;
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
