package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.net.PortalRoomDepthPacket;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What JourneyMap is allowed to map: only the carriage from inside one, and the rows the renderer draws
 * from anywhere else.
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

        assertTrue(MapSealFilter.hides(null, cut, 0, BEDROCK_Y, 0), "the bedrock row over the room");
        assertTrue(MapSealFilter.hides(null, cut, 0, -40, 0), "deep lava above the seal");
        assertTrue(MapSealFilter.hides(null, cut, 0, 70, 0));
        assertFalse(MapSealFilter.hides(null, cut, 0, -61, 0), "the room itself");
        assertFalse(MapSealFilter.hides(null, cut, 0, BEDROCK_Y - 1, 0), "the room's top row");
    }

    @Test
    @DisplayName("in a basement room, the surface scan starts at the seal, not the world's surface")
    void basementRoomClampsTheHeightmap() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(-64, -55, BEDROCK_Y, Integer.MAX_VALUE);

        assertEquals(BEDROCK_Y, MapSealFilter.clampSurface(null, cut, 0, 0, 72));
        assertEquals(-58, MapSealFilter.clampSurface(null, cut, 0, 0, -58), "a top already under the seal is left alone");
    }

    @Test
    @DisplayName("on the train, the twins under the floor and over the lid are hidden from the map")
    void normalWorldHidesTheTwins() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, 80.0);

        assertTrue(MapSealFilter.hides(null, cut, 0, -61, 0), "a basement twin");
        assertTrue(MapSealFilter.hides(null, cut, 0, ROOF_Y + 5, 0), "an attic twin");
        assertFalse(MapSealFilter.hides(null, cut, 0, BEDROCK_Y, 0), "the world's own floor");
        assertFalse(MapSealFilter.hides(null, cut, 0, 64, 0));
        assertEquals(ROOF_Y + 1, MapSealFilter.clampSurface(null, cut, 0, 0, 200), "an attic room never tops the map");
        assertEquals(72, MapSealFilter.clampSurface(null, cut, 0, 0, 72));
    }

    @Test
    @DisplayName("in an attic room, the lid and the world under it go, the room stays")
    void atticRoomHidesTheWorldBelow() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(ROOF_Y + 1, ROOF_Y + 8, BEDROCK_Y, ROOF_Y);

        assertTrue(MapSealFilter.hides(null, cut, 0, ROOF_Y, 0), "the lid");
        assertTrue(MapSealFilter.hides(null, cut, 0, ROOF_Y - 3, 0), "a trimmed row of the lid's section");
        assertTrue(MapSealFilter.hides(null, cut, 0, 60, 0));
        assertFalse(MapSealFilter.hides(null, cut, 0, ROOF_Y + 1, 0), "the room's floor");
        assertEquals(150, MapSealFilter.clampSurface(null, cut, 0, 0, 150), "nothing above an attic is sealed");
    }

    @Test
    @DisplayName("with no seal the map is untouched")
    void noCutHidesNothing() {
        assertFalse(MapSealFilter.hides(null, PortalSealPlane.Cut.NONE, 0, -61, 0));
        assertFalse(MapSealFilter.hides(null, PortalSealPlane.Cut.NONE, 0, 300, 0));
        assertEquals(300, MapSealFilter.clampSurface(null, PortalSealPlane.Cut.NONE, 0, 0, 300));
    }

    /** A carriage box in the basement, as the server sends it: x 9780..9990, y -64..-54, z 20..120. */
    private static final PortalRoomDepthPacket ROOM = new PortalRoomDepthPacket(9780, -64, 20, 9990, -54, 120, 0, 0, 0);

    @Test
    @DisplayName("inside a carriage, everything outside its box maps as air, the carriage stays")
    void insideCarriageOnlyTheCarriageMaps() {
        PortalSealPlane.Cut cut = PortalSealPlane.Cut.NONE; // the rule holds whatever the seal says

        assertFalse(MapSealFilter.hides(ROOM, cut, 9830, -61, 57), "the room floor under the player");
        assertFalse(MapSealFilter.hides(ROOM, cut, 9780, -64, 20), "the box's min corner");
        assertTrue(MapSealFilter.hides(ROOM, cut, 9830, -50, 57), "the lava over the room");
        assertTrue(MapSealFilter.hides(ROOM, cut, 9830, 62, 57), "the ocean over the room");
        assertTrue(MapSealFilter.hides(ROOM, cut, 9700, -61, 57), "beside the carriage, at its height");
        assertTrue(MapSealFilter.hides(ROOM, cut, 9830, -61, 200), "beyond the far wall");
        assertTrue(MapSealFilter.hides(ROOM, cut, 9990, -61, 57), "the box's open max edge");
    }

    @Test
    @DisplayName("inside a carriage, its columns scan from its ceiling and the rest start empty")
    void insideCarriageClampsEveryColumn() {
        PortalSealPlane.Cut cut = PortalSealPlane.Cut.NONE;

        assertEquals(-54, MapSealFilter.clampSurface(ROOM, cut, 9830, 57, 64));
        assertEquals(-64, MapSealFilter.clampSurface(ROOM, cut, 9700, 57, 64), "outside: starts at the box floor");
        assertEquals(-70, MapSealFilter.clampSurface(ROOM, cut, 9830, 57, -70), "a lower top is left alone");
    }
}
