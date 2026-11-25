package games.brennan.dungeontrain.combat;

import net.minecraft.core.BlockPos;

/**
 * When {@link MobDigGoal} looks again after deciding not to dig, and when the navigator's own path already answers
 * "can it walk there?" without a fresh search.
 *
 * <p>A mob holding range or circling never gets closer, so it counts as stuck every window; each look used to be a
 * full pathfind. After a "no dig" answer the wait doubles (up to {@link #MAX_DOUBLINGS}), and drops back to one
 * window as soon as something that could change the answer happens: it loses sight of its target, the target moves
 * {@link #MOVED_BLOCKS} or more. A mob wedged in place keeps the usual window (the carpet fallback counts those).</p>
 */
final class DigRecheck {

    /** No closer to the target for this long counts as stuck: one window, and the wait after an answer with no back-off. */
    static final int BASE_TICKS = 40;
    /** The wait grows 40, 80, 160 and no further. */
    static final int MAX_DOUBLINGS = 2;
    /** A target this many blocks (largest axis) from where it stood at the answer may now be walled in or reachable. */
    static final int MOVED_BLOCKS = 2;
    /** A held path aimed within this many blocks (largest axis) of the target's cell still counts as aimed at it. */
    static final int AIM_SLACK = 1;

    private DigRecheck() {}

    /** Ticks without progress before the next look, after {@code streak} "no dig" answers in a row. */
    static int waitTicks(int streak) {
        return BASE_TICKS << Math.max(0, Math.min(streak, MAX_DOUBLINGS));
    }

    /** The streak after one more "no dig" answer: it stops growing once the wait is capped. */
    static int grow(int streak) {
        return Math.min(Math.max(0, streak) + 1, MAX_DOUBLINGS);
    }

    /**
     * Whether something changed since the last "no dig" answer that could change it, so the mob looks again on
     * the usual clock: it saw its target then and doesn't now (a wall goes up between them), or the target has moved
     * {@link #MOVED_BLOCKS} or more. A wedged mob never builds a streak at all ({@code MobDigGoal#noteNoDig}).
     */
    static boolean resetsBackoff(boolean seenAtAnswer, boolean seenNow, int targetMovedBlocks) {
        return (seenAtAnswer && !seenNow) || targetMovedBlocks >= MOVED_BLOCKS;
    }

    /** Largest-axis distance between two block cells. */
    static int blocksApart(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.max(Math.abs(ax - bx), Math.max(Math.abs(ay - by), Math.abs(az - bz)));
    }

    static int blocksApart(BlockPos a, BlockPos b) {
        return blocksApart(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    /**
     * Whether the navigator's held path already says the target is walkable: a live path ({@code !done}) aimed
     * within {@link #AIM_SLACK} of the target's cell that reaches it, or ends in its column ({@link MobDigGoal#endsAtTarget}).
     * Only ever answers yes; anything else falls back to a fresh search.
     */
    static boolean heldPathWalkable(boolean done, boolean canReach,
                                    int endX, int endY, int endZ,
                                    int aimX, int aimY, int aimZ,
                                    int targetX, int targetY, int targetZ) {
        if (done) return false;
        if (blocksApart(aimX, aimY, aimZ, targetX, targetY, targetZ) > AIM_SLACK) return false;
        return canReach || MobDigGoal.endsAtTarget(endX, endY, endZ, targetX, targetY, targetZ);
    }
}
