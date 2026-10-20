package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Carriage box x 100..137, y 78..85, z 0..7. */
class CarriageSpaceTest {

    private static boolean overlaps(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return CarriageSpace.overlaps(minX, minY, minZ, maxX, maxY, maxZ, 100, 78, 0, 137, 85, 7);
    }

    @Test
    void mobOnTrackBedUnderCarriageOverlaps() {
        assertTrue(overlaps(109.7, 78, 3.2, 110.3, 80.9, 3.8));
    }

    @Test
    void mobJustUnderTheFloorDoesNotOverlap() {
        assertFalse(overlaps(109.7, 75, 3.2, 110.3, 78, 3.8)); // head touches the floor's underside
    }

    @Test
    void mobTouchingTheSideDoesNotOverlap() {
        assertFalse(overlaps(109.7, 78, 7, 110.3, 80.9, 7.6));
    }

    @Test
    void mobBesideTheCarriageDoesNotOverlap() {
        assertFalse(overlaps(109.7, 78, 9, 110.3, 80.9, 9.6));
    }

    @Test
    void degenerateCarriageBoxNeverOverlaps() {
        assertFalse(CarriageSpace.overlaps(0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0));
    }
}
