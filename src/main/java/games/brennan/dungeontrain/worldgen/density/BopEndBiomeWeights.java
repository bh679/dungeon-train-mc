package games.brennan.dungeontrain.worldgen.density;

import net.minecraft.util.random.WeightedEntry;

import java.util.List;

/**
 * Weights for the {@link BopEndBiomeSource} layout. Biomes O' Plenty's weights are whole numbers already low
 * ({@code end_corruption} is 3), so a biome is made rarer by raising everything else in its list, which leaves
 * the others' shares of each other as they were. A list without the biome is returned as it is.
 */
final class BopEndBiomeWeights {

    private BopEndBiomeWeights() {}

    /** {@code list} with {@code rare} about {@code factor} times rarer. */
    static <K> List<WeightedEntry.Wrapper<K>> rarer(List<WeightedEntry.Wrapper<K>> list, K rare, int factor) {
        if (list.stream().noneMatch(e -> rare.equals(e.data()))) return list;
        return list.stream()
                .map(e -> rare.equals(e.data()) ? e : WeightedEntry.wrap(e.data(), e.getWeight().asInt() * factor))
                .toList();
    }
}
