package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link NetherStructureSiting} — where the band will and won't stand a Nether
 * structure, and how high. The cycle is built by hand (no config, no Nether dimension), so no NeoForge
 * bootstrap is needed; the same convention as {@code WorldGenCycleTest} and {@link EndCitySitingTest}.
 *
 * <p>Cycle geometry matches {@code WorldGenCycleTest}'s: anchor 1000, period 1940, whose Nether segment
 * (stageBlocks 40, megaHold 60, coreFade 50, coreHold 200) puts the real-Nether core at world X
 * [1530, 1730) — 1505 is still netherrack crossfade and 1380 is bare mountain.</p>
 */
final class NetherStructureSitingTest {

    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    private static final long SEED = 0x5EEDL;
    private static final int BED_Y = 64;

    /** Deep inside the Nether core — far enough from both edges that the edge wave cannot reach out. */
    private static final int CORE_X = 1630;
    /** Netherrack crossfade, not core. */
    private static final int CROSSFADE_X = 1505;
    /** Bare mountain stage, not core. */
    private static final int MOUNTAIN_X = 1380;
    /** Plain overworld between bands. */
    private static final int OVERWORLD_X = 2000;

    @Test
    @DisplayName("Nether-space heights land on the band where the core terrain put that slice of Nether")
    void bandYMapsNetherSpaceOntoTheBed() {
        // The Nether's own centre line is the track bed.
        assertEquals(BED_Y, NetherStructureSiting.bandY(BED_Y, NetherCoreGeometry.NETHER_CENTER_Y));
        // A fortress's vanilla 48..70 window, translated.
        assertEquals(BED_Y + 8, NetherStructureSiting.bandY(BED_Y, 48));
        assertEquals(BED_Y + 30, NetherStructureSiting.bandY(BED_Y, 70));
        // A bastion's vanilla 33..100 window, translated.
        assertEquals(BED_Y - 7, NetherStructureSiting.bandY(BED_Y, 33));
        assertEquals(BED_Y + 60, NetherStructureSiting.bandY(BED_Y, 100));
    }

    @Test
    @DisplayName("the core slab bounds match the geometry's sampled range, and clamping never leaves it")
    void clampKeepsPiecesInsideTheCoreSlab() {
        int min = NetherStructureSiting.minStructureY(BED_Y);
        int max = NetherStructureSiting.maxStructureY(BED_Y);

        // Exactly the slab NetherCoreGeometry samples — outside it there is no core terrain at all.
        assertEquals(BED_Y + (NetherCoreGeometry.NETHER_Y_SAMPLE_MIN - NetherCoreGeometry.NETHER_CENTER_Y), min);
        assertEquals(BED_Y + (NetherCoreGeometry.NETHER_Y_SAMPLE_MAX - NetherCoreGeometry.NETHER_CENTER_Y), max);
        assertTrue(min < BED_Y && BED_Y < max, "the train rides through the middle of the slab");

        assertEquals(min, NetherStructureSiting.clampToCore(BED_Y, min - 1000));
        assertEquals(max, NetherStructureSiting.clampToCore(BED_Y, max + 1000));
        assertEquals(BED_Y, NetherStructureSiting.clampToCore(BED_Y, BED_Y));
        assertEquals(min, NetherStructureSiting.clampToCore(BED_Y, min));
        assertEquals(max, NetherStructureSiting.clampToCore(BED_Y, max));
    }

    @Test
    @DisplayName("only real-Nether core columns accept a structure")
    void coreColumnsOnly() {
        for (int z = -64; z <= 64; z += 16) {
            assertTrue(NetherStructureSiting.isCoreColumn(CYCLE, SEED, CORE_X, z),
                    "core at z=" + z);
            assertFalse(NetherStructureSiting.isCoreColumn(CYCLE, SEED, OVERWORLD_X, z),
                    "plain overworld at z=" + z);
            assertFalse(NetherStructureSiting.isCoreColumn(CYCLE, SEED, MOUNTAIN_X, z),
                    "bare mountain at z=" + z);
            assertFalse(NetherStructureSiting.isCoreColumn(CYCLE, SEED, CROSSFADE_X, z),
                    "netherrack crossfade at z=" + z);
        }
        assertFalse(NetherStructureSiting.isCoreColumn(null, SEED, CORE_X, 0),
                "no cycle snapshot yet — decline rather than guess");
    }

    @Test
    @DisplayName("the column test follows the edge-waved X, not the raw X")
    void coreColumnFollowsTheEdgeWave() {
        // Sitting one block outside the core's trailing edge, the answer must track the wave: wherever the
        // wave pulls the front past this X the column IS core, and where it doesn't it is not. Testing the
        // raw X would give one constant answer for every Z.
        int edgeX = 1729;
        boolean sawCore = false;
        boolean sawNonCore = false;
        for (int z = -512; z <= 512 && !(sawCore && sawNonCore); z++) {
            int waved = NetherMountainTerrain.wavyX(SEED, edgeX, z);
            boolean expected = CYCLE.isNetherCore(waved);
            assertEquals(expected, NetherStructureSiting.isCoreColumn(CYCLE, SEED, edgeX, z),
                    "z=" + z + " waved to " + waved);
            sawCore |= expected;
            sawNonCore |= !expected;
        }
        assertTrue(sawCore && sawNonCore, "the chosen X must straddle the wave to prove it is consulted");
    }

    @Test
    @DisplayName("a footprint must be core a wave-margin past both ends, so no structure straddles the edge")
    void footprintNeedsAWaveMargin() {
        int margin = NetherMountainTerrain.maxEdgeShift();

        assertTrue(NetherStructureSiting.isCoreFootprint(CYCLE, CORE_X, CORE_X + 32),
                "well inside the core");
        assertFalse(NetherStructureSiting.isCoreFootprint(CYCLE, OVERWORLD_X, OVERWORLD_X + 32),
                "plain overworld");
        assertFalse(NetherStructureSiting.isCoreFootprint(CYCLE, CROSSFADE_X, CORE_X),
                "a span reaching back into the crossfade");

        // The core runs [1530, 1730): a footprint ending at the last core block is still rejected, because
        // the wave could pull the front inside it. Backing off by the margin makes it acceptable.
        assertFalse(NetherStructureSiting.isCoreFootprint(CYCLE, CORE_X, 1729),
                "flush against the trailing edge — the wave could cut it");
        assertTrue(NetherStructureSiting.isCoreFootprint(CYCLE, CORE_X, 1729 - margin),
                "backed off by the wave margin");

        assertFalse(NetherStructureSiting.isCoreFootprint(null, CORE_X, CORE_X + 32),
                "no cycle snapshot yet — decline rather than guess");
    }

    @Test
    @DisplayName("the End band wins any overlap, and is silent where it has none")
    void endBandWinsOverlapOnly() {
        // The End segment of this cycle is nowhere near the Nether core, so the yield must not bite here.
        assertEquals(0.0, CYCLE.endMiddleRamp(CORE_X), 1e-9, "no End influence in the Nether core");
        assertTrue(NetherStructureSiting.isCoreColumn(CYCLE, SEED, CORE_X, 0));
        assertTrue(NetherStructureSiting.isCoreFootprint(CYCLE, CORE_X, CORE_X + 32));
    }
}
