package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framing of the Train Builder's arrival shot.
 *
 * <p>Two things have to hold for the shot to do its job: the aim point has to land on the
 * template (not near it, and never on the void beyond the platform), and the spawn rotation has
 * to point the player at the same thing the camera was looking at — otherwise control returns
 * with the build behind them, which is the bug this replaced.</p>
 */
final class BuilderCinematicTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final double EPS = 1.0e-6;

    @Test
    @DisplayName("The focus sits at the centre of the parked carriages")
    void focusCentresOnTheBuildVolumes() {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(3, DIMS);
        Vec3 focus = BuilderCinematic.focus(3, DIMS);

        assertEquals((boxes.get(0).minX() + boxes.get(2).maxX() + 1) / 2.0, focus.x, EPS);
        assertEquals(BuilderWorldLayout.TRAIN_Y + DIMS.height() / 2.0, focus.y, EPS);
        assertEquals(DIMS.width() / 2.0, focus.z, EPS);
    }

    @Test
    @DisplayName("A single carriage focuses on that carriage, not on where three would have been")
    void oneCarriageFocusesOnItself() {
        BoundingBox only = BuilderBounds.buildVolumes(1, DIMS).get(0);
        Vec3 focus = BuilderCinematic.focus(1, DIMS);
        assertEquals((only.minX() + only.maxX() + 1) / 2.0, focus.x, EPS);
        assertTrue(focus.x > only.minX() && focus.x < only.maxX() + 1, "inside the carriage");
    }

    @Test
    @DisplayName("With no carriages the shot holds on the track instead — the void is not a subject")
    void zeroCarriagesFallBackToTheTrack() {
        TrackGeometry g = TrackGeometry.from(DIMS, BuilderWorldLayout.TRAIN_Y);
        Vec3 focus = BuilderCinematic.focus(0, DIMS);

        assertEquals(g.trackCenterZ() + 0.5, focus.z, EPS);
        assertTrue(focus.y > g.railY(), "above the rails, not buried in the bed");
        assertTrue(BuilderWorldLayout.inPlatform((int) Math.floor(focus.x), (int) Math.floor(focus.z)),
                "on the platform");
    }

    @Test
    @DisplayName("The platform spawn faces the train — the yaw-0 default pointed straight away from it")
    void spawnFacesTheTemplate() {
        for (int carriages : new int[] { 0, 1, 3 }) {
            float yaw = BuilderCinematic.spawnFacing(carriages, DIMS)[0];
            // The spawn sits on +Z of the track and the template is at the origin, so facing it
            // is a half-turn from vanilla's yaw 0 (±180 are the same heading).
            assertEquals(180.0f, Math.abs(yaw), 1.0e-3f,
                    "carriages=" + carriages + " gave yaw " + yaw);
            assertTrue(headingDotToFocus(yaw, carriages) > 0.999,
                    "the spawn heading points at the template (carriages=" + carriages + ")");
        }
    }

    /**
     * How well the heading implied by {@code yaw} lines up with "straight at the template" from
     * the spawn — 1.0 is exact. Checks the rotation actually aims at the focus rather than just
     * matching an expected number.
     */
    private static double headingDotToFocus(float yaw, int carriages) {
        double rad = Math.toRadians(yaw);
        double hx = -Math.sin(rad);
        double hz = Math.cos(rad);
        Vec3 eye = playerEye(carriages);
        Vec3 focus = BuilderCinematic.focus(carriages, DIMS);
        double dx = focus.x - eye.x;
        double dz = focus.z - eye.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        return (hx * dx + hz * dz) / len;
    }

    @Test
    @DisplayName("Facing the carriages tilts the view up toward their middle, not down at the deck")
    void spawnPitchLooksUpAtTheCarriages() {
        float pitch = BuilderCinematic.spawnFacing(3, DIMS)[1];
        assertTrue(pitch < 0.0f, "negative pitch is upward in Minecraft's convention");
        assertTrue(pitch > -45.0f, "but not craning at the sky: " + pitch);
    }

    @Test
    @DisplayName("faceYawPitch agrees with the spawn helper for the four cardinal directions")
    void faceYawPitchCardinals() {
        Vec3 origin = new Vec3(0.0, 0.0, 0.0);
        assertEquals(0.0f, BuilderCinematic.faceYawPitch(origin, new Vec3(0, 0, 10))[0], 1.0e-3f);
        assertEquals(180.0f, Math.abs(BuilderCinematic.faceYawPitch(origin, new Vec3(0, 0, -10))[0]), 1.0e-3f);
        assertEquals(-90.0f, BuilderCinematic.faceYawPitch(origin, new Vec3(10, 0, 0))[0], 1.0e-3f);
        assertEquals(90.0f, BuilderCinematic.faceYawPitch(origin, new Vec3(-10, 0, 0))[0], 1.0e-3f);
    }

    @Test
    @DisplayName("The spawn stands back far enough that the whole train fits on screen")
    void spawnFramesTheWholeTemplate() {
        // Comfortably inside a default client's horizontal half-FOV (~51° at FOV 70 on 16:9),
        // with room for a narrower FOV setting or a 4:3 window.
        assertTrue(halfExtentDegrees(3) < 45.0,
                "three carriages subtend " + halfExtentDegrees(3) + "° from the spawn");
        assertTrue(halfExtentDegrees(1) < 45.0,
                "one carriage subtends " + halfExtentDegrees(1) + "°");
    }

    @Test
    @DisplayName("A longer train pushes the spawn further back; a short one keeps a minimum standoff")
    void standoffScalesWithTrainLength() {
        double three = BuilderWorldLayout.spawnPos(DIMS, 3).getZ();
        double one = BuilderWorldLayout.spawnPos(DIMS, 1).getZ();
        double none = BuilderWorldLayout.spawnPos(DIMS, 0).getZ();

        assertTrue(three > one, "3 carriages stand further back than 1: " + three + " vs " + one);
        assertTrue(one >= none, "1 carriage is never closer than the carriage-less floor");
        assertTrue(none > DIMS.width(), "still clear of the corridor");
        assertTrue(BuilderWorldLayout.inPlatform(0, (int) three), "and still on the platform");
    }

    /**
     * Widest horizontal angle, in degrees, between "looking at the template" and any corner of
     * its footprint — i.e. how much of the screen the train demands.
     */
    private static double halfExtentDegrees(int carriages) {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(carriages, DIMS);
        BoundingBox first = boxes.get(0);
        BoundingBox last = boxes.get(boxes.size() - 1);
        Vec3 eye = playerEye(carriages);
        Vec3 focus = BuilderCinematic.focus(carriages, DIMS);

        double worst = 0.0;
        for (double x : new double[] { first.minX(), last.maxX() + 1 }) {
            for (double z : new double[] { first.minZ(), last.maxZ() + 1 }) {
                worst = Math.max(worst, angleBetween(eye, focus, new Vec3(x, focus.y, z)));
            }
        }
        return worst;
    }

    /** Horizontal angle at {@code eye} between the directions to {@code a} and {@code b}. */
    private static double angleBetween(Vec3 eye, Vec3 a, Vec3 b) {
        double ax = a.x - eye.x;
        double az = a.z - eye.z;
        double bx = b.x - eye.x;
        double bz = b.z - eye.z;
        double cos = (ax * bx + az * bz)
                / (Math.sqrt(ax * ax + az * az) * Math.sqrt(bx * bx + bz * bz));
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
    }

    @Test
    @DisplayName("The camera starts above the template, off to one side, and behind the player")
    void cameraStartIsHighAndOffAxis() {
        Vec3 focus = BuilderCinematic.focus(3, DIMS);
        Vec3 eye = playerEye();
        Vec3 start = BuilderCinematic.cameraStart(focus, eye, 3, DIMS);

        assertTrue(start.y > focus.y + 5.0, "well above the carriage roofs: " + start.y);
        assertTrue(start.z > eye.z, "backed off past the player, on the platform side");
        assertTrue(Math.abs(start.x - focus.x) > DIMS.length(),
                "swung out along the train's long axis so the descent isn't a straight dolly");
    }

    @Test
    @DisplayName("The camera opens on the template and lands exactly on the player's eye")
    void cameraStartLooksAtTheTemplate() {
        Vec3 focus = BuilderCinematic.focus(3, DIMS);
        Vec3 eye = playerEye();
        Vec3 start = BuilderCinematic.cameraStart(focus, eye, 3, DIMS);

        float startPitch = BuilderCinematic.faceYawPitch(start, focus)[1];
        assertTrue(startPitch > 0.0f, "the opening frame looks down onto the train: " + startPitch);

        // The end of the move is the eye itself, so the release blend into player control has
        // nothing left to travel.
        float[] endFacing = BuilderCinematic.faceYawPitch(eye, focus);
        float[] spawnFacing = BuilderCinematic.spawnFacing(3, DIMS);
        assertEquals(spawnFacing[0], endFacing[0], 1.0e-3f);
        assertEquals(spawnFacing[1], endFacing[1], 1.0e-3f);
    }

    @Test
    @DisplayName("A camera start directly above the template still backs off rather than dropping on the player")
    void degenerateOverheadEyeStillBacksOff() {
        Vec3 focus = BuilderCinematic.focus(3, DIMS);
        Vec3 overhead = new Vec3(focus.x, focus.y + 30.0, focus.z);
        Vec3 start = BuilderCinematic.cameraStart(focus, overhead, 3, DIMS);

        assertTrue(start.z > focus.z, "falls back to the +Z platform side");
        assertTrue(Double.isFinite(start.x) && Double.isFinite(start.y) && Double.isFinite(start.z),
                "no NaN from normalising a zero-length axis");
    }

    /** Where a player stood at the builder world's spawn actually has their eyes. */
    private static Vec3 playerEye() {
        return playerEye(3);
    }

    private static Vec3 playerEye(int carriages) {
        BlockPos spawn = BuilderWorldLayout.spawnPos(DIMS, carriages);
        return BuilderCinematic.eyeOf(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
    }
}
