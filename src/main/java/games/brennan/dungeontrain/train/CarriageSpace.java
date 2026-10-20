package games.brennan.dungeontrain.train;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.joml.primitives.AABBdc;

/**
 * "Is this box inside a carriage?" for code that only reads the main level.
 *
 * <p>A carriage is a Sable sub-level, so its blocks are not in the main level: to anything that
 * scans {@code level.getBlockState} the volume a carriage occupies reads as plain air. A spawner
 * looking for "solid floor, two air above" therefore finds the track bed under a carriage and
 * places the mob there — standing inside the carriage floor with its head poking up through the
 * deck. Spawners ask this first and refuse any position it covers.</p>
 */
public final class CarriageSpace {

    private CarriageSpace() {}

    /** True if {@code box} intersects the world box of any loaded carriage in {@code level}. */
    public static boolean overlapsAnyCarriage(ServerLevel level, AABB box) {
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            AABBdc a = c.ship().worldAABB();
            if (overlaps(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    a.minX(), a.minY(), a.minZ(), a.maxX(), a.maxY(), a.maxZ())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strict overlap of two boxes: touching faces do not count, so a mob standing right against a
     * carriage's side is not refused. A degenerate (zero-size) carriage box — Sable reports one for a
     * sub-level before its first physics tick — never overlaps anything.
     */
    static boolean overlaps(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            double cMinX, double cMinY, double cMinZ, double cMaxX, double cMaxY, double cMaxZ) {
        if (cMaxX <= cMinX || cMaxY <= cMinY || cMaxZ <= cMinZ) return false;
        return maxX > cMinX && minX < cMaxX
            && maxY > cMinY && minY < cMaxY
            && maxZ > cMinZ && minZ < cMaxZ;
    }
}
