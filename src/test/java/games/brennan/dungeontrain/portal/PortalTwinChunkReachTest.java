package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for {@link PortalTwinChunkReach} — how many chunks a player inside a
 * dimensional carriage holds instead of their view distance. No NeoForge bootstrap.
 */
class PortalTwinChunkReachTest {

    private static final int VIEW = 12;
    private static final int FULL_WINDOW = (2 * PortalRoomTiling.MAX_RADIUS + 1) * (2 * PortalRoomTiling.MAX_RADIUS + 1);

    @Test
    @DisplayName("the window radius is the tiler's: full budget gives MAX_RADIUS, one tile gives none")
    void windowRadiusFollowsBudget() {
        assertEquals(PortalRoomTiling.MAX_RADIUS, PortalTwinChunkReach.windowRadius(FULL_WINDOW));
        assertEquals(PortalRoomTiling.MAX_RADIUS, PortalTwinChunkReach.windowRadius(FULL_WINDOW * 4));
        assertEquals(0, PortalTwinChunkReach.windowRadius(1));
        assertEquals(1, PortalTwinChunkReach.windowRadius(9));
        // A part ring still reaches the next ring out: the tiler fills nearest-first.
        assertEquals(1, PortalTwinChunkReach.windowRadius(5));
        assertEquals(2, PortalTwinChunkReach.windowRadius(10));
    }

    @Test
    @DisplayName("a default endless room holds well under a 12-chunk view distance")
    void defaultEndlessRoom() {
        int reach = PortalTwinChunkReach.reachBlocks(true, 11, 13, FULL_WINDOW, 2, 30);
        assertEquals((PortalRoomTiling.MAX_RADIUS + 1 + 2) * 13, reach);
        int radius = PortalTwinChunkReach.chunkRadius(reach, VIEW);
        assertTrue(radius < VIEW, "radius " + radius);
        assertTrue(radius * 16 >= reach, "must cover the reach, radius " + radius);
    }

    @Test
    @DisplayName("a room that does not tile only needs its own span")
    void singleRoom() {
        assertEquals(30, PortalTwinChunkReach.reachBlocks(false, 11, 13, FULL_WINDOW, 2, 30));
        assertEquals(4, PortalTwinChunkReach.chunkRadius(30, VIEW));
    }

    @Test
    @DisplayName("never more than view distance, never less than the floor")
    void clamped() {
        int huge = PortalTwinChunkReach.reachBlocks(true, 64, 64, FULL_WINDOW, 3, 100);
        assertEquals(VIEW, PortalTwinChunkReach.chunkRadius(huge, VIEW));
        assertEquals(PortalTwinChunkReach.MIN_RADIUS, PortalTwinChunkReach.chunkRadius(0, VIEW));
        // A tiny server view distance still keeps the floor under the player.
        assertEquals(PortalTwinChunkReach.MIN_RADIUS, PortalTwinChunkReach.chunkRadius(500, 1));
    }

    @Test
    @DisplayName("a big room's smaller budget shrinks the window")
    void bigRoomSmallBudget() {
        int reach = PortalTwinChunkReach.reachBlocks(true, 64, 64, 5, 1, 90);
        assertEquals((1 + 1 + 1) * 64, reach);
    }
}
