package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstellationCoreTest {

    @Test
    void solidCoreGrowsToCoverHiddenBlocksThenFeathers() {
        float core = 1.5f;
        assertEquals(1f, ConstellationEdge.alphaAt(1.49f, true, core));
        assertEquals(0.5f, ConstellationEdge.alphaAt(core + ConstellationEdge.FEATHER_WIDTH / 2f, true, core), 1e-4f);
        assertEquals(0f, ConstellationEdge.alphaAt(core + ConstellationEdge.FEATHER_WIDTH, true, core));
    }

    @Test
    void hardRimIsTheCoreOnceItPassesTheUnitDisc() {
        assertEquals(1f, ConstellationEdge.rim(false, 0.72f));
        assertEquals(1.8f, ConstellationEdge.rim(false, 1.8f));
        assertEquals(1f, ConstellationEdge.alphaAt(1.7f, false, 1.8f));
    }

    @Test
    void coreTargetNeverShrinksBelowTheFeatherStartOrPastTheCap() {
        assertEquals(ConstellationEdge.FEATHER_START, ConstellationSky.coreTarget(0f));
        assertEquals(1.3f, ConstellationSky.coreTarget(1.3f));
        assertEquals(ConstellationOcclusion.MAX_REACH, ConstellationSky.coreTarget(9f));
    }

    @Test
    void ringsAreClippedAtThePicturesEdge() {
        assertArrayEquals(new float[] {0f, 0.5f, 0.9f}, ConstellationSky.clipRadii(new float[] {0f, 0.5f, 1f, 1.2f}, 0.9f));
        assertArrayEquals(new float[] {0f, 0.5f}, ConstellationSky.clipRadii(new float[] {0f, 0.5f}, 2f));
    }
}
