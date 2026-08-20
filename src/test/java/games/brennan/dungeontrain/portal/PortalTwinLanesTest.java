package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for {@link PortalTwinLanes} — where a portal pair's twin structure is stamped
 * in the basement under the world. No NeoForge bootstrap.
 */
final class PortalTwinLanesTest {

    /** The default DT overworld: dimension type floor at -48, bedrock (noise floor) at 32. */
    private static final int DT_MIN_Y = -48;
    private static final int DT_BEDROCK_Y = 32;

    /** Compatible Terrain mode: vanilla's dimension type, whose floor and bedrock are the same row. */
    private static final int VANILLA_MIN_Y = -64;
    private static final int VANILLA_BEDROCK_Y = -64;

    private static final int GROUP_SIZE = 4;

    /**
     * The height the lanes are sized on in these tests — the built-in room's, which is what a world
     * that has authored nothing taller spaces its lanes on.
     */
    private static final int ROOM_H = 7;

    @Test
    @DisplayName("an 80-block basement holds every lane at the built-in room's height")
    void basementHoldsAllLanes() {
        assertEquals(PortalTwinLanes.MAX_LANES,
            PortalTwinLanes.usableLanes(DT_MIN_Y, DT_BEDROCK_Y, ROOM_H));
    }

    @Test
    @DisplayName("a lane is one block taller than the structure in it")
    void laneClearsItsStructure() {
        // eraseTwin sweeps one row past a structure's top, so a lane of exactly the structure's
        // height would have each lane's erase take the floor out of the lane above it.
        for (int h = PortalRoomLayout.MIN_HEIGHT; h <= PortalRoomLayout.MAX_HEIGHT; h++) {
            assertEquals(h + 1, PortalTwinLanes.laneHeight(h));
        }
    }

    @Test
    @DisplayName("height and lanes are two ends of one budget: a taller room spends lanes")
    void tallerRoomsCostLanes() {
        int previous = Integer.MAX_VALUE;
        for (int h = PortalRoomLayout.MIN_HEIGHT;
             h <= PortalTwinLanes.maxStructureHeight(DT_MIN_Y, DT_BEDROCK_Y); h++) {
            int lanes = PortalTwinLanes.usableLanes(DT_MIN_Y, DT_BEDROCK_Y, h);
            assertTrue(lanes >= 1 && lanes <= PortalTwinLanes.MAX_LANES,
                "height " + h + " gave " + lanes + " lanes");
            assertTrue(lanes <= previous, "lanes must never grow with the room: height " + h);
            previous = lanes;

            // Whatever the count, the top lane still holds a structure of that height.
            int topLane = PortalTwinLanes.twinFloorY(
                DT_MIN_Y, DT_BEDROCK_Y, (lanes - 1) * GROUP_SIZE, GROUP_SIZE, h);
            assertTrue(PortalTwinLanes.fitsUnderWorld(DT_MIN_Y, DT_BEDROCK_Y, topLane, h),
                "height " + h + ": top lane at " + topLane + " reaches the bedrock");
        }
    }

    @Test
    @DisplayName("every height legal today keeps the six lanes it had")
    void noRegressionAtTheOldCeiling() {
        // The lane spacing was a constant 12 and the ceiling on a room 11. Both of those heights
        // must still spread over the full six lanes, or this change cost existing worlds their
        // collision margin.
        for (int h = PortalRoomLayout.MIN_HEIGHT; h <= 11; h++) {
            assertEquals(PortalTwinLanes.MAX_LANES,
                PortalTwinLanes.usableLanes(DT_MIN_Y, DT_BEDROCK_Y, h),
                "height " + h + " lost lanes it used to have");
        }
    }

    @Test
    @DisplayName("the tallest structure a stock basement can stand up is 77")
    void maxStructureHeightInAStockPreset() {
        assertEquals(77, PortalTwinLanes.maxStructureHeight(DT_MIN_Y, DT_BEDROCK_Y));
        assertTrue(PortalTwinLanes.fitsUnderWorld(DT_MIN_Y, DT_BEDROCK_Y,
            PortalTwinLanes.floorY(DT_MIN_Y),
            PortalTwinLanes.maxStructureHeight(DT_MIN_Y, DT_BEDROCK_Y)));
        assertFalse(PortalTwinLanes.fitsUnderWorld(DT_MIN_Y, DT_BEDROCK_Y,
            PortalTwinLanes.floorY(DT_MIN_Y),
            PortalTwinLanes.maxStructureHeight(DT_MIN_Y, DT_BEDROCK_Y) + 1),
            "one block taller than the world holds must not fit");
        // A world with no basement has nothing to measure, so it gets the authoring ceiling.
        assertEquals(PortalRoomLayout.MAX_HEIGHT,
            PortalTwinLanes.maxStructureHeight(VANILLA_MIN_Y, VANILLA_BEDROCK_Y));
    }

