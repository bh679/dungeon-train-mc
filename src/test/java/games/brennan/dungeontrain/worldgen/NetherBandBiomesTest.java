package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cave palettes: the fall side is mostly deep dark in large regions; the rise side never is. */
final class NetherBandBiomesTest {

    @Test
    @DisplayName("post-core: deep dark covers ~75 % in ≥512-block runs; lush/dripstone fill the rest")
    void postCoreMostlyDeepDark() {
        int deepDark = NetherBandBiomes.CAVE_POST.indexOf(Biomes.DEEP_DARK);
        assertTrue(deepDark >= 0);
        int total = 0, dark = 0, lush = 0, drip = 0;
        for (long seed : new long[] {1L, 0xBEEFL, 0x1234_5678L}) {
            for (int z = 0; z < 512; z += 64) {
                int runStart = 0; int prev = -1;
                for (int x = 0; x < 65536; x += 16) {
                    int pick = NetherBandBiomes.pickCavePost(seed, x, z);
                    total++;
                    if (pick == deepDark) dark++;
                    else if (NetherBandBiomes.CAVE_POST.get(pick) == Biomes.LUSH_CAVES) lush++;
                    else drip++;
                    // a deep-dark run, once started, is never shorter than one coarse region
                    boolean isDark = pick == deepDark;
                    if (prev == 1 && !isDark) assertTrue(x - runStart >= (1 << NetherBandBiomes.DEEP_DARK_REGION_SHIFT),
                            "short deep-dark run at x=" + x + " seed=" + seed);
                    if (!isDark || prev != 1) runStart = x;
                    prev = isDark ? 1 : 0;
                }
            }
        }
        double share = dark / (double) total;
        assertTrue(share > 0.65 && share < 0.85, "deep-dark share " + share);
        assertTrue(lush > 0 && drip > 0, "lush and dripstone must still appear");
    }

    @Test
    @DisplayName("pre-core: never deep dark, both lush and dripstone occur")
    void preCoreNeverDeepDark() {
        boolean lush = false, drip = false;
        for (int x = 0; x < 8192; x += 32) {
            int pick = NetherBandBiomes.pickCave(7L, x, 40, NetherBandBiomes.CAVE_PRE.size());
            var b = NetherBandBiomes.CAVE_PRE.get(pick);
            assertTrue(b != Biomes.DEEP_DARK);
            lush |= b == Biomes.LUSH_CAVES; drip |= b == Biomes.DRIPSTONE_CAVES;
        }
        assertTrue(lush && drip);
        assertEquals(2, NetherBandBiomes.CAVE_PRE.size());
    }
}
