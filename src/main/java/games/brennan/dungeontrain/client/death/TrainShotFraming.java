package games.brennan.dungeontrain.client.death;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Where the train is, in world space, for the death cinematic's shots to aim at.
 *
 * <p>Carriages are Sable sub-levels, so there is no entity to look up: the set is walked through
 * {@link SubLevelContainer#getContainer(ClientLevel)} → {@link ClientSubLevelContainer#getAllSubLevels()},
 * the same discovery idiom {@code NearestCarriage} and {@code TrainEngineSound} use. Only each
 * sub-level's world AABB is read — deliberately <em>not</em>
 * {@code CarriageOcclusion.gatherNearby}, which additionally builds a chunk map per carriage for
 * its ray tests: this runs every render frame of the cinematic, and the shots only need extents.</p>
 *
 * <p>A carriage's AABB is world-space at both tick and render time (it describes the body, not
 * something riding it), so unlike an entity's position it is safe to sample from either.</p>
 */
public final class TrainShotFraming {

    /** Carriages further than this from the anchor aren't part of the shot's train. */
    private static final double GATHER_RADIUS = 96.0;

    private TrainShotFraming() {}

    /**
     * The train as the camera needs it: {@code center} of the carriages in frame, the centre of
     * the furthest-{@code forward} and furthest-{@code back} carriage along the travel axis, and
     * the highest roof among them.
     */
    public record TrainView(Vec3 center, Vec3 front, Vec3 back, double topY, int carriages) {}

    /**
     * Is there a train in this world at all? Cheaper and — at packet time — safer than a range
     * check against the player: an entity aboard a Sable ship reports far sub-level coords outside
     * render time, so a distance test would reject exactly the death this feature is for. The
     * shot's first render frame does the real range check, with the body's true world position.
     */
    public static boolean hasCarriages(ClientLevel level) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            if (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0) continue;
            return true;
        }
        return false;
    }

    /**
     * Resolve the train around {@code anchor}, ordering front/back along {@code axis} (a unit
     * horizontal vector pointing the way the train travels). Returns {@code null} when no carriage
     * with a settled AABB is in range — the death happened off the line, and the caller should
     * fall straight through to the recap.
     */
    public static TrainView resolve(ClientLevel level, Vec3 anchor, Vec3 axis) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;

        double rangeSq = GATHER_RADIUS * GATHER_RADIUS;
        int count = 0;
        double sumX = 0.0, sumY = 0.0, sumZ = 0.0;
        double topY = Double.NEGATIVE_INFINITY;
        double frontProj = Double.NEGATIVE_INFINITY, backProj = Double.POSITIVE_INFINITY;
        Vec3 front = null, back = null;

        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            // Fresh sub-levels report a zero AABB before their first physics tick — skip
            // (the same defensive check NearestCarriage and CarriageOcclusion make).
            if (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0) continue;

            Vec3 c = new Vec3(
                    (box.minX() + box.maxX()) * 0.5,
                    (box.minY() + box.maxY()) * 0.5,
                    (box.minZ() + box.maxZ()) * 0.5);
            if (c.distanceToSqr(anchor) > rangeSq) continue;

            count++;
            sumX += c.x;
            sumY += c.y;
            sumZ += c.z;
            topY = Math.max(topY, box.maxY());

            double proj = c.x * axis.x + c.z * axis.z;
            if (proj > frontProj) { frontProj = proj; front = c; }
            if (proj < backProj) { backProj = proj; back = c; }
        }

        if (count == 0) return null;
        Vec3 center = new Vec3(sumX / count, sumY / count, sumZ / count);
        return new TrainView(center, front != null ? front : center, back != null ? back : center, topY, count);
    }
}
