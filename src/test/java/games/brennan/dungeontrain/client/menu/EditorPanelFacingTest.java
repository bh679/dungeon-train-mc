package games.brennan.dungeontrain.client.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The editor's world-space panels hold a fixed facing instead of billboarding to the camera — these
 * pin which way each family faces, and that the bases stay orthonormal so the raycasts' dot-product
 * hit math keeps working.
 */
class EditorPanelFacingTest {

    private static final double EPS = 1.0e-9;

    @Test
    void plotPanelsFaceMinusX() {
        Vec3[] b = EditorPanelFacing.plotPanel();
        assertVec(new Vec3(-1, 0, 0), b[2]);
        assertOrthonormal(b);
    }

    @Test
    void xRowDoorPanelsFacePlusXWithLeftAtPlusZ() {
        Vec3[] b = EditorPanelFacing.doorPanel(false);
        assertVec(new Vec3(1, 0, 0), b[2]);
        // The Welcome panel sits on the reader's left: -right.
        assertVec(new Vec3(0, 0, 1), b[0].scale(-1));
        assertOrthonormal(b);
    }

    @Test
    void zRowDoorPanelsFacePlusZWithLeftAtMinusX() {
        Vec3[] b = EditorPanelFacing.doorPanel(true);
        assertVec(new Vec3(0, 0, 1), b[2]);
        assertVec(new Vec3(-1, 0, 0), b[0].scale(-1));
        assertOrthonormal(b);
    }

    @Test
    void callersCannotCorruptTheSharedBasis() {
        Vec3[] b = EditorPanelFacing.plotPanel();
        b[2] = Vec3.ZERO;
        assertArrayEquals(new double[]{-1, 0, 0}, toArray(EditorPanelFacing.plotPanel()[2]), EPS);
    }

    @Test
    void onlyTracksAndPortalsAreZRows() {
        assertTrue(EditorPanelFacing.isZRow(PlotCategory.TRACKS.id()));
        assertTrue(EditorPanelFacing.isZRow(PlotCategory.PORTALS.id()));
        assertFalse(EditorPanelFacing.isZRow(PlotCategory.CARRIAGES.id()));
        assertFalse(EditorPanelFacing.isZRow(""));
        assertFalse(EditorPanelFacing.isZRow("stages"));
        assertFalse(EditorPanelFacing.isZRow(null));
    }

    private static void assertOrthonormal(Vec3[] b) {
        for (Vec3 v : b) assertEquals(1.0, v.length(), EPS);
        assertEquals(0.0, b[0].dot(b[1]), EPS);
        assertEquals(0.0, b[0].dot(b[2]), EPS);
        assertEquals(0.0, b[1].dot(b[2]), EPS);
        assertVec(new Vec3(0, 1, 0), b[1]);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertArrayEquals(toArray(expected), toArray(actual), EPS);
    }

    private static double[] toArray(Vec3 v) {
        return new double[]{v.x, v.y, v.z};
    }
}
