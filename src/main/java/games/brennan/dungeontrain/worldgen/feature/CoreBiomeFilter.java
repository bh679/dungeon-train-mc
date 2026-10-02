package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The Nether core's stand-in for vanilla's {@code minecraft:biome} placement filter: a position is kept
 * only when the <b>core biome of its column</b> lists the feature — the test vanilla's {@code BiomeFilter}
 * makes against {@code level.getBiome(pos)}. Vanilla's filter can't run here (it needs a registered top
 * feature and reads the overworld label), and dropping it outright let every biome sampled in a chunk
 * decorate the whole chunk: erupting_inferno's brimstone buds landed on a neighbouring withered_abyss's
 * netherrack, where later netherrack ores rewrote the floor under them.
 *
 * <p>Consumes no randomness, so feature seeds are unchanged. Never serialised — {@link #type()} only
 * satisfies the abstract contract.</p>
 */
final class CoreBiomeFilter extends PlacementFilter {

    /** Whether a feature may place in the column at {@code (x, z)}. */
    @FunctionalInterface
    interface ColumnTest {
        boolean allows(int x, int z);
    }

    private final ColumnTest test;

    CoreBiomeFilter(ColumnTest test) {
        this.test = test;
    }

    /** A filter that keeps positions whose column biome (from {@code biomes}) lists {@code feature}. */
    static CoreBiomeFilter listing(PlacedFeature feature, ColumnBiomes biomes) {
        return new CoreBiomeFilter((x, z) -> {
            Holder<Biome> b = biomes.at(x, z);
            return b == null || b.value().getGenerationSettings().hasFeature(feature);
        });
    }

    @Override
    protected boolean shouldPlace(PlacementContext ctx, RandomSource random, BlockPos pos) {
        return test.allows(pos.getX(), pos.getZ());
    }

    @Override
    public PlacementModifierType<?> type() {
        return PlacementModifierType.BIOME_FILTER;
    }

    /**
     * Core biome per column, memoised for one chunk's decoration pass (a chunk's features ask the same
     * columns many times). Not thread-safe — one instance per {@code place()} call.
     */
    static final class ColumnBiomes {
        private final BiFunction<Integer, Integer, Holder<Biome>> sampler;
        private final Map<Long, Holder<Biome>> memo = new HashMap<>();

        ColumnBiomes(BiFunction<Integer, Integer, Holder<Biome>> sampler) {
            this.sampler = sampler;
        }

        /** The core biome at column {@code (x, z)}; {@code null} when the sampler has none. */
        Holder<Biome> at(int x, int z) {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            if (memo.containsKey(key)) return memo.get(key);
            Holder<Biome> b = sampler.apply(x, z);
            memo.put(key, b);
            return b;
        }
    }
}
