package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndBandStyleTest {

    @Test
    void oddBandsAreVanillaEvenBandsAreBetterEnd() {
        // Pass index is 0-based: band 1 = pass 0 (vanilla), band 2 = pass 1 (BetterEnd), …
        assertFalse(EndBandStyle.isBetterEndPass(0));
        assertTrue(EndBandStyle.isBetterEndPass(1));
        assertFalse(EndBandStyle.isBetterEndPass(2));
        assertTrue(EndBandStyle.isBetterEndPass(3));
        assertFalse(EndBandStyle.isBetterEndPass(4));
        assertTrue(EndBandStyle.isBetterEndPass(5));
    }

    @Test
    void beforeTheCycleIsNeverBetterEnd() {
        assertFalse(EndBandStyle.isBetterEndPass(-1));
        assertFalse(EndBandStyle.isBetterEndPass(Long.MIN_VALUE));
    }

    @Test
    void sampleXSweepsOutwardPerPass() {
        assertEquals(EndIslandGeometry.ISLAND_SAMPLE_OFFSET_X + 100, EndBandStyle.endSampleX(100, 0));
        assertEquals(EndIslandGeometry.ISLAND_SAMPLE_OFFSET_X + 100 + EndBandStyle.PASS_STEP_X,
                EndBandStyle.endSampleX(100, 1));
        assertEquals(EndBandStyle.endSampleX(100, 0), EndBandStyle.endSampleX(100, -1));
    }

    @Test
    void chunkOffsetIsExactSoEachDisplayChunkMapsToOneEndChunk() {
        for (long pass = 0; pass < 8; pass++) {
            assertEquals(0L, EndBandStyle.endSampleX(0, pass) % 16L, "pass " + pass);
            assertEquals(EndBandStyle.endSampleX(0, pass) / 16L, EndBandStyle.endChunkOffsetX(pass));
        }
    }

    @Test
    void displayAndEndYAreInverse() {
        int bedY = 71;
        assertEquals(bedY, EndBandStyle.displayY(EndIslandGeometry.END_ISLAND_CENTER_Y, bedY));
        for (int endY = 0; endY < 128; endY += 7) {
            assertEquals(endY, EndBandStyle.endY(EndBandStyle.displayY(endY, bedY), bedY));
        }
    }

    @Test
    void edgeFadeThinsSampledBlocks() {
        assertFalse(EndBandStyle.keepSampledBlock(0.0, 0.0));
        assertTrue(EndBandStyle.keepSampledBlock(1.0, 0.99));
        assertTrue(EndBandStyle.keepSampledBlock(0.5, 0.2));
        assertFalse(EndBandStyle.keepSampledBlock(0.5, 0.7));
    }
}
