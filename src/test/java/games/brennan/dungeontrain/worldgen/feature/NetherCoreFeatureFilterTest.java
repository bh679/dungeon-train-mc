package games.brennan.dungeontrain.worldgen.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link NetherCoreFeatureFilter}: a vanilla Nether core biome places only vanilla and
 * Dungeon Train features, and a BetterNether core biome keeps its full list.
 */
final class NetherCoreFeatureFilterTest {

    @Test
    @DisplayName("vanilla biome keeps vanilla and Dungeon Train features")
    void vanillaBiomeKeepsVanillaAndDt() {
        assertTrue(NetherCoreFeatureFilter.allowed("minecraft", "minecraft"));
        assertTrue(NetherCoreFeatureFilter.allowed("minecraft", "dungeontrain"));
    }

    @Test
    @DisplayName("vanilla biome drops BetterNether's injected ores and any other mod's features")
    void vanillaBiomeDropsModFeatures() {
        assertFalse(NetherCoreFeatureFilter.allowed("minecraft", "betternether"));
        assertFalse(NetherCoreFeatureFilter.allowed("minecraft", "biomesoplenty"));
    }

    @Test
    @DisplayName("BetterNether biome keeps its full list, vanilla features included")
    void betterNetherBiomeUnfiltered() {
        assertTrue(NetherCoreFeatureFilter.allowed("betternether", "betternether"));
        assertTrue(NetherCoreFeatureFilter.allowed("betternether", "minecraft"));
    }

    @Test
    @DisplayName("unkeyed biome or feature is allowed, never silently dropped")
    void unknownNamespacesAllowed() {
        assertTrue(NetherCoreFeatureFilter.allowed(null, "betternether"));
        assertTrue(NetherCoreFeatureFilter.allowed("minecraft", null));
        assertTrue(NetherCoreFeatureFilter.allowed(null, null));
    }
}
