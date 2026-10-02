package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cave palettes: the fall side is mostly deep dark and sulfur in large regions; the rise side never is. */
final class NetherBandBiomesTest {

    @Test
    @DisplayName("post-core: deep dark ~50 %, sulfur ~30 %, both in ≥512-block runs; lush/dripstone fill the rest")
    void postCoreDeepDarkAndSulfur() {
        int total = 0, dark = 0, sulfur = 0, lush = 0, drip = 0;
        for (long seed : new long[] {1L, 0xBEEFL, 0x1234_5678L}) {
            for (int z = 0; z < 512; z += 64) {
                int runStart = 0; int prev = -1;
                for (int x = 0; x < 65536; x += 16) {
                    int pick = NetherBandBiomes.pickCavePost(seed, x, z, true);
                    total++;
                    var biome = NetherBandBiomes.CAVE_POST.get(pick);
                    if (biome == Biomes.DEEP_DARK) dark++;
                    else if (biome == NetherBandBiomes.SULFUR_CAVES) sulfur++;
                    else if (biome == Biomes.LUSH_CAVES) lush++;
                    else drip++;
                    // a deep-dark or sulfur run, once started, is never shorter than one coarse region
                    if (prev >= 0 && prev != pick && prev >= NetherBandBiomes.CAVE_POST_DEEP_DARK) {
                        assertTrue(x - runStart >= (1 << NetherBandBiomes.DEEP_DARK_REGION_SHIFT),
                                "short run of " + NetherBandBiomes.CAVE_POST.get(prev) + " at x=" + x + " seed=" + seed);
                    }
                    if (pick != prev) runStart = x;
                    prev = pick;
                }
            }
        }
        double darkShare = dark / (double) total, sulfurShare = sulfur / (double) total;
        double otherShare = (lush + drip) / (double) total;
        assertTrue(darkShare > 0.43 && darkShare < 0.57, "deep-dark share " + darkShare);
        assertTrue(sulfurShare > 0.23 && sulfurShare < 0.37, "sulfur share " + sulfurShare);
        assertTrue(otherShare > 0.13 && otherShare < 0.27, "lush/dripstone share " + otherShare);
        assertTrue(lush > 0 && drip > 0, "lush and dripstone must still appear");
    }

    @Test
    @DisplayName("post-core without sulfur caves: the original 3-in-4 deep-dark roll, pick for pick")
    void postCoreWithoutSulfurIsTheOriginalRoll() {
        for (long seed : new long[] {1L, 0xBEEFL, 0x1234_5678L, -42L}) {
            for (int z = -512; z < 512; z += 96) {
                for (int x = -40000; x < 40000; x += 37) {
                    int got = NetherBandBiomes.pickCavePost(seed, x, z, false);
                    assertTrue(got != NetherBandBiomes.CAVE_POST_SULFUR, "no sulfur without the biome");
                    assertEquals(originalPickCavePost(seed, x, z), got, "x=" + x + " z=" + z + " seed=" + seed);
                }
            }
        }
    }

    /** The fall-side pick as it was before sulfur caves joined — the fallback must reproduce it exactly. */
    private static int originalPickCavePost(long seed, int worldX, int worldZ) {
        int coarse = NetherBandBiomes.pickWithinRegion(seed ^ 0x1B873593CC9E2D51L, worldX, worldZ, 4,
                NetherBandBiomes.DEEP_DARK_REGION_SHIFT);
        if (coarse < 3) return 2;
        return NetherBandBiomes.pickCave(seed, worldX, worldZ, 2);
    }

    @Test
    @DisplayName("pre-core: never deep dark or sulfur, both lush and dripstone occur")
    void preCoreNeverDeepDark() {
        boolean lush = false, drip = false;
        for (int x = 0; x < 8192; x += 32) {
            int pick = NetherBandBiomes.pickCave(7L, x, 40, NetherBandBiomes.CAVE_PRE.size());
            var b = NetherBandBiomes.CAVE_PRE.get(pick);
            assertTrue(b != Biomes.DEEP_DARK && b != NetherBandBiomes.SULFUR_CAVES);
            lush |= b == Biomes.LUSH_CAVES; drip |= b == Biomes.DRIPSTONE_CAVES;
        }
        assertTrue(lush && drip);
        assertEquals(2, NetherBandBiomes.CAVE_PRE.size());
    }
}
