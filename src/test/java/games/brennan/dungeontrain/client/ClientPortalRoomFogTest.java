package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.PortalRoomFogPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fog holds a place rather than a state, and this is why: the failure it is shaped to prevent is
 * a player disconnecting inside a room and coming back permanently fogged.
 */
class ClientPortalRoomFogTest {

    /** A room region 100 blocks across at the world floor, fogging at 65. */
    private static final PortalRoomFogPacket ROOM =
        new PortalRoomFogPacket(-50, -60, -50, 50, -50, 50, 65.0f);

    /** Ask enough times that the ease has converged, then report where it landed. */
    private static float settled(double x, double y, double z) {
        float value = 0.0f;
        for (int i = 0; i < 400; i++) value = ClientPortalRoomFog.fogDistanceAt(x, y, z);
        return value;
    }

    @BeforeEach
    void clear() {
        ClientPortalRoomFog.reset();
    }

    @Test
    @DisplayName("no region means no fog — a fresh client leaves the view alone")
    void freshClientHasNoFog() {
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0));
    }

    @Test
    @DisplayName("inside the region the fog eases up to the room's radius")
    void insideTheRegionFogs() {
        ClientPortalRoomFog.update(ROOM);
        assertEquals(65.0f, settled(0, -55, 0), 0.5f);
    }

    @Test
    @DisplayName("stepping outside clears it with no message from the server at all")
    void steppingOutClearsWithoutAMessage() {
        ClientPortalRoomFog.update(ROOM);
        assertTrue(settled(0, -55, 0) > 60.0f);

        // Same cached region, player somewhere else. This is the walking-out case, and it is also
        // what makes a disconnect safe: nothing has to arrive for the fog to stop applying.
        assertEquals(0.0f, settled(900, 80, 900));
    }

    @Test
    @DisplayName("a region that no longer holds the player never fogs them again")
    void staleRegionDoesNotStrandAPlayer() {
        ClientPortalRoomFog.update(ROOM);
        settled(0, -55, 0);
        // Rejoining puts them at the surface, thousands of blocks from a structure at the world floor.
        assertEquals(0.0f, settled(1200, 80, -400));
    }

    @Test
    @DisplayName("reset drops the region outright — a room never leaks into the next world")
    void resetDropsTheRegion() {
        ClientPortalRoomFog.update(ROOM);
        settled(0, -55, 0);
        ClientPortalRoomFog.reset();
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0));
    }

    @Test
    @DisplayName("a zero-radius packet is how the server takes the fog off")
    void zeroRadiusMeansNoFog() {
        ClientPortalRoomFog.update(ROOM);
        settled(0, -55, 0);
        ClientPortalRoomFog.update(PortalRoomFogPacket.none());
        assertEquals(0.0f, settled(0, -55, 0));
    }

    @Test
    @DisplayName("the fog eases rather than snapping, because the room's edges move under the player")
    void fogEasesIn() {
        ClientPortalRoomFog.update(ROOM);
        float first = ClientPortalRoomFog.fogDistanceAt(0, -55, 0);
        assertTrue(first > 0.0f && first < 65.0f, "first frame jumped straight to " + first);
        assertTrue(ClientPortalRoomFog.fogDistanceAt(0, -55, 0) > first, "should keep opening out");
    }

    @Test
    @DisplayName("the region is inclusive of its far corner — a player against the wall is still inside")
    void farCornerCounts() {
        ClientPortalRoomFog.update(ROOM);
        assertTrue(settled(50.5, -50.5, 50.5) > 60.0f);
    }
}
