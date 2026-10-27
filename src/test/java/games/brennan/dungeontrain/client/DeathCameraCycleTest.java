package games.brennan.dungeontrain.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathCameraCycleTest {

    private static List<String> labels(List<DeathShot> shots) {
        return shots.stream().map(DeathShot::label).toList();
    }

    @Test
    void fullSceneHasEveryShotInOrder() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, true, true, true);
        assertEquals(List.of("POV", "spot", "killer", "mob", "carriage", "leaving"), labels(seq).subList(0, 6));
        assertEquals(DeathCameraCycle.TRAIN_SHOTS.size(), seq.size() - 6);
        assertEquals(6, DeathCameraCycle.loopStart(seq));
    }

    @Test
    void voidDeathSkipsTheDeathSpot() {
        assertEquals(List.of("POV", "killer", "carriage", "leaving"),
            labels(DeathCameraCycle.buildSequence(true, true, false, true)).subList(0, 4));
    }

    @Test
    void noKillerNoMobOffTrainStillLeavesTheTrainShots() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, false, false, false);
        assertEquals(List.of("POV", "spot", "leaving"), labels(seq).subList(0, 3));
        assertEquals(3, DeathCameraCycle.loopStart(seq));
    }

    @Test
    void sequenceRunsOnceThenLoopsTheTrainShots() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, false, false, true);
        int loop = DeathCameraCycle.loopStart(seq);
        assertEquals(1, DeathCameraCycle.nextIndex(seq, 0));
        assertEquals(loop, DeathCameraCycle.nextIndex(seq, seq.size() - 1));
        assertEquals(seq.size() - 1, DeathCameraCycle.previousIndex(seq, 1), "Left from the first camera shot wraps to the last");
        assertEquals(1, DeathCameraCycle.previousIndex(seq, 2));
    }

    @Test
    void longestClearRunWrapsAroundTheCircle() {
        assertArrayEquals(new int[] {0, 0}, DeathCameraCycle.longestClearRun(new boolean[] {false, false, false, false}));
        assertArrayEquals(new int[] {0, 4}, DeathCameraCycle.longestClearRun(new boolean[] {true, true, true, true}));
        assertArrayEquals(new int[] {1, 2}, DeathCameraCycle.longestClearRun(new boolean[] {false, true, true, false}));
        // Clear at the end and the start: one run of 2 wrapping from index 5.
        assertArrayEquals(new int[] {5, 2}, DeathCameraCycle.longestClearRun(new boolean[] {true, false, true, false, false, true}));
    }

    @Test
    void orbitLensSitsOnTheCircleAtTheGivenHeight() {
        Vector3d lens = DeathCameraCycle.orbitLens(new Vector3d(10, 64, -5), 6.0, 3.0, Math.PI / 2);
        assertEquals(10, lens.x, 1e-9);
        assertEquals(67, lens.y, 1e-9);
        assertEquals(1, lens.z, 1e-9);
    }

    @Test
    void dollyEasesFromStartToEnd() {
        Vector3d a = new Vector3d(0, 0, 0);
        Vector3d b = new Vector3d(10, 0, 0);
        assertEquals(0, DeathCameraCycle.dollyAt(a, b, 0).x, 1e-9);
        assertEquals(5, DeathCameraCycle.dollyAt(a, b, 0.5).x, 1e-9);
        assertEquals(10, DeathCameraCycle.dollyAt(a, b, 1).x, 1e-9);
        assertEquals(10, DeathCameraCycle.dollyAt(a, b, 2).x, 1e-9);
        assertTrue(DeathCameraCycle.dollyAt(a, b, 0.1).x < 1.0, "slow start");
    }

    @Test
    void fadeGoesBlackAtTheCutAndClearsAfterIt() {
        int hold = 60;
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(0, hold, true), 1e-6);
        assertEquals(0.0f, DeathCameraCycle.blackAlpha(DeathCameraCycle.FADE_TICKS, hold, true), 1e-6);
        assertEquals(1.0f, DeathCameraCycle.blackAlpha(hold, hold, true), 1e-6);
        assertEquals(0.0f, DeathCameraCycle.blackAlpha(0, 40, false), 1e-6, "POV opens on the live world");
    }

    @Test
    void trainShotsStandClearOfTheTrainAndLookDown() {
        for (DeathShot shot : DeathCameraCycle.TRAIN_SHOTS) {
            assertTrue(Math.abs(shot.offset().z) >= 4.0, shot.label() + " must stand clear of the 7-wide train");
            Vector3d lens = DeathCameraCycle.lensFor(new Vector3d(), shot.offset());
            CinematicCameraController.Pose pose = DeathCameraCycle.poseFor(new Vec3(lens.x, lens.y, lens.z), new Vec3(0, 1, 0));
            assertTrue(pose.pitch() > 0 && pose.pitch() < 80, shot.label() + " pitch " + pose.pitch());
        }
    }
}
