package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for {@link PortalGeometry} — the side-of-the-midpoint rule that decides
 * which copy of the hallway a player belongs in. No NeoForge bootstrap.
 *
 * <p>Test geometry: corridor at {@code originX=100, originZ=10}, NEAR floor {@code Y=64},
 * {@code length=32} (interior X {@code [100,131]}, midpoint {@code 116.0}), {@code width=3}
 * (interior Z {@code [10,12]}), {@code height=3}, {@code deltaY=48} (FAR floor {@code Y=112}).</p>
 */
final class PortalGeometryTest {

    private static final int ORIGIN_X = 100;
    private static final int FLOOR_Y = 64;
    private static final int ORIGIN_Z = 10;
    private static final int LENGTH = 32;
    private static final int WIDTH = 3;
    private static final int HEIGHT = 3;
    private static final int DELTA_Y = 48;

    private static final double MID_X = 116.0;
    private static final double NEAR_Y = FLOOR_Y + 1;          // 65 — standing on the near floor
    private static final double FAR_Y = FLOOR_Y + DELTA_Y + 1; // 113 — standing on the far floor
    private static final double IN_Z = ORIGIN_Z + 1;           // 11 — corridor centre line

    private static PortalGeometry geo() {
        return new PortalGeometry(ORIGIN_X, FLOOR_Y, ORIGIN_Z, LENGTH, WIDTH, HEIGHT, DELTA_Y);
    }

    // ---- layout --------------------------------------------------------------

    @Test
    @DisplayName("midpoint is the corridor's exact centre, and lands off a block boundary")
    void midpoint() {
        assertEquals(MID_X, geo().midX(), 1e-9);
    }

    @Test
    @DisplayName("doors sit at the first and last interior columns, centred on the width")
    void doorPositions() {
        PortalGeometry g = geo();
        assertEquals(ORIGIN_X, g.nearDoorX());
        assertEquals(ORIGIN_X + LENGTH - 1, g.farDoorX());
        assertEquals(ORIGIN_Z + WIDTH / 2, g.doorZ());
    }

    @Test
    @DisplayName("floorYOf separates the copies by deltaY; highestBlockY is the far ceiling")
    void copyHeights() {
        PortalGeometry g = geo();
        assertEquals(FLOOR_Y, g.floorYOf(PortalGeometry.COPY_NEAR));
        assertEquals(FLOOR_Y + DELTA_Y, g.floorYOf(PortalGeometry.COPY_FAR));
        assertEquals(FLOOR_Y + DELTA_Y + HEIGHT + 1, g.highestBlockY());
    }

    // ---- copy ownership by Y -------------------------------------------------

    @Test
    @DisplayName("copyAt maps each interior Y window to its copy, and the gap between to none")
    void copyAt() {
        PortalGeometry g = geo();
        assertEquals(PortalGeometry.COPY_NEAR, g.copyAt(NEAR_Y));
        assertEquals(PortalGeometry.COPY_NEAR, g.copyAt(FLOOR_Y + HEIGHT));
        assertEquals(PortalGeometry.COPY_FAR, g.copyAt(FAR_Y));
        assertEquals(PortalGeometry.COPY_FAR, g.copyAt(FLOOR_Y + DELTA_Y + HEIGHT));
        assertEquals(PortalGeometry.COPY_NONE, g.copyAt(FLOOR_Y + 20));   // between the copies
        assertEquals(PortalGeometry.COPY_NONE, g.copyAt(FLOOR_Y - 20));   // below both
        assertEquals(PortalGeometry.COPY_NONE, g.copyAt(FLOOR_Y + DELTA_Y + 40)); // above both
    }

    // ---- the invariant -------------------------------------------------------

    @Test
    @DisplayName("a position already in the copy its side demands needs no shift")
    void correctSideNoShift() {
        PortalGeometry g = geo();
        assertEquals(0, g.requiredShift(MID_X - 6, NEAR_Y, IN_Z));  // before midpoint, in NEAR
        assertEquals(0, g.requiredShift(MID_X + 6, FAR_Y, IN_Z));   // past midpoint, in FAR
    }

    @Test
    @DisplayName("crossing the midpoint lifts NEAR→FAR, crossing back drops FAR→NEAR")
    void wrongSideShifts() {
        PortalGeometry g = geo();
        assertEquals(DELTA_Y, g.requiredShift(MID_X + 0.1, NEAR_Y, IN_Z));
        assertEquals(-DELTA_Y, g.requiredShift(MID_X - 0.1, FAR_Y, IN_Z));
    }

