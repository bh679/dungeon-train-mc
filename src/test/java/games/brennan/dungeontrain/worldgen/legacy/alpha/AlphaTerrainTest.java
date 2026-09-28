package games.brennan.dungeontrain.worldgen.legacy.alpha;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural invariants of the pure Alpha 1.1.2 generator port (normal and winter mode). */
final class AlphaTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final AlphaTerrain TERRAIN = new AlphaTerrain(SEED);
    private static final int SEA = BetaTerrain.SEA_LEVEL;

    private static byte get(byte[] blocks, int x, int y, int z) {
        return blocks[BetaTerrain.index(x, y, z)];
    }

    @Test
    @DisplayName("same seed + chunk ⇒ identical blocks, across instances and repeat calls")
    void deterministic() {
        byte[] a = TERRAIN.generate(3, -7, false);
        assertArrayEquals(a, new AlphaTerrain(SEED).generate(3, -7, false));
        assertArrayEquals(a, TERRAIN.generate(3, -7, false));
        assertFalse(Arrays.equals(a, new AlphaTerrain(SEED + 1).generate(3, -7, false)));
    }

    @Test
    @DisplayName("Alpha differs from Beta for the same seed")
    void notBeta() {
        assertFalse(Arrays.equals(TERRAIN.generate(0, 0, false), new BetaTerrain(SEED).generate(0, 0).blocks()));
    }

    @Test
    @DisplayName("a spread of chunks has grass land, sea, beaches, caves, bedrock — and no sandstone or ice")
    void plausibleWorld() {
        int land = 0;
        int grass = 0;
        int sea = 0;
        int sand = 0;
        int caveAir = 0;
        int bedrock = 0;
        int columns = 0;
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                byte[] c = TERRAIN.generate(cx, cz, false);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        columns++;
                        assertEquals(BetaBlocks.AIR, get(c, x, BetaTerrain.HEIGHT - 1, z));
                        int top = BetaTerrain.HEIGHT - 1;
                        while (top > 0 && get(c, x, top, z) == BetaBlocks.AIR) top--;
                        byte surface = get(c, x, top, z);
                        if (surface == BetaBlocks.WATER) sea++;
                        else land++;
                        if (surface == BetaBlocks.GRASS) grass++;
                        if (surface == BetaBlocks.SAND) sand++;
                        for (int y = 0; y < BetaTerrain.HEIGHT; y++) {
                            byte b = get(c, x, y, z);
                            assertNotEquals(BetaBlocks.SANDSTONE, b, "sandstone in Alpha");
                            assertNotEquals(BetaBlocks.ICE, b, "ice outside winter");
                            if (y < 5 && b == BetaBlocks.BEDROCK) bedrock++;
                            if (y > 0 && y < 40 && b == BetaBlocks.AIR) caveAir++;
                        }
                        assertTrue(top >= 20 && top < BetaTerrain.HEIGHT - 1, "surface at " + top);
                    }
                }
            }
        }
        assertTrue(land > columns / 10, "land " + land);
        assertTrue(grass > columns / 10, "grass " + grass);
        assertTrue(sea > 0, "no sea at all");
        assertTrue(sand > 0, "no beaches");
        assertTrue(caveAir > 0, "no caves");
        assertTrue(bedrock > columns, "bedrock " + bedrock);
    }

    @Test
    @DisplayName("winter: ice only on the top sea layer where the normal sea has water; land above sea level unchanged")
    void winterFreezesSea() {
        int frozen = 0;
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                byte[] normal = TERRAIN.generate(cx, cz, false);
                byte[] winter = TERRAIN.generate(cx, cz, true);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < BetaTerrain.HEIGHT; y++) {
                            byte n = get(normal, x, y, z);
                            byte w = get(winter, x, y, z);
                            if (w == BetaBlocks.ICE) {
                                assertEquals(SEA - 1, y, "ice below the sea surface");
                                assertEquals(BetaBlocks.WATER, n, "ice where the normal world has no sea");
                                frozen++;
                            } else if (y >= SEA) {
                                // Caves may differ below: Alpha's carver only avoided water, not ice.
                                assertEquals(n, w, "winter changed terrain above the sea at y " + y);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(frozen > 0, "no sea surface froze");
    }
}
