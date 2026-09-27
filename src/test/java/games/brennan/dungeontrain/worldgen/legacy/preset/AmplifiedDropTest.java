package games.brennan.dungeontrain.worldgen.legacy.preset;

import games.brennan.dungeontrain.portal.PortalTwinRegion;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sunk Amplified band's geometry: how far it drops, where its noise window sits, and the promise
 * that the attic its twins move to is at least as roomy as the basement its terrain takes over.
 *
 * <p>No NeoForge bootstrap — the maths is pure, as {@code PortalTwinRegionTest} does it.</p>
 */
final class AmplifiedDropTest {

    /** The default DT overworld: dimension floor at -48, terrain floor at 32, build ceiling 320. */
    private static final int MIN_Y = -48;
    private static final int BEDROCK_Y = 32;
    private static final int MAX_Y = 320;
    private static final int MARGIN = PortalTwinSpace.CEILING_MARGIN;

    /** Stock noise window: y 32 → 320. */
    private static final int BASE_HEIGHT = 288;

    private static AmplifiedDrop stock() {
        return AmplifiedDrop.compute(BEDROCK_Y, MIN_Y, MAX_Y, MARGIN);
    }

    @Test
    @DisplayName("stock world: drops the full 80, floor lands on the build floor")
    void stockDropsEighty() {
        AmplifiedDrop d = stock();
        assertTrue(d.active());
        assertEquals(80, d.drop());
        assertEquals(MIN_Y, d.floorY(BEDROCK_Y));
        assertEquals(63 - 80, d.seaLevel(63));
    }

    @Test
    @DisplayName("noise window is section-aligned, sunk, and capped under the lid (no more work than stock)")
    void noiseWindow() {
        AmplifiedDrop d = stock();
        int min = d.noiseMinY(BEDROCK_Y);
        int height = d.noiseHeight(BEDROCK_Y, BASE_HEIGHT);
        assertEquals(-48, min);
        assertEquals(0, Math.floorMod(min, 16));
        assertEquals(0, height % 16);
        assertTrue(min + height <= d.lidY(), "terrain can never reach the lid");
        assertTrue(height <= BASE_HEIGHT, "sinking must not widen the noise window");
    }

    @Test
    @DisplayName("the attic over the lid is at least as deep as the basement it replaces")
    void atticAtLeastBasement() {
        AmplifiedDrop d = stock();
        PortalTwinRegion attic = PortalTwinSpace.amplifiedAttic(d, MAX_Y);
        PortalTwinRegion basement = PortalTwinRegion.basement(MIN_Y, BEDROCK_Y);
        assertEquals(224, d.lidY());
        assertTrue(attic.ceiling() - attic.base() >= basement.ceiling() - basement.base());
        for (int h = 1; h < 70; h++) {
            if (basement.canHold(h)) assertTrue(attic.canHold(h), "attic refuses a height the basement holds: " + h);
        }
    }

    @Test
    @DisplayName("in the slot, twin space is the attic — the sunk valleys are not a portal room")
    void twinSpaceInSlot() {
        AmplifiedDrop d = stock();
        assertFalse(PortalTwinSpace.amplifiedTwinSpaceContains(0, d, BEDROCK_Y, MIN_Y, MAX_Y));
        assertFalse(PortalTwinSpace.amplifiedTwinSpaceContains(-40, d, BEDROCK_Y, MIN_Y, MAX_Y));
        assertFalse(PortalTwinSpace.amplifiedTwinSpaceContains(150, d, BEDROCK_Y, MIN_Y, MAX_Y));
        assertTrue(PortalTwinSpace.amplifiedTwinSpaceContains(230, d, BEDROCK_Y, MIN_Y, MAX_Y));
    }

    @Test
    @DisplayName("a shallower basement sinks less, rounded to a whole section")
    void shallowBasement() {
        AmplifiedDrop d = AmplifiedDrop.compute(32, -8, 320, MARGIN);   // 40-block basement
        assertEquals(32, d.drop());
        assertEquals(0, d.drop() % 16);
    }

    @Test
    @DisplayName("no basement (Compatible Terrain) leaves the band untouched")
    void noBasement() {
        assertSame(AmplifiedDrop.NONE, AmplifiedDrop.compute(-64, -64, 320, MARGIN));
        assertEquals(288, AmplifiedDrop.NONE.noiseHeight(-64, 288));
        assertEquals(63, AmplifiedDrop.NONE.seaLevel(63));
    }

    @Test
    @DisplayName("a world too short for a real window leaves the band untouched")
    void tooShort() {
        assertSame(AmplifiedDrop.NONE, AmplifiedDrop.compute(96, 16, 200, MARGIN));
    }
}
