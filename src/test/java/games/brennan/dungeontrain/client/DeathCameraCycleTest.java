package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathCameraCycleTest {

    private static final List<DeathShot> SEQ = List.of(
        DeathShot.pov(40),
        DeathShot.trainLocked(4, 1.8, 6, 60, "a"),
        DeathShot.trainLocked(-8, 6, -4, 60, "b"),
        DeathShot.transition(-3, 2.5, 8, 100, "t"),
        DeathShot.world(0, 3, 14, 120, "w1"),
        DeathShot.world(0, 8, 18, 120, "w2"));

    @Test
    void sequenceRunsOnceThenLoopsTheWorldShots() {
        assertEquals(4, DeathCameraCycle.loopStart(SEQ));
        assertEquals(1, DeathCameraCycle.nextIndex(SEQ, 0));
        assertEquals(4, DeathCameraCycle.nextIndex(SEQ, 3));
        assertEquals(5, DeathCameraCycle.nextIndex(SEQ, 4));
        assertEquals(4, DeathCameraCycle.nextIndex(SEQ, 5), "past the end: back to the first world shot");
    }

    @Test
    void leftNeverReturnsToThePovAndWrapsWithinTheLoop() {
        assertEquals(1, DeathCameraCycle.previousIndex(SEQ, 2));
        assertEquals(5, DeathCameraCycle.previousIndex(SEQ, 1), "from the first camera shot, Left wraps to the last");
        assertEquals(4, DeathCameraCycle.previousIndex(SEQ, 5));
        assertEquals(3, DeathCameraCycle.previousIndex(SEQ, 4));
    }

    @Test
    void sequenceWithoutWorldShotsHoldsItsLastShot() {
        List<DeathShot> seq = List.of(DeathShot.pov(40), DeathShot.trainLocked(4, 2, 6, 60, "a"));
        assertEquals(-1, DeathCameraCycle.loopStart(seq));
        assertEquals(1, DeathCameraCycle.nextIndex(seq, 1));
    }

    @Test
    void fadeGoesBlackAtTheCutAndClearsAfterIt() {
        int hold = 60;
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(0, hold, true), 1e-6);
        assertEquals(0.0f, DeathCameraCycle.blackAlpha(DeathCameraCycle.FADE_TICKS, hold, true), 1e-6);
        assertEquals(0.0f, DeathCameraCycle.blackAlpha(30, hold, true), 1e-6);
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(hold, hold, true), 1e-6);
        assertEquals(0.5f, DeathCameraCycle.blackAlpha(hold - DeathCameraCycle.FADE_TICKS / 2.0, hold, true), 1e-6);
    }

    @Test
    void povOpensOnTheLiveWorldWithoutAFadeIn() {
        assertEquals(0.0f, DeathCameraCycle.blackAlpha(0, 40, false), 1e-6);
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(40, 40, false), 1e-6);
    }

    @Test
    void lensIsBodyPlusOffset() {
        Vector3d lens = DeathCameraCycle.lensFor(new Vector3d(100, 64, -3), new Vec3(7, 2.5, 8));
        assertEquals(107, lens.x, 1e-9);
        assertEquals(66.5, lens.y, 1e-9);
        assertEquals(5, lens.z, 1e-9);
    }

    @Test
    void shippedShotsLookDownAtTheBodyFromDistinctSidesAndStayClearOfTheTrain() {
        Vector3d body = new Vector3d(0, 0, 0);
        Vec3 target = new Vec3(0, 1, 0);
        float lastYaw = Float.NaN;
        for (DeathShot shot : DeathCameraCycle.DEFAULT_SEQUENCE) {
            if (shot.kind() == DeathShot.Kind.POV) continue;
            assertTrue(Math.abs(shot.offset().z) >= 4.0, shot.label() + " must stand clear of the 7-wide train");
            Vector3d lens = DeathCameraCycle.lensFor(body, shot.offset());
            CinematicCameraController.Pose pose = DeathCameraCycle.poseFor(new Vec3(lens.x, lens.y, lens.z), target);
            assertTrue(pose.pitch() > 0 && pose.pitch() < 70, shot.label() + " pitch " + pose.pitch());
            if (!Float.isNaN(lastYaw)) assertNotEquals(Math.round(lastYaw), Math.round(pose.yaw()), shot.label());
            lastYaw = pose.yaw();
        }
    }
}
