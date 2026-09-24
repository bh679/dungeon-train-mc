package games.brennan.dungeontrain.worldgen.legacy.chaos;

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

/** The Caves of Chaos profile on the Beta pipeline: a 256-block column, sea at 6, stone high overhead. */
final class CavesOfChaosTerrainTest {

    private static final long SEED = 0xC4A05L;
    private static final BetaTerrain.Profile PROFILE = BetaTerrain.Profile.CAVES_OF_CHAOS;
    private static final BetaTerrain TERRAIN = new BetaTerrain(SEED, PROFILE);

    @Test
    @DisplayName("the profile is Moderner Beta's Beta Caves of Chaos preset")
    void profile() {
        assertEquals(256, PROFILE.height());
        assertEquals(6, PROFILE.seaLevel());
        assertEquals(64.0D, PROFILE.lowerLimitScale());
        assertEquals(2.0D, PROFILE.upperLimitScale());
        assertEquals(8.0D, PROFILE.stretchY());
        assertEquals(256, TERRAIN.height());
        assertEquals(6, TERRAIN.seaLevel());
    }

    @Test
    @DisplayName("deterministic per seed, and not the Beta column")
    void deterministic() {
        BetaChunk a = TERRAIN.generate(3, -7);
        BetaChunk b = new BetaTerrain(SEED, PROFILE).generate(3, -7);
        assertArrayEquals(a.blocks(), b.blocks());
        assertEquals(16 * 16 * 256, a.blocks().length);
        assertEquals(256, a.height());
        assertFalse(Arrays.equals(a.blocks(), new BetaTerrain(SEED + 1, PROFILE).generate(3, -7).blocks()));
        assertFalse(Arrays.equals(Arrays.copyOf(a.blocks(), 16 * 16 * 128), new BetaTerrain(SEED).generate(3, -7).blocks()));
    }

    @Test
    @DisplayName("bedrock at the bottom, water only under the low sea, stone far above Beta's ceiling")
    void column() {
        boolean stoneAbove128 = false;
        int columns = 0;
        int landColumns = 0;
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                BetaChunk c = TERRAIN.generate(cx, cz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        columns++;
                        assertEquals(BetaBlocks.BEDROCK, c.get(x, 0, z));
                        // No air-at-the-top check: like the original preset (MC-71084), the terrain runs into the ceiling.
                        boolean land = false;
                        for (int y = 0; y < 256; y++) {
                            byte b = c.get(x, y, z);
                            if (b == BetaBlocks.WATER || b == BetaBlocks.ICE) {
                                assertTrue(y < PROFILE.seaLevel(), "water at " + y);
                            }
                            if (y >= 128 && b == BetaBlocks.STONE) stoneAbove128 = true;
                            if (y >= PROFILE.seaLevel() && (b == BetaBlocks.STONE || b == BetaBlocks.GRASS || b == BetaBlocks.DIRT)) land = true;
                        }
                        if (land) landColumns++;
                    }
                }
            }
        }
        assertTrue(stoneAbove128, "no stone above y 128: the tall column is not being filled");
        assertTrue(landColumns > columns / 2, "mostly land expected, got " + landColumns + "/" + columns);
    }
}
