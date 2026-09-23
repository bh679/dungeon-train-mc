package games.brennan.dungeontrain.worldgen.legacy.farlands;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaChunk;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Far Lands come out of the unmodified Beta port once it is sampled past {@link FarLandsShift#EDGE}:
 * these pin the overflow that makes them and the tall, broken terrain it produces.
 */
final class FarLandsTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final BetaTerrain TERRAIN = new BetaTerrain(SEED);
    private static final int EDGE_CHUNK = FarLandsShift.EDGE >> 4;

    @Test
    @DisplayName("the old floor saturates at the int range, as Beta's (int) cast did")
    void floorSaturates() {
        assertEquals(Integer.MAX_VALUE, LegacyMath.floor(3.0e9));
        assertEquals(Integer.MAX_VALUE, LegacyMath.floor(12_550_900 * 171.103));
        // Negative side: the cast saturates at MIN_VALUE and Beta's step-down wraps it to MAX_VALUE.
        assertEquals(Integer.MAX_VALUE, LegacyMath.floor(-3.0e9));
        assertEquals(-2, LegacyMath.floor(-1.5));
    }

    @Test
    @DisplayName("Far Lands chunks are deterministic per seed")
    void deterministic() {
        BetaChunk a = TERRAIN.generate(EDGE_CHUNK + 9, 3);
        assertArrayEquals(a.blocks(), new BetaTerrain(SEED).generate(EDGE_CHUNK + 9, 3).blocks());
    }

    @Test
    @DisplayName("before the edge the land is ordinary Beta; past it the terrain towers to the ceiling")
    void wall() {
        double before = highShare(EDGE_CHUNK - 40, EDGE_CHUNK - 24, 0);
        double edge = highShare(EDGE_CHUNK + 4, EDGE_CHUNK + 20, 0);
        assertTrue(before < 0.05, "ordinary land reaches y≥110 in " + before);
        assertTrue(edge > 0.5, "edge Far Lands reach y≥110 in only " + edge);
    }

    @Test
    @DisplayName("the edge lands are riddled with open space, not a solid block")
    void edgeIsHollowed() {
        int air = 0;
        int total = 0;
        for (int cx = EDGE_CHUNK + 4; cx < EDGE_CHUNK + 12; cx++) {
            BetaChunk c = TERRAIN.generate(cx, 0);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 40; y < 120; y++) {
                        total++;
                        if (c.get(x, y, z) == BetaBlocks.AIR) air++;
                    }
                }
            }
        }
        double share = (double) air / total;
        assertTrue(share > 0.1 && share < 0.9, "air share " + share);
    }

    @Test
    @DisplayName("the negative edge breaks the same way: ordinary land inside it, towering land past it")
    void negativeEdge() {
        assertTrue(highShareZ(-EDGE_CHUNK + 24, -EDGE_CHUNK + 40) < 0.05);
        assertTrue(highShareZ(-EDGE_CHUNK - 20, -EDGE_CHUNK - 4) > 0.5);
    }

    /** {@link #highShare} along Z at chunk X 0: chunks {@code {0} × [fromCz, toCz)}. */
    private static double highShareZ(int fromCz, int toCz) {
        double total = 0;
        for (int cz = fromCz; cz < toCz; cz++) total += highShare(0, 1, cz);
        return total / (toCz - fromCz);
    }

    @Test
    @DisplayName("the corner lands (both axes overflowed) also tower")
    void corner() {
        assertTrue(highShare(EDGE_CHUNK + 4, EDGE_CHUNK + 12, EDGE_CHUNK + 4) > 0.5);
    }

    /** Share of columns in chunks {@code [fromCx, toCx) × {cz}} with ground (not air or water) at or above y 110. */
    private static double highShare(int fromCx, int toCx, int cz) {
        int high = 0;
        int columns = 0;
        for (int cx = fromCx; cx < toCx; cx++) {
            BetaChunk c = TERRAIN.generate(cx, cz);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    columns++;
                    for (int y = 110; y < BetaTerrain.HEIGHT; y++) {
                        byte b = c.get(x, y, z);
                        if (b != BetaBlocks.AIR && b != BetaBlocks.WATER) {
                            high++;
                            break;
                        }
                    }
                }
            }
        }
        return (double) high / columns;
    }
}
