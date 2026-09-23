package games.brennan.dungeontrain.worldgen.legacy.beta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural invariants of the pure Beta 1.7.3 generator port. */
final class BetaTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final BetaTerrain TERRAIN = new BetaTerrain(SEED);

    @Test
    @DisplayName("same seed + chunk ⇒ identical blocks, across instances and repeat calls")
    void deterministic() {
        BetaChunk a = TERRAIN.generate(3, -7);
        BetaChunk b = new BetaTerrain(SEED).generate(3, -7);
        assertArrayEquals(a.blocks(), b.blocks());
        assertArrayEquals(a.blocks(), TERRAIN.generate(3, -7).blocks());
        assertFalse(Arrays.equals(a.blocks(), new BetaTerrain(SEED + 1).generate(3, -7).blocks()));
    }

    @Test
    @DisplayName("bottom rows are bedrock-floored, top rows are open sky")
    void floorAndCeiling() {
        for (int cx = -4; cx < 4; cx++) {
            BetaChunk c = TERRAIN.generate(cx, cx * 3);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(BetaBlocks.BEDROCK, c.get(x, 0, z));
                    assertEquals(BetaBlocks.AIR, c.get(x, BetaTerrain.HEIGHT - 1, z));
                }
            }
        }
    }

    @Test
    @DisplayName("a spread of chunks produces land, sea, beaches and caves in plausible proportions")
    void plausibleWorld() {
        int land = 0;
        int sea = 0;
        int sand = 0;
        int caveAir = 0;
        int columns = 0;
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                BetaChunk c = TERRAIN.generate(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        columns++;
                        int top = BetaTerrain.HEIGHT - 1;
                        while (top > 0 && c.get(x, top, z) == BetaBlocks.AIR) top--;
                        byte surface = c.get(x, top, z);
                        if (surface == BetaBlocks.WATER || surface == BetaBlocks.ICE) sea++;
                        else land++;
                        if (surface == BetaBlocks.SAND) sand++;
                        for (int y = 1; y < 40; y++) {
                            if (c.get(x, y, z) == BetaBlocks.AIR) caveAir++;
                        }
                        assertTrue(top >= 20 && top < BetaTerrain.HEIGHT - 1, "surface at " + top);
                    }
                }
            }
        }
        assertTrue(land > columns / 10, "land " + land);
        assertTrue(sea > 0, "no sea at all");
        assertTrue(sand > 0, "no beaches or desert");
        assertTrue(caveAir > 0, "no caves");
    }

    @Test
    @DisplayName("climate stays in [0,1] and maps onto Beta's biome table")
    void climateRange() {
        BetaClimate climate = TERRAIN.climate();
        for (int i = -2000; i < 2000; i += 37) {
            double[] c = climate.sample(i, i * 3 - 500);
            assertTrue(c[0] >= 0.0 && c[0] <= 1.0);
            assertTrue(c[1] >= 0.0 && c[1] <= 1.0);
        }
        assertEquals(BetaBiome.ICE_DESERT, BetaBiome.of(0.05, 0.5));
        assertEquals(BetaBiome.DESERT, BetaBiome.of(1.0, 0.05));
        assertEquals(BetaBiome.RAINFOREST, BetaBiome.of(1.0, 1.0));
    }
}
