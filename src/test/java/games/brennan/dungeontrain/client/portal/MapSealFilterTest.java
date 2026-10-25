package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.portal.PortalSealPlane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What JourneyMap is allowed to map either side of a portal seal: the same rows the renderer draws.
 *
 * <p>Built from {@link PortalSealPlane}'s own cuts, so a change to where the seal sits carries into the
 * minimap with no second copy of the arithmetic. No NeoForge bootstrap.</p>
 */
final class MapSealFilterTest {

    /** A DT overworld whose terrain starts at -48, twin rooms under it at -61 (the reported world). */
    private static final int BEDROCK_Y = -48;
    private static final int ROOF_Y = 110;

    @Test
    @DisplayName("in a basement room, the world over the terrain floor maps as air and the room stays")
    void basementRoomHidesTheWorldAbove() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(-64, -55, BEDROCK_Y, Integer.MAX_VALUE);

        assertTrue(MapSealFilter.hides(cut, BEDROCK_Y), "the bedrock row over the room");
        assertTrue(MapSealFilter.hides(cut, -40), "deep lava above the seal");
        assertTrue(MapSealFilter.hides(cut, 70));
        assertFalse(MapSealFilter.hides(cut, -61), "the room itself");
        assertFalse(MapSealFilter.hides(cut, BEDROCK_Y - 1), "the room's top row");
    }

    @Test
    @DisplayName("in a basement room, the surface scan starts at the seal, not the world's surface")
    void basementRoomClampsTheHeightmap() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(-64, -55, BEDROCK_Y, Integer.MAX_VALUE);

        assertEquals(BEDROCK_Y, MapSealFilter.clampSurface(cut, 72));
        assertEquals(-58, MapSealFilter.clampSurface(cut, -58), "a top already under the seal is left alone");
    }

    @Test
    @DisplayName("on the train, the twins under the floor and over the lid are hidden from the map")
    void normalWorldHidesTheTwins() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, 80.0);

        assertTrue(MapSealFilter.hides(cut, -61), "a basement twin");
        assertTrue(MapSealFilter.hides(cut, ROOF_Y + 5), "an attic twin");
        assertFalse(MapSealFilter.hides(cut, BEDROCK_Y), "the world's own floor");
        assertFalse(MapSealFilter.hides(cut, 64));
        assertEquals(ROOF_Y + 1, MapSealFilter.clampSurface(cut, 200), "an attic room never tops the map");
        assertEquals(72, MapSealFilter.clampSurface(cut, 72));
    }

    @Test
    @DisplayName("in an attic room, the lid and the world under it go, the room stays")
    void atticRoomHidesTheWorldBelow() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(ROOF_Y + 1, ROOF_Y + 8, BEDROCK_Y, ROOF_Y);

        assertTrue(MapSealFilter.hides(cut, ROOF_Y), "the lid");
        assertTrue(MapSealFilter.hides(cut, ROOF_Y - 3), "a trimmed row of the lid's section");
        assertTrue(MapSealFilter.hides(cut, 60));
        assertFalse(MapSealFilter.hides(cut, ROOF_Y + 1), "the room's floor");
        assertEquals(150, MapSealFilter.clampSurface(cut, 150), "nothing above an attic is sealed");
    }

    @Test
    @DisplayName("with no seal the map is untouched")
    void noCutHidesNothing() {
        assertFalse(MapSealFilter.hides(PortalSealPlane.Cut.NONE, -61));
        assertFalse(MapSealFilter.hides(PortalSealPlane.Cut.NONE, 300));
        assertEquals(300, MapSealFilter.clampSurface(PortalSealPlane.Cut.NONE, 300));
    }
}
