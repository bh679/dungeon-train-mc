package games.brennan.dungeontrain.worldgen.density;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link BetterNetherCoreBiomes}: which Nether passes are BetterNether, the sub-biome filter, and
 * the seeded cell picker (deterministic, no neighbouring repeats, covers the list across a core).
 */
final class BetterNetherCoreBiomesTest {

    private static final List<String> BIOMES = List.of(
            "bone_reef", "crimson_pinewood", "gloomwood", "gravel_desert", "magma_land",
            "nether_jungle", "nether_mushroom_forest", "nether_swampland", "soul_plain", "wart_forest");

    @Test
    @DisplayName("alternate passes: 1, 3, 5 are BetterNether; 0, 2, 4 and -1 stay vanilla")
    void alternatePasses() {
        assertFalse(BetterNetherCoreBiomes.isBetterNetherPass(-1));
        assertFalse(BetterNetherCoreBiomes.isBetterNetherPass(0));
        assertTrue(BetterNetherCoreBiomes.isBetterNetherPass(1));
        assertFalse(BetterNetherCoreBiomes.isBetterNetherPass(2));
        assertTrue(BetterNetherCoreBiomes.isBetterNetherPass(3));
        assertFalse(BetterNetherCoreBiomes.isBetterNetherPass(4));
        assertTrue(BetterNetherCoreBiomes.isBetterNetherPass(5));
    }

    @Test
    @DisplayName("edge / terraces / cleared sub-biomes never get a full cell")
    void subBiomeFilter() {
        assertTrue(BetterNetherCoreBiomes.isStandaloneBiomePath("nether_jungle"));
        assertFalse(BetterNetherCoreBiomes.isStandaloneBiomePath("wart_forest_edge"));
        assertFalse(BetterNetherCoreBiomes.isStandaloneBiomePath("nether_swampland_terraces"));
        assertFalse(BetterNetherCoreBiomes.isStandaloneBiomePath("upside_down_forest_cleared"));
        assertFalse(BetterNetherCoreBiomes.isStandaloneBiomePath(""));
        assertFalse(BetterNetherCoreBiomes.isStandaloneBiomePath(null));
    }

    @Test
    @DisplayName("an empty biome list yields no picker, so callers fall back to vanilla")
    void emptyListHasNoPicker() {
        assertNull(BetterNetherCoreBiomes.of(List.of(), 1L));
        assertNull(BetterNetherCoreBiomes.of(null, 1L));
    }

    @Test
    @DisplayName("same seed gives the same biome at every column; another seed reorders")
    void deterministicFromSeed() {
        BetterNetherCoreBiomes<String> a = BetterNetherCoreBiomes.of(BIOMES, 42L);
        BetterNetherCoreBiomes<String> b = BetterNetherCoreBiomes.of(BIOMES, 42L);
        BetterNetherCoreBiomes<String> c = BetterNetherCoreBiomes.of(BIOMES, 43L);
        boolean anyDifferent = false;
        for (int x = -3000; x <= 9000; x += 37) {
            for (int z = -40; z <= 40; z += 20) {
                assertEquals(a.biomeAt(x, z), b.biomeAt(x, z));
                if (!a.biomeAt(x, z).equals(c.biomeAt(x, z))) anyDifferent = true;
            }
        }
        assertTrue(anyDifferent, "a different seed should change the order");
    }

    @Test
    @DisplayName("neighbouring cells are never the same biome, and one core crosses the whole list")
    void distinctNeighboursAndCoverage() {
        BetterNetherCoreBiomes<String> picker = BetterNetherCoreBiomes.of(BIOMES, 7L);
        Set<String> seen = new HashSet<>();
        String previous = null;
        int coreLength = 5000;
        for (int x = 20_000; x < 20_000 + coreLength; x += BetterNetherCoreBiomes.CELL_BLOCKS) {
            String here = picker.biomeAt(x, 0);
            if (previous != null) assertNotEquals(previous, here);
            previous = here;
            seen.add(here);
        }
        assertEquals(BIOMES.size(), seen.size(), "a default-length core should visit every biome");
    }

    @Test
    @DisplayName("cell borders wave along Z but stay within the wobble bound")
    void bordersWaveAlongZ() {
        BetterNetherCoreBiomes<String> picker = BetterNetherCoreBiomes.of(BIOMES, 11L);
        Set<Integer> cellsAtBorder = new HashSet<>();
        int border = 10 * BetterNetherCoreBiomes.CELL_BLOCKS;
        for (int z = -500; z <= 500; z += 3) {
            cellsAtBorder.add(picker.cellIndex(border, z));
            int deepInside = border + BetterNetherCoreBiomes.CELL_BLOCKS / 2;
            assertEquals(10, picker.cellIndex(deepInside, z), "cell centres must not move cells");
        }
        assertTrue(cellsAtBorder.size() > 1, "a border column should fall on both sides as Z varies");
    }
}
