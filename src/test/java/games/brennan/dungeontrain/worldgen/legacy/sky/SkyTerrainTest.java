package games.brennan.dungeontrain.worldgen.legacy.sky;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBiome;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaChunk;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural invariants of the pure Beta 1.7.3 Sky generator port. */
final class SkyTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final SkyTerrain TERRAIN = new SkyTerrain(SEED);
    /** Lowest old Y land may form at — mirrors {@code LegacyBands.SKY_LOWEST_LAND_OLD_Y}. */
    private static final int LOWEST_LAND_OLD_Y = 16;

    @Test
    @DisplayName("same seed + chunk ⇒ identical blocks, across instances and repeat calls")
    void deterministic() {
        BetaChunk a = TERRAIN.generate(3, -7);
        BetaChunk b = new SkyTerrain(SEED).generate(3, -7);
        assertArrayEquals(a.blocks(), b.blocks());
        assertArrayEquals(a.blocks(), TERRAIN.generate(3, -7).blocks());
        assertFalse(Arrays.equals(a.blocks(), new SkyTerrain(SEED + 1).generate(3, -7).blocks()));
    }

    @Test
    @DisplayName("islands over void: no bedrock, water, sand or gravel; no land low in the column; sky biome")
    void islandsOverVoid() {
        int emptyChunks = 0;
        int landChunks = 0;
        int grassTops = 0;
        int chunks = 0;
        for (int cx = -10; cx < 10; cx++) {
            for (int cz = -10; cz < 10; cz++) {
                BetaChunk c = TERRAIN.generate(cx, cz);
                chunks++;
                boolean any = false;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        assertEquals(BetaBiome.SKY, c.biome(x, z));
                        int top = -1;
                        for (int y = 0; y < BetaTerrain.HEIGHT; y++) {
                            byte b = c.get(x, y, z);
                            if (b == BetaBlocks.AIR) continue;
                            any = true;
                            top = y;
                            assertTrue(y >= LOWEST_LAND_OLD_Y, "land at old y " + y);
                            assertFalse(b == BetaBlocks.BEDROCK || b == BetaBlocks.WATER || b == BetaBlocks.SAND
                                    || b == BetaBlocks.GRAVEL || b == BetaBlocks.ICE, "unexpected block " + b);
                        }
                        if (top >= 0 && c.get(x, top, z) == BetaBlocks.GRASS) grassTops++;
                    }
                }
                if (any) landChunks++;
                else emptyChunks++;
            }
        }
        assertTrue(emptyChunks > chunks / 5, "too few open-void chunks: " + emptyChunks + "/" + chunks);
        assertTrue(landChunks > chunks / 5, "too few island chunks: " + landChunks + "/" + chunks);
        assertTrue(grassTops > 0, "islands have no grass");
    }

    @Test
    @DisplayName("slides pull the column's top and bottom samples to the void target")
    void slides() {
        assertEquals(-30.0, SkyTerrain.slide(100.0, 32), 1e-9);
        assertTrue(SkyTerrain.slide(100.0, 0) <= -30.0 + 1e-9);
        assertTrue(SkyTerrain.slide(100.0, 8) > 0.0);
    }
}
