package games.brennan.dungeontrain.client.snapshot;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The two bits of lens maths every third-person Dungeon Train camera needs: keep the lens out of
 * the world's blocks, and aim it at something.
 *
 * <p>Extracted from {@link SnapshotCamera} (which still uses it for the ride-photo angles) so the
 * death cinematic's shots — {@code client.death.DeathCinematic} — can place a camera against the
 * same rules rather than re-deriving them. Behaviour is unchanged from the original private
 * helpers; only the home moved.</p>
 */
public final class CameraClip {

    /** Keep the camera this far off a hit block so it isn't flush against the face. */
    public static final double CLIP_MARGIN = 0.4;

    private CameraClip() {}

    /**
     * Clip the segment {@code from → want} against world blocks. Clear → return {@code want}.
     * Blocked → return a point just short of the hit so the camera sits in open air on the
     * subject's side (nothing between it and the subject).
     *
     * <p>{@code from} is the thing being looked at and {@code want} the camera's ideal spot, so
     * the ray is cast outward from the subject — a blocked ray then yields the furthest lens
     * position that can still see it.</p>
     */
    public static Vec3 towardOpenAir(ClientLevel level, Entity subject, Vec3 from, Vec3 want) {
        double len = want.subtract(from).length();
        if (len < 1.0e-6) return from;
        BlockHitResult hit = level.clip(new ClipContext(
                from, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject));
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
    public static float[] lookAt(Vec3 pos, Vec3 target) {
        double dx = target.x - pos.x;
        double dy = target.y - pos.y;
        double dz = target.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[] { yaw, pitch };
    }
}
