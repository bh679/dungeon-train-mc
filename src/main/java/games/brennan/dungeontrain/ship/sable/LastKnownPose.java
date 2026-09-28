package games.brennan.dungeontrain.ship.sable;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;

/**
 * The last pose and bounding box a {@link SableManagedShip} saw on its live sub-level.
 *
 * <p>A wrapper only holds its sub-level weakly (see {@link SableManagedShip}); once Sable has culled
 * the sub-level and the object is collected, registry handles that still name the carriage answer
 * {@link SableManagedShip#worldAABB()} and friends from here — the same frozen last-known pose a
 * removed-but-uncollected sub-level reported before, so nothing downstream changes.</p>
 *
 * <p>Plain doubles rather than JOML objects so a per-tick refresh allocates nothing, and so the
 * class is Minecraft- and Sable-free for unit tests. Written and read on the server thread only.</p>
 */
final class LastKnownPose {

    private double px, py, pz;
    private double qx, qy, qz, qw = 1.0;
    private double rx, ry, rz;
    private double minX, minY, minZ, maxX, maxY, maxZ;
    private boolean hasPose;
    private boolean hasAabb;

    void recordPose(Vector3dc position, Quaterniondc orientation, Vector3dc rotationPoint) {
        recordPose(position.x(), position.y(), position.z(),
            orientation.x(), orientation.y(), orientation.z(), orientation.w(),
            rotationPoint.x(), rotationPoint.y(), rotationPoint.z());
    }

    void recordPose(double px, double py, double pz,
                    double qx, double qy, double qz, double qw,
                    double rx, double ry, double rz) {
        this.px = px; this.py = py; this.pz = pz;
        this.qx = qx; this.qy = qy; this.qz = qz; this.qw = qw;
        this.rx = rx; this.ry = ry; this.rz = rz;
        this.hasPose = true;
    }

    void recordAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.hasAabb = true;
    }

    boolean hasPose() {
        return hasPose;
    }

    boolean hasAabb() {
        return hasAabb;
    }

    /** A fresh box; the degenerate {@code [0,0,0,0,0,0]} Sable reports pre-tick when nothing was ever recorded. */
    AABBd aabb() {
        return new AABBd(minX, minY, minZ, maxX, maxY, maxZ);
    }

    Vector3d position() {
        return new Vector3d(px, py, pz);
    }

    Quaterniond orientation() {
        return new Quaterniond(qx, qy, qz, qw);
    }

    Vector3d rotationPoint() {
        return new Vector3d(rx, ry, rz);
    }

    /**
     * Model → world, in place: {@code world = position + rotation * (model - rotationPoint)} — the
     * mapping Sable's {@code Pose3dc.transformPosition} applies, so a stale handle transforms exactly
     * as it did while live.
     */
    Vector3d shipToWorld(Vector3d model) {
        model.sub(rx, ry, rz);
        orientation().transform(model);
        return model.add(px, py, pz);
    }

    /** World → model, in place: the inverse of {@link #shipToWorld}. */
    Vector3d worldToShip(Vector3d world) {
        world.sub(px, py, pz);
        orientation().conjugate().transform(world);
        return world.add(rx, ry, rz);
    }
}
