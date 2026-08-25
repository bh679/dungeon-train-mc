package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two bounds that both writes and erases read.
 *
 * <p>A structure must never leave behind a block its own erase will not reach, so the skin that
 * writes under a room's floor — or over its ceiling — and the sweep that clears it derive those rows
 * from the same place. The ceiling half matters now that a twin can stand in the <b>attic</b> over
 * the upside-down band's inverted lid ({@link PortalTwinSpace}) rather than under the world, where
 * the build ceiling is what it runs into. Everything else in {@code PortalCarriageBuilder} needs a
 * {@code ServerLevel}; this does not.</p>
 */
class PortalCarriageBuilderBoundsTest {

    /** Read, not restated: a local copy drifted from the real margin once already. */
    private static final int FLOOR_MARGIN = PortalTwinLanes.FLOOR_MARGIN;

    /** A DT overworld's build ceiling. */
    private static final int WORLD_MAX = 320;

    /** The height lanes are spaced on in a world that has authored nothing taller. */
    private static final int BUILT_IN_ROOM_HEIGHT = 7;

    @Test
    @DisplayName("An ordinary lane writes the row under its floor")
    void higherLanesWriteBelowTheFloor() {
        int worldMin = -64;
        int laneFloor = worldMin + FLOOR_MARGIN + PortalTwinLanes.laneHeight(BUILT_IN_ROOM_HEIGHT);
        assertEquals(laneFloor - 1, PortalCarriageBuilder.lowestWritableY(worldMin, laneFloor));
    }

    @Test
    @DisplayName("So does the lowest lane — that is what the floor margin buys it")
    void lowestLaneWritesItsUndersideToo() {
        int worldMin = -64;
        int laneFloor = worldMin + FLOOR_MARGIN;
        // At a margin of one this row and the clamp were the same, and lane 0 alone ended up with a
        // floor you could break through into open basement. At two the underside lands.
        assertEquals(laneFloor - 1, PortalCarriageBuilder.lowestWritableY(worldMin, laneFloor));
    }

    @Test
    @DisplayName("A floor on the world's own bottom row has nowhere to put one")
    void aFloorAtTheVeryBottomClampsUpward() {
        int worldMin = -64;
        // Not worldMin: writing there is pointless and erasing it would open a hole into the void.
        assertEquals(worldMin + 1, PortalCarriageBuilder.lowestWritableY(worldMin, worldMin));
    }

    @Test
    @DisplayName("An ordinary ceiling writes the row over it")
    void ceilingsWriteTheRowAbove() {
        int roomTop = 150;
        assertEquals(roomTop + 1, PortalCarriageBuilder.highestWritableY(WORLD_MAX, roomTop));
    }

    @Test
    @DisplayName("Never past the top of the world — setBlock up there is a silent no-op")
    void neverAboveTheWorld() {
        for (int worldMax : new int[]{320, 256, 64}) {
            for (int top = worldMax - 40; top < worldMax + 4; top++) {
                assertTrue(PortalCarriageBuilder.highestWritableY(worldMax, top) < worldMax,
                    "world ceiling " + worldMax + ", structure ceiling " + top);
            }
        }
    }

    @Test
    @DisplayName("Never below the ceiling it is protecting — the skin sits over the room, not in it")
    void neverBelowTheStructureCeiling() {
        for (int top = 100; top < 200; top++) {
            assertTrue(PortalCarriageBuilder.highestWritableY(WORLD_MAX, top) >= top,
                "structure ceiling " + top);
        }
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
