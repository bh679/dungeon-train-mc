package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link PortalRoomOccupancy} — who still counts as in a dimensional room.
 *
 * <p>Framed on the room from the M1lfHunter2xG report: floor at y -12, roof at -3, train deck at 79.</p>
 */
final class PortalRoomOccupancyTest {

    /** The room's own box: y -13 (one under the floor) to -2 (one over the roof). */
    private static final AABB ROOM = new AABB(4740, -13, -64, 5200, -2, 68);
    /** A room one lane up, its box starting six rows over this one's — inside the roof margin. */
    private static final AABB UPPER = new AABB(4740, 4, -64, 5200, 17, 68);

    private static final double X = 5128.5;
    private static final double Z = 2.5;

    @Test
    @DisplayName("Inside the room's own box counts, as it always did")
    void insideCounts() {
        assertTrue(PortalRoomOccupancy.counts(X, -11, Z, ROOM, true, List.of()));
    }

    @Test
    @DisplayName("Standing on the roof after breaking out still counts")
    void onTheRoofCounts() {
        assertTrue(PortalRoomOccupancy.counts(X, -2, Z, ROOM, true, List.of()));
        assertTrue(PortalRoomOccupancy.counts(X, 3, Z, ROOM, true, List.of()),
            "a few blocks of pillar over the roof");
    }

    @Test
    @DisplayName("Dropping through the floor still counts")
    void underTheFloorCounts() {
        assertTrue(PortalRoomOccupancy.counts(X, -15, Z, ROOM, true, List.of()));
    }

    @Test
    @DisplayName("Beyond the margins does not count")
    void beyondTheMarginsDoesNot() {
        double top = ROOM.maxY + PortalRoomOccupancy.ROOF_MARGIN;
        double bottom = ROOM.minY - PortalRoomOccupancy.UNDER_FLOOR_MARGIN;
        assertFalse(PortalRoomOccupancy.counts(X, top, Z, ROOM, true, List.of()));
        assertFalse(PortalRoomOccupancy.counts(X, bottom - 0.01, Z, ROOM, true, List.of()));
    }

    @Test
    @DisplayName("The margin never counts outside twin space, so nobody on the train is ever in a room")
    void marginNeedsTwinSpace() {
        assertFalse(PortalRoomOccupancy.counts(X, 3, Z, ROOM, false, List.of()));
        assertFalse(PortalRoomOccupancy.counts(X, 79, Z, ROOM, true, List.of()), "the train deck");
    }

    @Test
    @DisplayName("A player in another room's box is that room's, never this one's (2026-10-21)")
    void anotherRoomWins() {
        assertTrue(UPPER.contains(X, 5, Z), "precondition: y 5 is inside the upper room");
        assertTrue(PortalRoomOccupancy.grown(ROOM).contains(X, 5, Z), "precondition: and in the margin");
        assertFalse(PortalRoomOccupancy.counts(X, 5, Z, ROOM, true, List.of(UPPER)));
        assertTrue(PortalRoomOccupancy.counts(X, 1, Z, ROOM, true, List.of(UPPER)),
            "the gap between the two lanes is still the lower room's");
    }

    @Test
    @DisplayName("Outside the room's footprint does not count, however low")
    void outsideTheFootprintDoesNot() {
        assertFalse(PortalRoomOccupancy.counts(4000, -11, Z, ROOM, true, List.of()));
    }
}
