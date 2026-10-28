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
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, true, true, true, true);
        assertEquals(List.of("POV", "spot", "killer", "mob", "carriage"), labels(seq).subList(0, 5));
        assertEquals(DeathCameraCycle.TRAIN_SHOTS.size() + DeathCameraCycle.TUNNEL_SHOTS.size() + 2, seq.size() - 5);
        assertEquals(5, DeathCameraCycle.loopStart(seq));
        assertEquals(List.of("feature", "mob-arc"), labels(seq).subList(seq.size() - 2, seq.size()), "subject shots close the loop");
    }

    @Test
    void voidDeathSkipsTheDeathSpot() {
        assertEquals(List.of("POV", "killer", "carriage"),
            labels(DeathCameraCycle.buildSequence(true, true, false, true)).subList(0, 3));
    }

    @Test
    void noKillerNoMobOffTrainStillLeavesTheTrainShots() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, false, false, false);
        assertEquals(List.of("POV", "spot", "leaving"), labels(seq).subList(0, 3));
        assertEquals(3, DeathCameraCycle.loopStart(seq));
        assertEquals("mob-arc", seq.get(seq.size() - 1).label(), "no feature listed: the mob arc alone closes the loop");
        assertTrue(labels(seq).contains("eyes-forward"));
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
    void orbitsHoldThreeSecondsAndTheCarriageShotZoomsThenHolds() {
        for (DeathShot shot : DeathCameraCycle.buildSequence(false, true, true, true)) {
            if (shot.kind() == DeathShot.Kind.ORBIT) assertEquals(60, shot.holdTicks(), shot.label());
            if (shot.kind() == DeathShot.Kind.DOLLY) assertEquals(DeathCameraCycle.DOLLY_TICKS + 60, shot.holdTicks());
        }
    }

    @Test
    void orbitLensSitsOnTheCircleAtTheGivenHeight() {
        Vector3d lens = DeathCameraCycle.orbitLens(new Vector3d(10, 64, -5), 6.0, 3.0, Math.PI / 2);
        assertEquals(10, lens.x, 1e-9);
        assertEquals(67, lens.y, 1e-9);
        assertEquals(1, lens.z, 1e-9);
    }

    @Test
    void dollyMovesAtConstantSpeedThenSlowsIntoTheEnd() {
        Vector3d a = new Vector3d(0, 0, 0);
        Vector3d b = new Vector3d(10, 0, 0);
        assertEquals(0, DeathCameraCycle.dollyAt(a, b, 0).x, 1e-9);
        assertEquals(10, DeathCameraCycle.dollyAt(a, b, 1).x, 1e-9);
        assertEquals(10, DeathCameraCycle.dollyAt(a, b, 2).x, 1e-9);
        double early = DeathCameraCycle.dollyAt(a, b, 0.1).x - DeathCameraCycle.dollyAt(a, b, 0.0).x;
        double late = DeathCameraCycle.dollyAt(a, b, 1.0).x - DeathCameraCycle.dollyAt(a, b, 0.9).x;
        assertTrue(early > 1.5, "no slow start: moved " + early + " in the first tenth");
        assertTrue(late < early / 5, "slows into the end: " + late + " vs " + early);
    }

    @Test
    void arcKeepsRadiusAndHeightAndTurnsTheSweep() {
        Vector3d centre = new Vector3d(100, 64, 0);
        Vector3d start = new Vector3d(120, 80, 0);
        Vector3d end = DeathCameraCycle.arcAt(centre, start, 90.0, 1.0);
        assertEquals(100, end.x, 1e-6);
        assertEquals(80, end.y, 1e-9);
        assertEquals(20, end.z, 1e-6);
        assertEquals(start.x, DeathCameraCycle.arcAt(centre, start, 90.0, 0.0).x, 1e-9);
        // Constant speed: halfway through the sweep is 45 degrees round.
        Vector3d mid = DeathCameraCycle.arcAt(centre, start, 90.0, 0.5);
        assertEquals(100 + 20 * Math.cos(Math.PI / 4), mid.x, 1e-6);
        assertEquals(20 * Math.sin(Math.PI / 4), mid.z, 1e-6);
    }

    @Test
    void trainShotsAreAllMovingOrAMobArcAndStartTheLoop() {
        List<DeathShot> seq = DeathCameraCycle.buildSequence(false, false, false, true);
        int loop = DeathCameraCycle.loopStart(seq);
        assertTrue(loop > 0);
        for (DeathShot shot : DeathCameraCycle.TRAIN_SHOTS) {
            assertTrue(shot.kind() == DeathShot.Kind.WORLD_DOLLY || shot.kind() == DeathShot.Kind.WORLD_ARC, shot.label());
        }
        for (DeathShot shot : DeathCameraCycle.LOOP_TAIL) assertTrue(shot.kind().isLoop() && !shot.kind().isOpenAir(), shot.label());
        for (DeathShot shot : DeathCameraCycle.TUNNEL_SHOTS) assertTrue(shot.kind().isTunnel() && shot.kind().isLoop(), shot.label());
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
