package games.brennan.dungeontrain.worldgen.legacy.infdev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural invariants of the pure Infdev generator ports. */
final class InfdevTerrainTest {

    private static final long SEED = 3257840388504953787L;
    private static final InfdevTerrain TERRAIN = new InfdevTerrain(SEED);
    /** A chunk row far enough from the track that no pyramid reaches it. */
    private static final int FAR_CHUNK_Z = 40;

    private static byte get(byte[] blocks, int x, int y, int z) {
        return blocks[BetaTerrain.index(x, y, z)];
    }

    @ParameterizedTest
    @EnumSource(InfdevVersion.class)
    @DisplayName("same seed + chunk ⇒ identical blocks; a different seed differs")
    void deterministic(InfdevVersion v) {
        byte[] a = TERRAIN.generate(3, -7, v);
        assertArrayEquals(a, new InfdevTerrain(SEED).generate(3, -7, v));
        assertArrayEquals(a, TERRAIN.generate(3, -7, v));
        assertFalse(Arrays.equals(a, new InfdevTerrain(SEED + 1).generate(3, -7, v)));
    }

    @ParameterizedTest
    @EnumSource(InfdevVersion.class)
    @DisplayName("bedrock floor, open sky at the top, land and sea with water topping out at old y 63")
    void plausibleWorld(InfdevVersion v) {
        int land = 0;
        int sea = 0;
        int columns = 0;
        // A sparse spread: each version's land and oceans run hundreds of blocks across.
        for (int cx = -40; cx < 40; cx += 6) {
            for (int cz = FAR_CHUNK_Z; cz < FAR_CHUNK_Z + 80; cz += 6) {
                byte[] c = TERRAIN.generate(cx, cz, v);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        columns++;
                        assertEquals(BetaBlocks.BEDROCK, get(c, x, 0, z));
                        assertEquals(BetaBlocks.AIR, get(c, x, BetaTerrain.HEIGHT - 1, z));
                        int top = BetaTerrain.HEIGHT - 1;
                        while (top > 0 && get(c, x, top, z) == BetaBlocks.AIR) top--;
                        if (get(c, x, top, z) == BetaBlocks.WATER) {
                            sea++;
                            assertEquals(63, top, v + " water surface");
                        } else {
                            land++;
                        }
                    }
                }
            }
        }
        assertTrue(land > columns / 10, v + " land " + land);
        assertTrue(sea > 0, v + " has no sea");
    }

    @Test
    @DisplayName("caves only from 20100420 on")
    void caves() {
        assertEquals(0, caveAir(InfdevVersion.V227));
        assertEquals(0, caveAir(InfdevVersion.V415));
        assertTrue(caveAir(InfdevVersion.V420) > 0);
        assertTrue(caveAir(InfdevVersion.V611) > 0);
    }

    /** Air cells enclosed below the old y-30 line (the density terrain is solid there without caves). */
    private static int caveAir(InfdevVersion v) {
        int air = 0;
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                byte[] c = TERRAIN.generate(cx, cz + FAR_CHUNK_Z, v);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 1; y < 30; y++) {
                            if (get(c, x, y, z) == BetaBlocks.AIR) air++;
                        }
                    }
                }
            }
        }
        return air;
    }

    @Test
    @DisplayName("227: an obsidian wall on every 1024-block boundary, two blocks above the ground")
    void wall() {
        for (int region = -2; region <= 3; region++) {
            int chunkX = region * (Infdev227Terrain.REGION / 16);
            byte[] c = TERRAIN.generate(chunkX, FAR_CHUNK_Z, InfdevVersion.V227);
            Infdev227Terrain t = TERRAIN.v227();
            for (int z = 0; z < 16; z++) {
                int height = t.height(chunkX << 4, (FAR_CHUNK_Z << 4) + z);
                assertEquals(BetaBlocks.OBSIDIAN, get(c, 0, height + 1, z), "wall top at region " + region);
                assertTrue(get(c, 0, height + 2, z) != BetaBlocks.OBSIDIAN);
                for (int y = 0; y < BetaTerrain.HEIGHT; y++) {
                    assertTrue(get(c, 1, y, z) != BetaBlocks.OBSIDIAN, "wall is one block thick");
                }
            }
            assertFalse(contains(TERRAIN.generate(chunkX, FAR_CHUNK_Z, InfdevVersion.V415), BetaBlocks.OBSIDIAN));
        }
    }

    @Test
    @DisplayName("227: every region's brick pyramid peaks near the track, 40–200 blocks either side")
    void pyramids() {
        Infdev227Terrain t = TERRAIN.v227();
        for (int region = -3; region <= 3; region++) {
            Infdev227Terrain.Pyramid p = t.pyramid(region);
            int dz = Math.abs(p.z());
            assertTrue(dz >= Infdev227Terrain.PYRAMID_MIN_DIST && dz < Infdev227Terrain.PYRAMID_MAX_DIST, "z " + p.z());
            assertEquals(region, Math.floorDiv(p.x(), Infdev227Terrain.REGION));
            byte[] c = TERRAIN.generate(p.x() >> 4, p.z() >> 4, InfdevVersion.V227);
            int lx = p.x() & 15;
            int lz = p.z() & 15;
            assertEquals(BetaBlocks.BRICKS, get(c, lx, Infdev227Terrain.PYRAMID_APEX - 1, lz), "apex at region " + region);
            assertEquals(BetaBlocks.AIR, get(c, lx, Infdev227Terrain.PYRAMID_APEX, lz));
        }
        assertFalse(contains(TERRAIN.generate(0, FAR_CHUNK_Z, InfdevVersion.V227), BetaBlocks.BRICKS));
    }

    private static boolean contains(byte[] blocks, byte id) {
        for (byte b : blocks) if (b == id) return true;
        return false;
    }

    @Test
    @DisplayName("versions run newest first, 20/20/20/40; out-of-range progress clamps to the ends")
    void versionSplit() {
        assertEquals(InfdevVersion.V611, InfdevVersion.at(-1.0));
        assertEquals(InfdevVersion.V611, InfdevVersion.at(0.0));
        assertEquals(InfdevVersion.V611, InfdevVersion.at(0.199));
        assertEquals(InfdevVersion.V420, InfdevVersion.at(0.2));
        assertEquals(InfdevVersion.V415, InfdevVersion.at(0.4));
        assertEquals(InfdevVersion.V227, InfdevVersion.at(0.6));
        assertEquals(InfdevVersion.V227, InfdevVersion.at(2.0));
    }

    @Test
    @DisplayName("tree counts are never negative, and forests exist")
    void treeCounts() {
        for (InfdevVersion v : InfdevVersion.values()) {
            int total = 0;
            for (int cx = -20; cx < 20; cx++) {
                int n = InfdevPopulator.treeCount(TERRAIN, v, cx, cx * 7, new Random(cx));
                assertTrue(n >= 0);
                total += n;
            }
            if (v.isDensity()) assertTrue(total > 0, v + " grew no trees");
        }
    }
}
