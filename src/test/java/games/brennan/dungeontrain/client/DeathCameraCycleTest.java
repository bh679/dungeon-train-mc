package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathCameraCycleTest {

    @Test
    void threeShotsCycleForwardAndWrap() {
        assertEquals(3, DeathCameraCycle.ANGLE_COUNT);
        assertEquals(1, DeathCameraCycle.nextIndex(0));
        assertEquals(2, DeathCameraCycle.nextIndex(1));
        assertEquals(0, DeathCameraCycle.nextIndex(2));
    }

    @Test
    void leftArrowGoesBackAndWraps() {
        assertEquals(2, DeathCameraCycle.previousIndex(0));
        assertEquals(0, DeathCameraCycle.previousIndex(1));
        assertEquals(1, DeathCameraCycle.previousIndex(2));
    }

    @Test
    void lensIsBodyPlusOffset() {
        Vector3d lens = DeathCameraCycle.lensFor(new Vector3d(100, 64, -3), new Vec3(7, 2.5, 8));
        assertEquals(107, lens.x, 1e-9);
        assertEquals(66.5, lens.y, 1e-9);
        assertEquals(5, lens.z, 1e-9);
    }

    @Test
    void everyShotLooksDownAtTheBodyFromAboveAndFromADistinctSide() {
        Vector3d body = new Vector3d(0, 0, 0);
        Vec3 target = new Vec3(0, 1, 0);
        float[] yaws = new float[DeathCameraCycle.ANGLE_COUNT];
        for (int i = 0; i < DeathCameraCycle.ANGLE_COUNT; i++) {
            Vector3d lens = DeathCameraCycle.lensFor(body, DeathCameraCycle.OFFSETS[i]);
            CinematicCameraController.Pose pose =
                DeathCameraCycle.poseFor(new Vec3(lens.x, lens.y, lens.z), target);
            assertTrue(pose.pitch() > 0, "shot " + i + " should look down (pitch " + pose.pitch() + ")");
            assertTrue(pose.pitch() < 60, "shot " + i + " should not be a top-down view");
            yaws[i] = pose.yaw();
        }
        assertNotEquals(Math.round(yaws[0]), Math.round(yaws[1]));
        assertNotEquals(Math.round(yaws[1]), Math.round(yaws[2]));
        assertNotEquals(Math.round(yaws[0]), Math.round(yaws[2]));
    }

    @Test
    void shotHoldIsFourSeconds() {
        assertEquals(80, DeathCameraCycle.ANGLE_TICKS);
    }
}
