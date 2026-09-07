package games.brennan.dungeontrain.ship.sable;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The snapshot a stale ship handle answers from. Plain doubles, no Minecraft bootstrap. */
final class LastKnownPoseTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("nothing recorded reads as the degenerate zero box, like Sable pre-tick")
    void emptyIsDegenerate() {
        LastKnownPose last = new LastKnownPose();
        assertFalse(last.hasPose());
        assertFalse(last.hasAabb());
        AABBd box = last.aabb();
        assertEquals(0.0, box.minX, EPS);
        assertEquals(0.0, box.maxX, EPS);
    }

    @Test
    @DisplayName("pose and box round-trip")
    void roundTrip() {
        LastKnownPose last = new LastKnownPose();
        last.recordPose(new Vector3d(100, 80, 3), new Quaterniond(), new Vector3d(7, 1, 2));
        last.recordAabb(90, 78, -1, 110, 88, 7);
        assertTrue(last.hasPose());
        assertTrue(last.hasAabb());
        assertEquals(100, last.position().x, EPS);
        assertEquals(7, last.rotationPoint().x, EPS);
        assertEquals(1.0, last.orientation().w, EPS);
        AABBd box = last.aabb();
        assertEquals(90, box.minX, EPS);
        assertEquals(88, box.maxY, EPS);
    }

    @Test
    @DisplayName("identity rotation: world = position + (model - rotationPoint), and back")
    void identityTransform() {
        LastKnownPose last = new LastKnownPose();
        last.recordPose(new Vector3d(1000, 80, 3.5), new Quaterniond(), new Vector3d(20, 5, 5));
        Vector3d world = last.shipToWorld(new Vector3d(25, 6, 5));
        assertEquals(1005, world.x, EPS);
        assertEquals(81, world.y, EPS);
        assertEquals(3.5, world.z, EPS);
        Vector3d model = last.worldToShip(new Vector3d(1005, 81, 3.5));
        assertEquals(25, model.x, EPS);
        assertEquals(6, model.y, EPS);
        assertEquals(5, model.z, EPS);
    }

    @Test
    @DisplayName("a quarter turn about Y rotates the model offset")
    void rotatedTransform() {
        LastKnownPose last = new LastKnownPose();
        Quaterniond quarter = new Quaterniond().rotateY(Math.PI / 2);
        last.recordPose(new Vector3d(0, 0, 0), quarter, new Vector3d(0, 0, 0));
        Vector3d world = last.shipToWorld(new Vector3d(1, 0, 0));
        assertEquals(0, world.x, EPS);
        assertEquals(-1, world.z, EPS);
        Vector3d back = last.worldToShip(new Vector3d(world));
        assertEquals(1, back.x, EPS);
        assertEquals(0, back.z, EPS);
    }
}
