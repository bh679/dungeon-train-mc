package games.brennan.dungeontrain.worldgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoises the End's {@code end_islands} erosion density function for the offline samplers.
 *
 * <p>Profiled on an End-band sample (JFR, 2026-10-01): <b>69 % of a sample</b> was End biome lookups, and
 * nearly all of that was vanilla {@code EndIslandDensityFunction.getHeightValue} — 625 simplex evaluations
 * per call. Every End biome source in play (vanilla's, and WorldWeaver's for BetterEnd) asks the climate
 * sampler's erosion at the <em>chunk-section centre</em> once per quart it fills, i.e. the same value 1 024
 * times for a 256-tall chunk, and {@link RandomState} strips the {@code cache_2d} marker that would
 * otherwise remember it. The function reads only {@code blockX}/{@code blockZ}, so remembering it by
 * (x, z) is exact.</p>
 *
 * <p>Only the samplers use the memoised sampler ({@link #sampler}); live End generation is untouched.
 * Bounded: past {@link #CAP} entries the memo is dropped and refilled. Shared across sampler threads.</p>
 */
public final class EndErosionMemo implements DensityFunction.SimpleFunction {

    /** Entries kept before the memo is dropped: one per End chunk asked about, so this is a lot of chunks. */
    static final int CAP = 1 << 14;
    /** Vanilla's {@code DensityFunctions.EndIslandDensityFunction} is package-private; matched by name. */
    private static final String END_ISLANDS_CLASS = "EndIslandDensityFunction";

    /** The memoised sampler for each random state's sampler; a handful of entries per server. */
    private static final Map<Climate.Sampler, Climate.Sampler> SAMPLERS = new ConcurrentHashMap<>();

    private final DensityFunction inner;
    private final int cap;
    private final ConcurrentHashMap<Long, Double> memo = new ConcurrentHashMap<>();

    EndErosionMemo(DensityFunction inner) {
        this(inner, CAP);
    }

    EndErosionMemo(DensityFunction inner, int cap) {
        this.inner = inner;
        this.cap = Math.max(1, cap);
    }

    /** True for vanilla's End-islands erosion function — the one worth memoising. */
    public static boolean isEndIslands(DensityFunction function) {
        return function != null && END_ISLANDS_CLASS.equals(function.getClass().getSimpleName());
    }

    /** {@code function} memoised if it is the End-islands erosion, else {@code function} itself. */
    public static DensityFunction wrap(DensityFunction function) {
        if (function instanceof EndErosionMemo) return function;
        return isEndIslands(function) ? new EndErosionMemo(function) : function;
    }

    /** {@code sampler} with its erosion memoised, or {@code sampler} itself when there is nothing to memoise. */
    public static Climate.Sampler memoise(Climate.Sampler sampler) {
        DensityFunction erosion = wrap(sampler.erosion());
        if (erosion == sampler.erosion()) return sampler;
        return new Climate.Sampler(sampler.temperature(), sampler.humidity(), sampler.continentalness(),
                erosion, sampler.depth(), sampler.weirdness(), sampler.spawnTarget());
    }

    /** The memoised climate sampler for {@code random}, shared by every sampler thread. */
    public static Climate.Sampler sampler(RandomState random) {
        return SAMPLERS.computeIfAbsent(random.sampler(), EndErosionMemo::memoise);
    }

    /** Forget every memo (server stopping / world change). */
    public static void clearAll() {
        SAMPLERS.clear();
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        long key = ChunkPos.asLong(context.blockX(), context.blockZ());
        Double known = memo.get(key);
        if (known != null) return known;
        double value = inner.compute(context);
        if (memo.size() >= cap) memo.clear();
        memo.put(key, value);
        return value;
    }

    int size() {
        return memo.size();
    }

    @Override
    public double minValue() {
        return inner.minValue();
    }

    @Override
    public double maxValue() {
        return inner.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return inner.codec();
    }
}
