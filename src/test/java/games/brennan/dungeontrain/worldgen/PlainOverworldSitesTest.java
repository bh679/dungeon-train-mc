package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import games.brennan.dungeontrain.portal.StretchSites;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld.Stretch;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain overworld dimensional carriage's site walk ({@link PortalChunkTerrain#plainOverworldSite}).
 *
 * <p>Its sites are scattered across the whole reach, and under the shipped cycle only a few percent of
 * that is vanilla overworld — so about a third of pairs ran out of sites before generating a single
 * chunk, and Test the Carriage (always the same pair) failed every time in an unlucky world with
 * "Couldn't find ground to sample". The retry pass inside the vanilla gaps must always find one.</p>
 */
final class PlainOverworldSitesTest {

    private static final long START = 10_000L;
    private static final WorldGenCycle SHIPPED = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), CycleLayoutTest.shipped(), 0);

    private static final long[] SEEDS = {-60_849_081_470_745_298L, 0L, 12_345L, 987_654_321_987L};
    private static final int PAIRS = 2000;

    @Test
    @DisplayName("the scattered pass alone runs out for many pairs — the failure the retry exists for")
    void scatteredPassRunsOut() {
        int exhausted = 0;
        for (long seed : SEEDS) {
            for (int key = 0; key < PAIRS; key++) {
                if (PortalChunkTerrain.plainOverworldSite(SHIPPED, seed, key, 0, false) == null) exhausted++;
            }
        }
        assertTrue(exhausted > 0, "the shipped cycle no longer starves the scattered pass; revisit this test");
    }

    @Test
    @DisplayName("with the retry, every pair finds a vanilla site, rolls included")
    void everyPairFindsASite() {
        for (long seed : SEEDS) {
            for (int key = 0; key < PAIRS; key++) {
                for (int roll = 0; roll < 3; roll++) {
                    ChunkPos site = PortalChunkTerrain.plainOverworldSite(SHIPPED, seed, key, roll, true);
                    assertNotNull(site, "seed " + seed + " pair " + key + " roll " + roll + " ran out of sites");
                    assertTrue(StretchSites.matches(SHIPPED, Stretch.VANILLA, site.getMinBlockX()),
                            "site " + site + " is not vanilla overworld");
                }
            }
        }
    }

    @Test
    @DisplayName("a pair the scattered pass already served keeps exactly the same site")
    void servedPairsDoNotMove() {
        for (long seed : SEEDS) {
            for (int key = 0; key < PAIRS; key++) {
                ChunkPos scattered = PortalChunkTerrain.plainOverworldSite(SHIPPED, seed, key, 0, false);
                if (scattered == null) continue;
                assertEquals(scattered, PortalChunkTerrain.plainOverworldSite(SHIPPED, seed, key, 0, true));
            }
        }
    }
}
