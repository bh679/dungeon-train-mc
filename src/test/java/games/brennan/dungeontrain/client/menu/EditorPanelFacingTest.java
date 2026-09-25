package games.brennan.dungeontrain.client.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The editor panels face the player when they appear (and after a teleport), then hold still;
 * {@code ↻} re-faces, shift + {@code ↻} snaps to the grid. These pin that lifecycle, the grid
 * defaults, and that every basis stays orthonormal for the raycasts' dot-product hit math.
 */
class EditorPanelFacingTest {

    private static final double EPS = 1.0e-9;
    private static final BlockPos KEY = new BlockPos(10, 250, 20);
    private static final Vec3 ANCHOR = Vec3.atCenterOf(KEY);

    @BeforeEach
    @AfterEach
    void clear() {
        EditorPanelFacing.clearAll();
    }

    @Test
    @DisplayName("A panel faces the camera the first time it is asked, then holds that facing")
    void capturesOnFirstAskThenHolds() {
        Vec3[] first = EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0));
        assertVec(new Vec3(-1, 0, 0), first[2]);
        assertOrthonormal(first);

        // Walk round to the other side — the panel does not follow.
        Vec3[] later = EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(0, 3, 7));
        assertVec(new Vec3(-1, 0, 0), later[2]);
    }

    @Test
    @DisplayName("The face button turns the panel to the camera's current side")
    void faceNowRefaces() {
        EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0));
        EditorPanelFacing.faceNow(KEY, ANCHOR, ANCHOR.add(0, 0, 9));
        assertVec(new Vec3(0, 0, 1), EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0))[2]);
    }

    @Test
    @DisplayName("Shift + face button snaps the panel to its grid default")
    void resetSnapsToGrid() {
        EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(3, 0, 4));
        EditorPanelFacing.reset(KEY, EditorPanelFacing.doorPanel(false));
        assertVec(new Vec3(1, 0, 0), EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0))[2]);
    }

    @Test
    @DisplayName("After clearAll (a teleport) every panel faces the camera afresh")
    void clearAllRecaptures() {
        EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0));
        EditorPanelFacing.clearAll();
        assertVec(new Vec3(0, 0, -1), EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(0, 0, -6))[2]);
    }

    @Test
    @DisplayName("Panels sharing an anchor share a facing; other anchors are independent")
    void keysShareOrNot() {
        EditorPanelFacing.faceNow(KEY, ANCHOR, ANCHOR.add(0, 0, 9));
        // A companion asks with the same anchor block and a different camera — it still matches.
        assertVec(new Vec3(0, 0, 1), EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-9, 0, 0))[2]);
        BlockPos other = KEY.offset(0, 0, 40);
        assertVec(new Vec3(-1, 0, 0),
            EditorPanelFacing.basis(other, Vec3.atCenterOf(other), Vec3.atCenterOf(other).add(-4, 0, 0))[2]);
    }

    @Test
    @DisplayName("Callers mutating a returned basis cannot corrupt the held one")
    void returnedBasisIsACopy() {
        Vec3[] b = EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(-5, 0, 0));
        b[2] = Vec3.ZERO;
        assertVec(new Vec3(-1, 0, 0), EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR)[2]);
        Vec3[] grid = EditorPanelFacing.plotPanel();
        grid[2] = Vec3.ZERO;
        assertVec(new Vec3(-1, 0, 0), EditorPanelFacing.plotPanel()[2]);
    }

    @Test
    @DisplayName("A camera straight above the anchor still yields a valid upright basis")
    void cameraAboveAnchor() {
        assertOrthonormal(EditorPanelFacing.basis(KEY, ANCHOR, ANCHOR.add(0, 10, 0)));
    }

    @Test
    @DisplayName("Grid defaults: plot panels face -X; door panels +X, or +Z on Z-rows, with left on +Z / -X")
    void gridDefaults() {
        assertVec(new Vec3(-1, 0, 0), EditorPanelFacing.plotPanel()[2]);
        Vec3[] xDoor = EditorPanelFacing.doorPanel(false);
        assertVec(new Vec3(1, 0, 0), xDoor[2]);
        assertVec(new Vec3(0, 0, 1), xDoor[0].scale(-1));
        Vec3[] zDoor = EditorPanelFacing.doorPanel(true);
        assertVec(new Vec3(0, 0, 1), zDoor[2]);
        assertVec(new Vec3(-1, 0, 0), zDoor[0].scale(-1));
        assertOrthonormal(EditorPanelFacing.plotPanel());
        assertOrthonormal(xDoor);
        assertOrthonormal(zDoor);
    }

    @Test
    @DisplayName("Only tracks and dimensional carriages are Z-rows")
    void onlyTracksAndPortalsAreZRows() {
        assertTrue(EditorPanelFacing.isZRow(PlotCategory.TRACKS.id()));
        assertTrue(EditorPanelFacing.isZRow(PlotCategory.PORTALS.id()));
        assertFalse(EditorPanelFacing.isZRow(PlotCategory.CARRIAGES.id()));
        assertFalse(EditorPanelFacing.isZRow(""));
        assertFalse(EditorPanelFacing.isZRow("stages"));
        assertFalse(EditorPanelFacing.isZRow(null));
    }

    @Test
    @DisplayName("Teleport detection: a big jump or a dimension change, never the first tick or a walk")
    void teleportDetection() {
        Vec3 here = new Vec3(0, 250, 0);
        assertFalse(EditorPanelFacingEvents.isTeleport(null, null, here, "a"));
        assertFalse(EditorPanelFacingEvents.isTeleport(here, "a", here.add(0.5, 0, 0.3), "a"));
        assertTrue(EditorPanelFacingEvents.isTeleport(here, "a", here.add(40, 0, 0), "a"));
        assertTrue(EditorPanelFacingEvents.isTeleport(here, "a", here, "b"));
    }

    private static void assertOrthonormal(Vec3[] b) {
        for (Vec3 v : b) assertEquals(1.0, v.length(), EPS);
        assertEquals(0.0, b[0].dot(b[1]), EPS);
        assertEquals(0.0, b[0].dot(b[2]), EPS);
        assertEquals(0.0, b[1].dot(b[2]), EPS);
        assertVec(new Vec3(0, 1, 0), b[1]);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertArrayEquals(new double[]{expected.x, expected.y, expected.z},
            new double[]{actual.x, actual.y, actual.z}, EPS);
    }
}
