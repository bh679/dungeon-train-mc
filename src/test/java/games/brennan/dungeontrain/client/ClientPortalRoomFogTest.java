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
 *
 * <p>The other failure these guard is the one that made a portal swap flash — an ease that ran up
 * from zero, so the first frames in a room drew a four-block far plane and filled the screen with fog
 * colour. Nothing here may ever hand back a distance shorter than the room's own radius.</p>
 */
class ClientPortalRoomFogTest {

    /** A room region 100 blocks across at the world floor, fogging at 65. */
    private static final PortalRoomFogPacket ROOM =
        new PortalRoomFogPacket(-50, -60, -50, 50, -50, 50, 65.0f);

    /** What vanilla would have drawn — a render distance well past the room's fog. */
    private static final float VANILLA_FAR = 192.0f;

    /** Ask enough times that the ease has converged, then report where it landed. */
    private static float settled(double x, double y, double z) {
        float value = 0.0f;
        for (int i = 0; i < 400; i++) {
            value = ClientPortalRoomFog.fogDistanceAt(x, y, z, VANILLA_FAR);
        }
        return value;
    }

    @BeforeEach
    void clear() {
        ClientPortalRoomFog.reset();
    }

    @Test
    @DisplayName("no region means no fog — a fresh client leaves the view alone")
    void freshClientHasNoFog() {
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR));
    }

    @Test
    @DisplayName("inside the region the fog eases in to the room's radius")
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
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR));
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
        float first = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR);
        float second = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR);
        assertTrue(first < VANILLA_FAR, "should have started closing in, sat at " + first);
        assertTrue(second < first, "should keep closing in, went " + first + " → " + second);
    }

    @Test
    @DisplayName("arriving in a room changes nothing on the first frame — the ease starts at vanilla's")
    void firstFrameMatchesVanilla() {
        ClientPortalRoomFog.update(ROOM);
        float first = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR);
        // One frame of an 8% ease off a 192-block plane. The point is that it is a hair under what was
        // already on screen, not a fraction of it: this is the frame a portal swap lands on.
        assertEquals(182.0f, first, 2.0f);
    }

    @Test
    @DisplayName("the fog never draws closer than the room asked for — this is the white flash")
    void neverDrawsCloserThanTheRadius() {
        ClientPortalRoomFog.update(ROOM);
        // Walk in, stand a while, walk out, stand a while. Every frame of it.
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR));
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(900, 80, 900, VANILLA_FAR));
        // …and back in again, from a cache that has been all the way round once.
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR));
    }

    private static void assertNotFlashing(float distance) {
        assertTrue(distance == 0.0f || distance >= 65.0f,
            "fog drew at " + distance + " — anything between 0 and the room's 65 is a screenful of fog");
    }

    @Test
    @DisplayName("walking out opens back up to vanilla's distance before handing it over")
    void easesOutThroughVanillaRatherThanZero() {
        ClientPortalRoomFog.update(ROOM);
        settled(0, -55, 0);
        // First frame outside: opening out, not snapping shut and not released yet.
        float first = ClientPortalRoomFog.fogDistanceAt(900, 80, 900, VANILLA_FAR);
        assertTrue(first > 65.0f && first < VANILLA_FAR, "should be opening out, sat at " + first);
        assertEquals(0.0f, settled(900, 80, 900), "should end up back in vanilla's hands");
    }

    @Test
    @DisplayName("a room fogging further than the player can see is left to vanilla entirely")
    void roomBeyondRenderDistanceDoesNothing() {
        ClientPortalRoomFog.update(ROOM);
        // Render distance 4: vanilla's plane is already closer than the room's 65.
        for (int i = 0; i < 50; i++) {
            assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, 60.0f));
        }
    }

    @Test
    @DisplayName("the region is inclusive of its far corner — a player against the wall is still inside")
    void farCornerCounts() {
        ClientPortalRoomFog.update(ROOM);
        assertTrue(settled(50.5, -50.5, 50.5) > 60.0f);
    }
}
