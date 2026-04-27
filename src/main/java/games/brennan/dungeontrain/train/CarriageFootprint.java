package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.Set;

/**
 * Computes the world-space AABB of a train's currently-active carriages
 * — {@link #activeWorldAABB(ManagedShip, TrainTransformProvider)}
 * replaces the underlying physics mod's whole-ship AABB in per-tick hot
 * paths.
 *
 * <h2>Why</h2>
 * VS 2.4.11's {@code ship.getWorldAABB()} covers every chunk the ship
 * has ever claimed, which grows unbounded as the rolling-window train
 * travels. Using that AABB in {@code TrainTickEvents.clearBlocksAhead}
 * and {@code killEntitiesIn} made per-tick work scale with distance
 * travelled and eventually stalled the server ({@code Can't keep up!
 * Running 36425ms / 728 ticks behind} around carriage idx ±228).
 *
 * <h2>How</h2>
 * Union the eight corners of each active carriage's shipyard-space box
 * (origin + (length, height, width)) transformed to world space via
 * {@link ManagedShip#shipToWorld}. Size is bounded by the rolling-window
 * count (~10 carriages) regardless of how far the train has rolled.
 */
public final class CarriageFootprint {

    private CarriageFootprint() {}

    /**
     * World-space AABB enclosing every active carriage, or
     * {@link AABB#ofSize(net.minecraft.world.phys.Vec3, double, double, double)}-like
     * empty AABB at origin when the train has no active carriages yet.
     */
    public static AABB activeWorldAABB(ManagedShip ship, TrainTransformProvider provider) {
        Set<Integer> active = provider.getActiveIndices();
        if (active.isEmpty()) {
            // No active carriages → no work to do. Return a degenerate AABB
            // at world origin; callers iterating [min..max] with max<min
            // will no-op. (AABB doesn't have a dedicated EMPTY constant.)
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        CarriageDims dims = provider.dims();
        int originX = provider.getShipyardOrigin().getX();
        int originY = provider.getShipyardOrigin().getY();
        int originZ = provider.getShipyardOrigin().getZ();
        int length = dims.length();
        int height = dims.height();
        int width = dims.width();

        int minIdx = Integer.MAX_VALUE;
        int maxIdx = Integer.MIN_VALUE;
        for (int i : active) {
            if (i < minIdx) minIdx = i;
            if (i > maxIdx) maxIdx = i;
        }

        // Shipyard-space carriage block range — inclusive/exclusive per the
        // CarriageTemplate.eraseAt iteration pattern (dx ∈ [0, length)).
        double syMinX = originX + minIdx * length;
        double syMaxX = originX + (maxIdx + 1) * length;
        double syMinY = originY;
        double syMaxY = originY + height;
        double syMinZ = originZ;
        double syMaxZ = originZ + width;

        // Transform all 8 corners — the ship may be rotated (even though
        // Dungeon Train locks rotation via setStatic(true), keeping the
        // math general costs 8 matrix-vector products per tick vs 2 and
        // protects against future changes).
        double[] xs = { syMinX, syMaxX };
        double[] ys = { syMinY, syMaxY };
        double[] zs = { syMinZ, syMaxZ };
        double minWX = Double.POSITIVE_INFINITY, minWY = Double.POSITIVE_INFINITY, minWZ = Double.POSITIVE_INFINITY;
        double maxWX = Double.NEGATIVE_INFINITY, maxWY = Double.NEGATIVE_INFINITY, maxWZ = Double.NEGATIVE_INFINITY;
        Vector3d tmp = new Vector3d();
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    tmp.set(x, y, z);
                    ship.shipToWorld(tmp);
                    if (tmp.x < minWX) minWX = tmp.x;
                    if (tmp.y < minWY) minWY = tmp.y;
                    if (tmp.z < minWZ) minWZ = tmp.z;
                    if (tmp.x > maxWX) maxWX = tmp.x;
                    if (tmp.y > maxWY) maxWY = tmp.y;
                    if (tmp.z > maxWZ) maxWZ = tmp.z;
                }
            }
        }
        return new AABB(minWX, minWY, minWZ, maxWX, maxWY, maxWZ);
    }
}
