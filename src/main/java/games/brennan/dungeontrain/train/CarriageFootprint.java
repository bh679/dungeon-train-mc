package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.world.phys.AABB;
import org.joml.primitives.AABBdc;

import java.util.List;

/**
 * Computes the world-space AABB of a train — the union of every carriage
 * sub-level's individual AABB.
 *
 * <p>With the per-carriage architecture each carriage is its own Sable
 * sub-level whose {@link ManagedShip#worldAABB()} is bounded by that one
 * carriage's footprint. Unioning across the train carriages gives a
 * tight per-train AABB that grows linearly with carriage count and never
 * carries phantom-claimed-chunk territory.</p>
 */
public final class CarriageFootprint {

    private CarriageFootprint() {}

    /**
     * Union of world-space AABBs across every carriage in {@code train}.
     * Returns an empty AABB at world origin when the train is empty.
     *
     * <p>Sable may briefly return zero-bounds AABB on a freshly-assembled
     * sub-level (before its first physics tick has run) — that's a mild
     * underestimate; not a correctness issue for the kill-ahead caller
     * because it only fires for trains that have been moving for at least
     * one tick.</p>
     */
    public static AABB activeWorldAABB(List<Trains.Carriage> train) {
        if (train.isEmpty()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Trains.Carriage c : train) {
            AABBdc aabb = c.ship().worldAABB();
            if (aabb.minX() < minX) minX = aabb.minX();
            if (aabb.minY() < minY) minY = aabb.minY();
            if (aabb.minZ() < minZ) minZ = aabb.minZ();
            if (aabb.maxX() > maxX) maxX = aabb.maxX();
            if (aabb.maxY() > maxY) maxY = aabb.maxY();
            if (aabb.maxZ() > maxZ) maxZ = aabb.maxZ();
        }

        if (minX > maxX || minY > maxY || minZ > maxZ) {
            // Defensive: if every sub-level returned zero-bounds AABB
            // (pre-first-tick), fall back to a degenerate AABB at origin
            // rather than NEGATIVE_INFINITY garbage values.
            return new AABB(0, 0, 0, 0, 0, 0);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
