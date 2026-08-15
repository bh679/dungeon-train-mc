package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one bound that both writes and erases read.
 *
 * <p>A structure must never leave behind a block its own erase will not reach, so the skin that
 * writes under a room's floor and the sweep that clears it derive that row from the same place.
 * Everything else in {@code PortalCarriageBuilder} needs a {@code ServerLevel}; this does not.</p>
 */
class PortalCarriageBuilderBoundsTest {

    /** What {@code PortalCarriageEvents.TWIN_FLOOR_MARGIN} puts the lowest lane's floor at. */
    private static final int FLOOR_MARGIN = 1;

    @Test
    @DisplayName("An ordinary lane writes the row under its floor")
    void higherLanesWriteBelowTheFloor() {
        int worldMin = -64;
        int laneFloor = worldMin + FLOOR_MARGIN + DimensionalCarriageLayout.TWIN_LANE_HEIGHT;
        assertEquals(laneFloor - 1, PortalCarriageBuilder.lowestWritableY(worldMin, laneFloor));
    }

    @Test
    @DisplayName("The lowest lane does not — that row is the world's own bedrock")
    void lowestLaneStopsAtItsOwnFloor() {
        int worldMin = -64;
        int laneFloor = worldMin + FLOOR_MARGIN;
        // Not worldMin: writing there is pointless and erasing it would open a hole into the void.
        assertEquals(laneFloor, PortalCarriageBuilder.lowestWritableY(worldMin, laneFloor));
    }

    @Test
    @DisplayName("Never below the world, whatever floor it is handed")
    void neverBelowTheWorld() {
        for (int worldMin : new int[]{-64, 0, 32}) {
            for (int floor = worldMin; floor < worldMin + 40; floor++) {
                assertTrue(PortalCarriageBuilder.lowestWritableY(worldMin, floor) > worldMin,
                    "world floor " + worldMin + ", structure floor " + floor);
            }
        }
    }

    @Test
    @DisplayName("Never above the floor it is protecting — the skin sits under the room, not in it")
    void neverAboveTheStructureFloor() {
        for (int floor = -60; floor < 40; floor++) {
            assertTrue(PortalCarriageBuilder.lowestWritableY(-64, floor) <= floor,
                "structure floor " + floor);
        }
    }
}
