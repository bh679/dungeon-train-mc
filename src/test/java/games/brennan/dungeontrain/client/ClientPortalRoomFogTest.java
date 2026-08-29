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

    /** A room region 100 blocks across at the world floor, fogging flat at 65 — a tiling mode. */
    private static final PortalRoomFogPacket ROOM =
        new PortalRoomFogPacket(-50, -60, -50, 50, -50, 50, 65.0f, 0, 0.0f);

    /**
     * A Bedrockless region: a room 20 blocks across at the origin, with 50 blocks of swept clearance
     * padded around it, fogging at 50 at the room's walls and closing to 8 at the clearance edge.
     */
    private static final PortalRoomFogPacket VOID_ROOM =
        new PortalRoomFogPacket(-60, -60, -60, 59, -50, 59, 50.0f, 50, 8.0f);

    /** What vanilla would have drawn — a render distance well past the room's fog. */
    private static final float VANILLA_FAR = 192.0f;

    /** Not in a portal corridor: the ordinary case, and what every pre-corridor test asserts. */
    private static final float NO_CROSSING = 0.0f;

    /** A spot well outside {@link #ROOM}, standing in for a corridor's world position. */
    private static final double CORRIDOR_X = 200.0;

    /** Ask enough times that the ease has converged, then report where it landed. */
    private static float settled(double x, double y, double z) {
        return settled(x, y, z, NO_CROSSING);
    }

    /** The same, for a camera partway along a portal corridor. */
    private static float settled(double x, double y, double z, float crossing) {
        float value = 0.0f;
        for (int i = 0; i < 400; i++) {
            value = ClientPortalRoomFog.fogDistanceAt(x, y, z, VANILLA_FAR, crossing);
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
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING));
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
        assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING));
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
        float first = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING);
        float second = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING);
        assertTrue(first < VANILLA_FAR, "should have started closing in, sat at " + first);
        assertTrue(second < first, "should keep closing in, went " + first + " → " + second);
    }

    @Test
    @DisplayName("arriving in a room changes nothing on the first frame — the ease starts at vanilla's")
    void firstFrameMatchesVanilla() {
        ClientPortalRoomFog.update(ROOM);
        float first = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING);
        // One frame of an 8% ease off a 192-block plane. The point is that it is a hair under what was
        // already on screen, not a fraction of it: this is the frame a portal swap lands on.
        assertEquals(182.0f, first, 2.0f);
    }

    @Test
    @DisplayName("the fog never draws closer than the room asked for — this is the white flash")
    void neverDrawsCloserThanTheRadius() {
        ClientPortalRoomFog.update(ROOM);
        // Walk in, stand a while, walk out, stand a while. Every frame of it.
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING));
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(900, 80, 900, VANILLA_FAR, NO_CROSSING));
        // …and back in again, from a cache that has been all the way round once.
        for (int i = 0; i < 200; i++) assertNotFlashing(ClientPortalRoomFog.fogDistanceAt(0, -55, 0, VANILLA_FAR, NO_CROSSING));
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
        float first = ClientPortalRoomFog.fogDistanceAt(900, 80, 900, VANILLA_FAR, NO_CROSSING);
        assertTrue(first > 65.0f && first < VANILLA_FAR, "should be opening out, sat at " + first);
        assertEquals(0.0f, settled(900, 80, 900), "should end up back in vanilla's hands");
    }

    @Test
    @DisplayName("a room fogging further than the player can see is left to vanilla entirely")
    void roomBeyondRenderDistanceDoesNothing() {
        ClientPortalRoomFog.update(ROOM);
        // Render distance 4: vanilla's plane is already closer than the room's 65.
        for (int i = 0; i < 50; i++) {
            assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(0, -55, 0, 60.0f, NO_CROSSING));
        }
    }

    @Test
    @DisplayName("the region is inclusive of its far corner — a player against the wall is still inside")
    void farCornerCounts() {
        ClientPortalRoomFog.update(ROOM);
        assertTrue(settled(50.5, -50.5, 50.5) > 60.0f);
    }

    @Test
    @DisplayName("inside a Bedrockless room the fog is the room's own distance — the ramp starts at the wall")
    void bedrocklessInsideTheRoomIsNominal() {
        ClientPortalRoomFog.update(VOID_ROOM);
        // Standing in the middle, and standing against the room's own wall: both are "not out in the
        // void yet", and neither may be fogged any harder than the room asks for.
        assertEquals(50.0f, settled(0, -55, 0), 0.5f);
        assertEquals(50.0f, settled(10, -55, 0), 0.5f);
    }

    @Test
    @DisplayName("walking out into the clearance closes the fog in, step by step")
    void bedrocklessRampsAsYouWalkOut() {
        ClientPortalRoomFog.update(VOID_ROOM);
        float atWall = settled(10, -55, 0);
        float quarter = settled(22.5, -55, 0);
        float half = settled(35, -55, 0);
        float far = settled(55, -55, 0);

        assertTrue(atWall > quarter && quarter > half && half > far,
            "fog should tighten with every step out, went "
                + atWall + " → " + quarter + " → " + half + " → " + far);
        // Halfway across the clearance is halfway down the ramp: 50 closing toward 8.
        assertEquals(29.0f, half, 1.0f);
    }

    @Test
    @DisplayName("at the edge of the clearance it is a whiteout — the room you left is gone")
    void bedrocklessBottomsOutAtTheWhiteout() {
        ClientPortalRoomFog.update(VOID_ROOM);
        assertEquals(8.0f, settled(59.5, -55, 0), 0.5f);
        // The far corner is at the end of the walk on both axes, not √2 past it — the swept space is
        // a box, so the ramp is measured per axis rather than as a diagonal.
        assertEquals(8.0f, settled(59.5, -55, 59.5), 0.5f);
    }

    @Test
    @DisplayName("the ramp measures the walk, not the drop — a Bedrockless void has no vertical term")
    void bedrocklessIgnoresHeight() {
        ClientPortalRoomFog.update(VOID_ROOM);
        // Same spot in the room, floor and ceiling. Pairs sit twelve blocks apart in Y, so there is
        // no vertical emptiness for a ramp to cross and height must not change the fog.
        assertEquals(settled(0, -59, 0), settled(0, -50, 0), 0.5f);
    }

    @Test
    @DisplayName("out past the swept clearance the fog is gone entirely, ramp or no ramp")
    void bedrocklessStillReleasesOutsideTheRegion() {
        ClientPortalRoomFog.update(VOID_ROOM);
        settled(0, -55, 0);
        assertEquals(0.0f, settled(900, 80, 900));
    }

    @Test
    @DisplayName("a Bedrockless room on a short render distance still ramps rather than being skipped")
    void bedrocklessRampsInsideAShortRenderDistance() {
        ClientPortalRoomFog.update(VOID_ROOM);
        // Render distance 2 chunks: vanilla's plane is 32, already inside the room's nominal 50, so
        // standing in the room is vanilla's business. The walk out is not — the ramp goes below it.
        float inRoom = 0.0f;
        for (int i = 0; i < 400; i++) inRoom = ClientPortalRoomFog.fogDistanceAt(0, -55, 0, 32.0f, NO_CROSSING);
        assertEquals(0.0f, inRoom, "a room fogging past the far plane has nothing to say");

        float atEdge = 0.0f;
        for (int i = 0; i < 400; i++) atEdge = ClientPortalRoomFog.fogDistanceAt(59.5, -55, 0, 32.0f, NO_CROSSING);
        assertEquals(8.0f, atEdge, 0.5f, "the whiteout is well inside 32 blocks and must still draw");
    }

    @Test
    @DisplayName("a corridor fogs partway — the walk in is where the transition happens")
    void corridorRampsOnTheWayIn() {
        ClientPortalRoomFog.update(ROOM);
        // Halfway along, halfway between what vanilla was drawing and what the room asks for.
        assertEquals(128.5f, settled(CORRIDOR_X, -55, 0, 0.5f), 1.0f);
    }

    @Test
    @DisplayName("the room-side door plane is already the room's own distance — nothing left to snap")
    void corridorMeetsTheRoomAtTheDoor() {
        ClientPortalRoomFog.update(ROOM);
        float atDoor = settled(CORRIDOR_X, -55, 0, 1.0f);
        ClientPortalRoomFog.reset();
        ClientPortalRoomFog.update(ROOM);
        assertEquals(settled(0, -55, 0), atDoor, 0.5f);
    }

    @Test
    @DisplayName("the fog closes in with every step down the corridor, and never past vanilla's plane")
    void corridorRampIsMonotonic() {
        ClientPortalRoomFog.update(ROOM);
        float previous = VANILLA_FAR;
        for (int step = 0; step <= 8; step++) {
            float here = settled(CORRIDOR_X, -55, 0, step / 8.0f);
            if (step == 0) {
                assertEquals(0.0f, here, "no corridor, no fog");
                continue;
            }
            assertTrue(here <= VANILLA_FAR, "must only ever shrink the view, drew " + here);
            assertTrue(here < previous, "should keep closing in, went " + previous + " → " + here);
            previous = here;
        }
    }

    @Test
    @DisplayName("no cached room means a corridor ramps into nothing")
    void corridorWithoutARoomDoesNothing() {
        ClientPortalRoomFog.update(PortalRoomFogPacket.none());
        assertEquals(0.0f, settled(CORRIDOR_X, -55, 0, 0.75f));
    }

    @Test
    @DisplayName("a room fogging past the far plane is left to vanilla in the corridor too")
    void corridorRespectsAShortRenderDistance() {
        ClientPortalRoomFog.update(ROOM);
        // Render distance 4: vanilla is already closer than the room's 65, so there is nothing to
        // ramp toward — and ramping anyway would be letting the player see further, not less far.
        for (int i = 0; i < 50; i++) {
            assertEquals(0.0f, ClientPortalRoomFog.fogDistanceAt(CORRIDOR_X, -55, 0, 60.0f, 0.5f));
        }
    }

    @Test
    @DisplayName("walking out of the corridor into the train hands the fog back to vanilla")
    void corridorReleasesAtTheTrainDoor() {
        ClientPortalRoomFog.update(ROOM);
        assertTrue(settled(CORRIDOR_X, -55, 0, 1.0f) < VANILLA_FAR);
        assertEquals(0.0f, settled(CORRIDOR_X, -55, 0, NO_CROSSING));
    }
}
