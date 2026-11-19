package games.brennan.dungeontrain.worldgen.density;

import net.minecraft.util.random.WeightedEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** How a biome is made rarer in the Biomes O' Plenty End's weighted lists. */
class BopEndBiomeWeightsTest {

    private static int weight(List<WeightedEntry.Wrapper<String>> list, String key) {
        return list.stream().filter(e -> e.data().equals(key)).findFirst().orElseThrow().getWeight().asInt();
    }

    @Test
    @DisplayName("BoP's highlands weights put the rare biome near 1% at factor 10, down from about 11%")
    void rarerByRaisingTheRest() {
        List<WeightedEntry.Wrapper<String>> list = List.of(WeightedEntry.wrap("vanilla", 10), WeightedEntry.wrap("wilds", 9),
                WeightedEntry.wrap("reef", 6), WeightedEntry.wrap("rare", 3));
        List<WeightedEntry.Wrapper<String>> out = BopEndBiomeWeights.rarer(list, "rare", 10);
        assertEquals(3, weight(out, "rare"));
        assertEquals(100, weight(out, "vanilla"));
        assertEquals(90, weight(out, "wilds"));
        assertEquals(60, weight(out, "reef"));
    }

    @Test
    @DisplayName("a list without the biome is left alone, so that zone's layout does not change")
    void otherListsUntouched() {
        List<WeightedEntry.Wrapper<String>> list = List.of(WeightedEntry.wrap("vanilla", 10));
        assertSame(list, BopEndBiomeWeights.rarer(list, "rare", 10));
    }
}