    @Test
    @DisplayName("no lane's structure reaches the bedrock, so none can be dug into")
    void everyLaneStaysUnderTheWorld() {
        for (int lane = 0; lane < PortalTwinLanes.MAX_LANES; lane++) {
            int pairKey = lane * GROUP_SIZE;
            int twinY = PortalTwinLanes.twinFloorY(
                DT_MIN_Y, DT_BEDROCK_Y, pairKey, GROUP_SIZE, ROOM_H);
            assertTrue(twinY > DT_MIN_Y,
                "lane " + lane + " floor " + twinY + " must clear the build floor");
            assertTrue(PortalTwinLanes.fitsUnderWorld(DT_MIN_Y, DT_BEDROCK_Y, twinY, ROOM_H),
                "lane " + lane + " floor " + twinY + " must hold its room under the bedrock");
        }
    }

    @Test
    @DisplayName("every lane has a row under its floor to skin, lane 0 included")
    void everyLaneHasAnUnderside() {
        // PortalCarriageBuilder writes the Bedrock Lock underside one row below the structure floor,
        // clamped to what the world can hold. A lane whose floor sits ON that clamp loses the row —
        // and with it the only thing between a room's floor and the open basement below.
        for (int lane = 0; lane < PortalTwinLanes.MAX_LANES; lane++) {
            int twinY = PortalTwinLanes.twinFloorY(
                DT_MIN_Y, DT_BEDROCK_Y, lane * GROUP_SIZE, GROUP_SIZE, ROOM_H);
            assertTrue(PortalCarriageBuilder.lowestWritableY(DT_MIN_Y, twinY) < twinY,
                "lane " + lane + " (floor " + twinY + ") has no writable row beneath it");
        }
    }

    @Test
    @DisplayName("consecutive groups take consecutive lanes, not all of lane 0")
    void groupsSpreadOverLanes() {
        Set<Integer> heights = new HashSet<>();
        for (int group = 0; group < PortalTwinLanes.MAX_LANES; group++) {
            // A pair is keyed on its group's anchor — always a multiple of the group size.
            heights.add(PortalTwinLanes.twinFloorY(
                DT_MIN_Y, DT_BEDROCK_Y, group * GROUP_SIZE, GROUP_SIZE, ROOM_H));
        }
        assertEquals(PortalTwinLanes.MAX_LANES, heights.size());
    }

    @Test
    @DisplayName("lanes wrap, and are spaced a full lane height apart")
    void lanesWrapAndAreSpaced() {
        int lane0 = PortalTwinLanes.twinFloorY(DT_MIN_Y, DT_BEDROCK_Y, 0, GROUP_SIZE, ROOM_H);
        int lane1 = PortalTwinLanes.twinFloorY(
            DT_MIN_Y, DT_BEDROCK_Y, GROUP_SIZE, GROUP_SIZE, ROOM_H);
        assertEquals(PortalTwinLanes.laneHeight(ROOM_H), lane1 - lane0);
        assertEquals(lane0, PortalTwinLanes.twinFloorY(
            DT_MIN_Y, DT_BEDROCK_Y, PortalTwinLanes.MAX_LANES * GROUP_SIZE, GROUP_SIZE, ROOM_H));
    }

    @Test
    @DisplayName("a westward pair key lands in a lane too, rather than a negative one")
    void negativePairKeys() {
        int twinY = PortalTwinLanes.twinFloorY(
            DT_MIN_Y, DT_BEDROCK_Y, -3 * GROUP_SIZE, GROUP_SIZE, ROOM_H);
        assertTrue(twinY >= PortalTwinLanes.floorY(DT_MIN_Y));
        assertTrue(PortalTwinLanes.fitsUnderWorld(DT_MIN_Y, DT_BEDROCK_Y, twinY, ROOM_H));
    }

    @Test
    @DisplayName("a world with no basement keeps one lane at the old height")
    void compatWorldFallsBackToOneLane() {
        assertEquals(1, PortalTwinLanes.usableLanes(VANILLA_MIN_Y, VANILLA_BEDROCK_Y, ROOM_H));
        for (int group = 0; group < 4; group++) {
            assertEquals(VANILLA_MIN_Y + PortalTwinLanes.FLOOR_MARGIN,
                PortalTwinLanes.twinFloorY(
                    VANILLA_MIN_Y, VANILLA_BEDROCK_Y, group * GROUP_SIZE, GROUP_SIZE, ROOM_H));
        }
        // Nothing to stay under, so the under-the-world check defers to the caller's other fits.
        assertTrue(PortalTwinLanes.fitsUnderWorld(VANILLA_MIN_Y, VANILLA_BEDROCK_Y,
            VANILLA_MIN_Y + PortalTwinLanes.FLOOR_MARGIN, PortalRoomLayout.MAX_HEIGHT));
    }

    @Test
    @DisplayName("a basement too shallow for a second lane yields exactly one")
    void shallowBasement() {
        // Room for one structure of this height, but not for another lane above it.
        int bedrock = PortalTwinLanes.floorY(DT_MIN_Y) + ROOM_H + 1;
        assertEquals(1, PortalTwinLanes.usableLanes(DT_MIN_Y, bedrock, ROOM_H));
    }

    @Test
    @DisplayName("a structure poking up through the bedrock is rejected")
    void tooTallForItsLaneIsRejected() {
        int top = PortalTwinLanes.floorY(DT_MIN_Y);
        assertFalse(PortalTwinLanes.fitsUnderWorld(
            DT_MIN_Y, DT_BEDROCK_Y, top, DT_BEDROCK_Y - top),
            "a structure whose top reaches the bedrock row must not be stamped");
    }
}
