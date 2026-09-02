package games.brennan.dungeontrain.client.vivecraft;

import net.minecraft.world.phys.AABB;

/**
 * Pure decision logic for {@code SwingTrackerSubLevelAabbMixin}, kept OUT of the mixin package so it is a
 * normal class (Mixin's transformer claims everything under the mixin package) and unit-testable off the
 * render thread — no running game / no Vivecraft needed: a plain {@code AABB -> AABB} transform.
 *
 * <p>When a Vivecraft VR player stands on a Sable sub-level (train carriage), the swing tracker's
 * {@code getEntities} AABB spans the player's real position to the sub-level pivot (~20 million blocks).
 * Sable's {@code SubLevelInclusiveLevelEntityGetter} aborts any query with {@code AABB.getSize() > 100000}
 * (and log-spams a stack trace). An absurdly large box is the unambiguous signature of that coordinate
 * mismatch — a real swing box is only a few blocks — so we treat any query above {@link #MAX_SANE_SWING_SIZE}
 * as broken and rebuild it as a small box centred on the player's own bounding box, inflated to VR melee
 * reach. That box is in the player's current frame (the same frame as the on-carriage mobs at tick time),
 * so Sable resolves it against the sub-level and returns the nearby mobs instead of aborting.</p>
 */
public final class SwingAabbClamp {

    /**
     * Average side length ({@link AABB#getSize()}) above which a swing query is treated as the sub-level
     * coordinate mismatch. Far above any real melee box (a few blocks) and far below both Sable's 100000
     * abort threshold and the ~6.8M produced by the mismatch.
     */
    public static final double MAX_SANE_SWING_SIZE = 256.0;

    /** VR melee reach (blocks) to inflate the player's bounding box by when rebuilding the query box. */
    public static final double MELEE_REACH = 4.0;

    private SwingAabbClamp() {}

    /** True when {@code area} is too large to be a real VR swing box (i.e. the sub-level coord mismatch). */
    public static boolean isOversized(AABB area) {
        return area.getSize() > MAX_SANE_SWING_SIZE;
    }

    /**
     * The AABB the swing broad-phase query should actually use.
     *
     * @param original the AABB Vivecraft built (possibly the ~20M-block mismatch box)
     * @param playerBox the player entity's current bounding box (the frame the query must resolve in)
     * @return {@code playerBox} inflated to melee reach when {@code original} is oversized; otherwise
     *         {@code original} unchanged (returned by identity, so normal play is a zero-behaviour-change
     *         pass-through)
     */
    public static AABB forSwingQuery(AABB original, AABB playerBox) {
        return isOversized(original) ? playerBox.inflate(MELEE_REACH) : original;
    }
}
