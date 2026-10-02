package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When one pair's twin may not be stamped where another pair's already stands.
 *
 * <p>The bug this guards: a Void room relocated onto a pair the train had left far behind,
 * in the same Y lane — the old room's bedrock showed in the new room's void, and the old pair, still
 * claiming the space, pulled the player out as stranded.</p>
 */
class PortalClaimConflictTest {

    private static final int ROOM_HEIGHT = 7;

    /** A footprint the way {@code footprintOf} measures one: a row under the floor, a row over the top. */
    private static BoundingBox footprint(int minX, int laneFloor, int maxX) {
        return new BoundingBox(minX, laneFloor - 1, -2, maxX, laneFloor + ROOM_HEIGHT, 12);
    }

    @Test
    @DisplayName("Same lane, overlapping X: a conflict")
    void sameLaneOverlapConflicts() {
        BoundingBox a = footprint(0, -22, 40);
        BoundingBox b = footprint(30, -22, 70);
        assertTrue(PortalCarriageBuilder.claimsConflict(a, b));
        assertTrue(PortalCarriageBuilder.claimsConflict(b, a));
    }

    @Test
    @DisplayName("Neighbouring lanes share one skin row and are NOT a conflict")
    void neighbouringLanesDoNotConflict() {
        int lower = -30;
        int upper = lower + PortalTwinLanes.laneHeight(ROOM_HEIGHT);
        BoundingBox a = footprint(0, lower, 40);
        BoundingBox b = footprint(0, upper, 40);
        // They do touch — that is the shared row the lanes are spaced on.
        assertTrue(a.intersects(b));
        assertFalse(PortalCarriageBuilder.claimsConflict(a, b));
        assertFalse(PortalCarriageBuilder.claimsConflict(b, a));
    }

    @Test
    @DisplayName("Same lane, apart in X: no conflict")
    void sameLaneApartDoesNotConflict() {
        assertFalse(PortalCarriageBuilder.claimsConflict(footprint(0, -22, 40), footprint(41, -22, 80)));
    }

    @Test
    @DisplayName("A Void claim reaches its void, so a pair standing in the void conflicts")
    void voidClearanceIsClaimed() {
        BoundingBox room = footprint(0, -22, 40);
        BoundingBox halo = new BoundingBox(-50, -22, -50, 90, -22 + ROOM_HEIGHT, 60);
        BoundingBox claim = PortalCarriageBuilder.claimWithHalo(room, halo);

        assertEquals(-50, claim.minX());
        assertEquals(90, claim.maxX());
        // Keeps the footprint's under-floor row: the halo alone starts at the floor.
        assertEquals(room.minY(), claim.minY());

        BoundingBox leftBehind = footprint(70, -22, 110);
        assertFalse(PortalCarriageBuilder.claimsConflict(room, leftBehind));
        assertTrue(PortalCarriageBuilder.claimsConflict(claim, leftBehind));
    }
}
