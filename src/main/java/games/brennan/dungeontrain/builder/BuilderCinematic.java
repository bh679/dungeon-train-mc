package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Camera maths for the Train Builder's arrival shot.
 *
 * <p>The shot is the inverse of the join intro: instead of starting at the player's eye and
 * pulling away, it starts high and off to one side of the template and <b>descends into</b> the
 * player's eye, holding its aim on the template the whole way. It therefore ends exactly where
 * the player's head already is, facing what they came here to build — so handing control back
 * is invisible rather than a cut.</p>
 *
 * <p>"The template" is whatever this mode is authoring: the parked carriages
 * ({@link BuilderBounds#buildVolumes}) when there are any, and otherwise the origin stretch of
 * track, which is what the track and portal modes will be editing.</p>
 *
 * <p>Pure — no level and no client access — so the framing is unit-testable and both the server
 * (which computes the poses) and the tests can use it.</p>
 */
public final class BuilderCinematic {

    /** Standing eye height, matching {@code CinematicIntroService.EYE_HEIGHT}. */
    public static final double EYE_HEIGHT = 1.62;

    /** How far above the template the camera starts. */
    private static final double START_HEIGHT = 15.0;
    /**
     * How far <em>past</em> the player (on the template→player axis) the camera starts, so the
     * move is a genuine approach rather than a drop straight down onto their own head.
     */
    private static final double START_BACK = 16.0;
    /**
     * Lateral offset at the start, as a fraction of the template's length. Pushing the camera
     * off-axis makes the descent sweep diagonally across the train's flank instead of dollying
     * down the centre line, which is what makes the silhouette read.
     */
    private static final double START_SIDE_FRACTION = 0.75;
    /** Floor on that lateral offset, so a one-carriage (or carriage-less) shot still swings. */
    private static final double MIN_START_SIDE = 10.0;

    private BuilderCinematic() {}

    /**
     * The point the camera holds its aim on, given the volumes actually being authored.
     *
     * <p>Centre of their union. The overload below derives them from a carriage count, which is the
     * only thing this could read before a build volume stopped always being a carriage — a portal
     * room's box has to be passed in, because no carriage count describes it.</p>
     */
    public static Vec3 focus(List<BoundingBox> volumes, CarriageDims dims) {
        if (volumes.isEmpty()) {
            TrackGeometry g = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
            return new Vec3(0.5, g.railY() + 1.0, g.trackCenterZ() + 0.5);
        }
        BoundingBox first = volumes.get(0);
        BoundingBox last = volumes.get(volumes.size() - 1);
        return new Vec3(
                (first.minX() + last.maxX() + 1) / 2.0,
                (first.minY() + first.maxY() + 1) / 2.0,
                (first.minZ() + first.maxZ() + 1) / 2.0);
    }

    /**
     * The aim point for a mode that parks carriages.
     *
     * <p>With none — Tracks &amp; Tunnels, or Train Dimensions before a room is opened — it falls
     * back to the track at the origin, a block above the rails so the shot frames the line rather
     * than staring into the bed. In a void world there is no line to frame, but the origin is also
     * where the room will stand, so the camera is already pointing at the right place.</p>
     */
    public static Vec3 focus(int carriages, CarriageDims dims) {
        return focus(BuilderBounds.buildVolumes(carriages, dims), dims);
    }

    /** Where the camera comes to rest: the player's eye, so the release blend has nothing to do. */
    public static Vec3 eyeOf(double x, double y, double z) {
        return new Vec3(x, y + EYE_HEIGHT, z);
    }

    /**
     * Where the camera starts: high above the template, backed off along the template→eye axis,
     * and swung out to one side of it.
     *
     * <p>The side offset is taken along the template's long axis (X — the train always runs on
     * X in a builder world), scaled to how long the template actually is, so a three-carriage
     * run gets a wider swing than a single one.</p>
     */
    public static Vec3 cameraStart(Vec3 focus, Vec3 eye, int carriages, CarriageDims dims) {
        double backX = eye.x - focus.x;
        double backZ = eye.z - focus.z;
        double backLen = Math.sqrt(backX * backX + backZ * backZ);
        if (backLen < 1.0e-6) {
            // Degenerate (eye directly above/below the template) — back off on +Z, the side the
            // platform spawn is on, so the camera never starts inside the carriages.
            backX = 0.0;
            backZ = 1.0;
        } else {
            backX /= backLen;
            backZ /= backLen;
        }
        double side = Math.max(MIN_START_SIDE,
                START_SIDE_FRACTION * BuilderWorldLayout.totalTrainLength(Math.max(carriages, 1), dims));
        return new Vec3(
                eye.x + backX * START_BACK + side,
                focus.y + START_HEIGHT,
                eye.z + backZ * START_BACK);
    }

    /**
     * Yaw/pitch (Minecraft convention) to look from {@code from} toward {@code target} — the same
     * arithmetic {@code PlayerJoinEvents.tryPlace} uses to aim the spawn pose, kept here so the
     * builder's spawn rotation and its camera agree by construction.
     */
    public static float[] faceYawPitch(Vec3 from, Vec3 target) {
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[] { yaw, pitch };
    }

    /**
     * The rotation a player standing at {@link BuilderWorldLayout#spawnPos} should have: looking
     * back across the platform at the template.
     *
     * <p>Without this the builder spawns facing {@code yaw = 0} — straight down +Z, directly
     * <em>away</em> from the train parked behind them.</p>
     */
    public static float[] spawnFacing(int carriages, CarriageDims dims) {
        BlockPos spawn = BuilderWorldLayout.spawnPos(dims, carriages);
        Vec3 eye = eyeOf(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        return faceYawPitch(eye, focus(carriages, dims));
    }
}