    @Test
    @DisplayName("the midpoint tie resolves to NEAR, so exactly one branch can fire")
    void midpointTieBelongsToNear() {
        PortalGeometry g = geo();
        assertEquals(0, g.requiredShift(MID_X, NEAR_Y, IN_Z));         // standing exactly on the line
        assertEquals(-DELTA_Y, g.requiredShift(MID_X, FAR_Y, IN_Z));   // ...so FAR must come down
    }

    @Test
    @DisplayName("applying the shift twice is a no-op — the rule is idempotent, not an event")
    void idempotent() {
        PortalGeometry g = geo();
        double x = MID_X + 0.1;

        int first = g.requiredShift(x, NEAR_Y, IN_Z);
        assertEquals(DELTA_Y, first);

        // After the shift the player is in FAR, past the midpoint: settled, nothing more to do.
        assertEquals(0, g.requiredShift(x, NEAR_Y + first, IN_Z));
    }

    @Test
    @DisplayName("a fast crossing shifts the same way however far past the line it lands")
    void speedIndependent() {
        PortalGeometry g = geo();
        // One tick of elytra flight can clear many blocks; the rule reads position, not motion.
        assertEquals(DELTA_Y, g.requiredShift(MID_X + 0.05, NEAR_Y, IN_Z));
        assertEquals(DELTA_Y, g.requiredShift(MID_X + 12.0, NEAR_Y, IN_Z));
    }

    // ---- outside the corridor ------------------------------------------------

    @Test
    @DisplayName("positions outside the corridor footprint are never shifted")
    void outsideCorridorNeverShifts() {
        PortalGeometry g = geo();
        // Beyond the far door — the pocket area in FAR, solid rock in NEAR.
        assertEquals(0, g.requiredShift(ORIGIN_X + LENGTH + 6, FAR_Y, IN_Z));
        // Before the near door — the open world in NEAR.
        assertEquals(0, g.requiredShift(ORIGIN_X - 6, NEAR_Y, IN_Z));
        // Beside the corridor, past the midpoint: outside the width, so left alone.
        assertEquals(0, g.requiredShift(MID_X + 4, NEAR_Y, ORIGIN_Z + WIDTH + 8));
        // Right height, right X, but in neither copy's Y window.
        assertEquals(0, g.requiredShift(MID_X + 4, FLOOR_Y + 20, IN_Z));
    }

    @Test
    @DisplayName("insideCorridor needs both the footprint and a copy's Y window")
    void insideCorridor() {
        PortalGeometry g = geo();
        assertTrue(g.insideCorridor(MID_X, NEAR_Y, IN_Z));
        assertTrue(g.insideCorridor(MID_X, FAR_Y, IN_Z));
        assertFalse(g.insideCorridor(MID_X, FLOOR_Y + 20, IN_Z));            // between copies
        assertFalse(g.insideCorridor(ORIGIN_X - 6, NEAR_Y, IN_Z));           // outside footprint
    }

    // ---- validation ----------------------------------------------------------

    @Test
    @DisplayName("a corridor shorter than MIN_LENGTH is rejected — the midpoint must be light-isolated")
    void rejectsShortCorridor() {
        assertThrows(IllegalArgumentException.class,
            () -> new PortalGeometry(ORIGIN_X, FLOOR_Y, ORIGIN_Z, PortalGeometry.MIN_LENGTH - 1,
                WIDTH, HEIGHT, DELTA_Y));
    }

    @Test
    @DisplayName("a deltaY that would crowd the two copies together is rejected")
    void rejectsCrowdedCopies() {
        assertThrows(IllegalArgumentException.class,
            () -> new PortalGeometry(ORIGIN_X, FLOOR_Y, ORIGIN_Z, LENGTH, WIDTH, HEIGHT,
                HEIGHT + 2 + PortalGeometry.MIN_COPY_GAP - 1));
    }

    @Test
    @DisplayName("undersized width or height is rejected")
    void rejectsUndersizedCrossSection() {
        assertThrows(IllegalArgumentException.class,
            () -> new PortalGeometry(ORIGIN_X, FLOOR_Y, ORIGIN_Z, LENGTH,
                PortalGeometry.MIN_WIDTH - 1, HEIGHT, DELTA_Y));
        assertThrows(IllegalArgumentException.class,
            () -> new PortalGeometry(ORIGIN_X, FLOOR_Y, ORIGIN_Z, LENGTH, WIDTH,
                PortalGeometry.MIN_HEIGHT - 1, DELTA_Y));
    }
}
