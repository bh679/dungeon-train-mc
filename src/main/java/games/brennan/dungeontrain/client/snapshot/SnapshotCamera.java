package games.brennan.dungeontrain.client.snapshot;

import games.brennan.dungeontrain.client.CinematicCameraController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Picks a clip-safe, player-in-view third-person camera pose for a snapshot.
 *
 * <p>For the chosen {@link SnapshotTag} it evaluates several candidate angles
 * around the player (preferred direction first). Each candidate is ray-checked
 * from the player toward the camera against the world ({@link ClientLevel#clip}):
 * if a block is in the way the camera is pulled to open air <em>on the player's
 * side</em> of it, so the lens never sits inside a block and the player is never
 * occluded by world geometry. The candidate with the clearest line of sight
 * wins. If even the best angle can't see the player from at least
 * {@link #MIN_VISIBLE_DIST} blocks (boxed in), it returns {@code null} and the
 * director simply tries again shortly.</p>
 *
 * <p>Carriage walls are Sable sub-level blocks (not in the main level), so the
 * ray test covers world terrain — the dominant clipping case — and the angles
 * are kept near deck height to keep the player on an open deck in view.</p>
 */
public final class SnapshotCamera {

    /** Keep the camera this far off a hit block so it isn't flush against the face. */
    private static final double CLIP_MARGIN = 0.4;
    /** Reject a framing that can't see the player from at least this far (too cramped). */
    private static final double MIN_VISIBLE_DIST = 2.5;
    /** Prefer well-lit moments — skip when the player's spot is darker than this (0-15). */
    private static final int MIN_LIGHT = 8;

    private SnapshotCamera() {}

    /**
     * A clip-safe pose framing the player, or {@code null} if there is no clean
     * angle right now or the player's spot is too dark. <b>Must be called at
     * render time</b> ({@code player.position()} is the real world position
     * then — at tick time a player on a Sable ship reports far sub-level coords).
     */
    public static CinematicCameraController.Pose poseFor(ClientLevel level, SnapshotTag tag, LocalPlayer player) {
        // Prefer well-lit moments (checked here, in render space, so the light sample is at the real spot).
        BlockPos eye = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        if (level.getMaxLocalRawBrightness(eye) < MIN_LIGHT) return null;

        Vec3 p = player.position();
        Vec3 lookTarget = new Vec3(p.x, p.y + player.getEyeHeight() * 0.85, p.z);
        // Ray origin at the player's chest — a clear ray to the camera means the player is visible.
        Vec3 from = new Vec3(p.x, p.y + player.getBbHeight() * 0.6, p.z);

        double[] base = baseFor(tag);
        double dist = base[0], height = base[1];

        Vec3 best = null;
        double bestClear = -1.0;
        for (double[] dir : dirsFor(tag)) {
            Vec3 want = new Vec3(p.x + dir[0] * dist, p.y + height, p.z + dir[1] * dist);
            Vec3 adj = clipTowardOpenAir(level, player, from, want);
            double clear = adj.distanceTo(from);
            if (clear > bestClear) {
                bestClear = clear;
                best = adj;
            }
        }
        if (best == null || bestClear < MIN_VISIBLE_DIST) return null;

        float[] yp = lookAt(best, lookTarget);
        return new CinematicCameraController.Pose(best, yp[0], yp[1]);
    }

    /** {horizontal distance, height above player} per tag. */
    private static double[] baseFor(SnapshotTag tag) {
        return switch (tag) {
            case COMBAT -> new double[] { 4.5, 2.0 };
            case GEAR   -> new double[] { 4.0, 1.8 };
            case LORE   -> new double[] { 3.5, 2.2 };
            case SOCIAL -> new double[] { 4.0, 1.6 };
            case SCENIC -> new double[] { 7.0, 3.5 };
        };
    }

    /**
     * Candidate horizontal directions, preferred angle first (train travels +X,
     * so {@code -X} is "behind"). The scorer keeps the first fully-clear one, so
     * order encodes the tag's ideal look while still falling back to any clear angle.
     */
    private static double[][] dirsFor(SnapshotTag tag) {
        return switch (tag) {
            case COMBAT -> new double[][] { {0, 1}, {0, -1}, {-0.7, 0.7}, {-0.7, -0.7}, {-1, 0}, {0.7, 0.7}, {0.7, -0.7} };
            case GEAR   -> new double[][] { {1, 0}, {0.7, 0.7}, {0.7, -0.7}, {0, 1}, {0, -1}, {-0.7, 0.7}, {-0.7, -0.7} };
            case LORE   -> new double[][] { {0, 1}, {0, -1}, {-0.7, 0.7}, {-0.7, -0.7}, {-1, 0} };
            case SOCIAL -> new double[][] { {0, 1}, {0, -1}, {0.7, 0.7}, {0.7, -0.7}, {-0.7, 0.7}, {-0.7, -0.7}, {1, 0} };
            case SCENIC -> new double[][] { {-1, 0}, {-0.7, 0.7}, {-0.7, -0.7}, {0, 1}, {0, -1}, {0.7, 0.7}, {0.7, -0.7} };
        };
    }

    /**
     * Clip the segment {@code from → want} against world blocks. Clear → return
     * {@code want}. Blocked → return a point just short of the hit so the camera
     * sits in open air on the player's side (nothing between it and the player).
     */
    private static Vec3 clipTowardOpenAir(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 want) {
        double len = want.subtract(from).length();
        if (len < 1.0e-6) return from;
        BlockHitResult hit = level.clip(new ClipContext(
                from, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return want;
        double hitDist = hit.getLocation().distanceTo(from);
        // A real hit lies on the from→want segment (hitDist ≤ len). Sable wraps Level.clip to
        // redirect the ray into a ship's sub-level, which returns a hit at far sub-level coords —
        // ignore those and keep the nice-coord candidate, or the camera teleports into the void.
        if (hitDist > len + 0.5) return want;
        Vec3 dir = want.subtract(from).scale(1.0 / len);
        double d = Math.max(0.0, hitDist - CLIP_MARGIN);
        return from.add(dir.scale(d));
    }

    /** Yaw/pitch (MC convention) to look from {@code pos} toward {@code target}. */
    private static float[] lookAt(Vec3 pos, Vec3 target) {
        double dx = target.x - pos.x;
        double dy = target.y - pos.y;
        double dz = target.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[] { yaw, pitch };
    }
}
