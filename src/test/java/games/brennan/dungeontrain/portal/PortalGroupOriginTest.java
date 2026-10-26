package games.brennan.dungeontrain.portal;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the shell corner is believed over the bounding box. A box only ever grows outward from the
 * shell, so the shell corner sits at or above the box corner; one well below it is a different shell.
 */
class PortalGroupOriginTest {

    private static final Vector3d BOX = new Vector3d(100.0, 70.0, 3.0);

    @Test
    @DisplayName("a clean box agrees with the shell, give or take the pose lag")
    void cleanBoxTrusted() {
        assertTrue(PortalGroupOrigin.trusted(new Vector3d(100.0, 70.0, 3.0), BOX));
        assertTrue(PortalGroupOrigin.trusted(new Vector3d(99.7, 69.8, 2.6), BOX));
    }

    @Test
    @DisplayName("a box grown by a bridge block sits below the shell, and the shell wins")
    void grownBoxTrusted() {
        // A block built against the -Z wall and one under the floor.
        assertTrue(PortalGroupOrigin.trusted(new Vector3d(100.0, 71.0, 4.0), BOX));
    }

    @Test
    @DisplayName("a shell corner a block below the box is not this shell")
    void shellBelowBoxDistrusted() {
        assertFalse(PortalGroupOrigin.trusted(new Vector3d(100.0, 70.0, 2.0), BOX));
        assertFalse(PortalGroupOrigin.trusted(new Vector3d(98.0, 70.0, 3.0), BOX));
        assertFalse(PortalGroupOrigin.trusted(new Vector3d(100.0, 69.0, 3.0), BOX));
    }
}
