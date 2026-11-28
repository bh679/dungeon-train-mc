package games.brennan.dungeontrain.ship.sable;

import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/**
 * Decides whether an arrow's next block raycast would be absurdly long. This class has no Sable
 * calls in it, so it can be unit-tested without Sable.
 *
 * <p><b>Why:</b> Sable keeps a stuck arrow in carriage plot coordinates (x/z around 20 million).
 * Its {@code clip} overwrite projects each end of the ray into world space separately, and its
 * {@code onHitBlock} redirect assumes the arrow is in world space. Either one can leave the
 * flying-branch {@code clip} in {@code AbstractArrow.tick} walking millions of blocks. That froze
 * a player's render thread for 1 to 6 seconds (player logs, 2026-11-26).</p>
 *
 * <p>A real arrow moves under 5 blocks per tick. Any ray longer than {@link #MAX_STEP_BLOCKS},
 * raw or after projection, is broken. The caller zeroes that tick's movement.</p>
 */
public final class ArrowRayGuard {

    /** Longest single-tick arrow step treated as real, in blocks. Far above any real arrow speed. */
    public static final double MAX_STEP_BLOCKS = 100.0;

    private static final double MAX_STEP_SQR = MAX_STEP_BLOCKS * MAX_STEP_BLOCKS;

    /** Result of checking a ray. */
    public enum Verdict {
        /** Normal arrow step; leave it alone. */
        OK,
        /** The raw delta movement alone is longer than {@link #MAX_STEP_BLOCKS}. */
        ABSURD_DELTA,
        /** The raw ray is short, but its ends project into world space far apart (plot vs world). */
        CROSS_SPACE
    }

    private ArrowRayGuard() {}

    /**
     * @param from    arrow position at the start of the tick (raw: plot or world space)
     * @param delta   arrow delta movement for this tick
     * @param toWorld maps a raw position to world space, the same way Sable's {@code clip} does
     *                (the identity when the position is in no sub-level)
     */
    public static Verdict verdict(Vec3 from, Vec3 delta, UnaryOperator<Vec3> toWorld) {
        if (!isFinite(delta) || delta.lengthSqr() > MAX_STEP_SQR) {
            return Verdict.ABSURD_DELTA;
        }
        Vec3 worldFrom = toWorld.apply(from);
        Vec3 worldTo = toWorld.apply(from.add(delta));
        if (!isFinite(worldFrom) || !isFinite(worldTo) || worldFrom.distanceToSqr(worldTo) > MAX_STEP_SQR) {
            return Verdict.CROSS_SPACE;
        }
        return Verdict.OK;
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
