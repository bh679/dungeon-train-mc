package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The carriage frame a puppet is mirrored through follows the ship's pose, not its bounding box.
 *
 * <p>Sable's box lags its logical pose by a fraction of a block that changes every tick, and the
 * corridor origin the tick hands in is read off the box. A puppet mirrored through that origin and
 * then taken into plot space through the pose carries the lag as a wobble — the shimmer a moving
 * train showed. {@link PortalPuppets#poseAligned} snaps the origin to the plot grid the corridor was
 * stamped on, so the lag drops out; pinned here with a ship whose box and pose disagree.</p>
 */
final class PortalPuppetsPoseAlignTest {

    private static final PortalCarriageLayout LAYOUT = new PortalCarriageLayout(9, 7, 7);
    private static final PortalFrames.Origin TWIN = new PortalFrames.Origin(100, 174, 0);

    /**
     * A translating ship: plot {@code p} sits at world {@code pose + p}. Its box is at {@code pose
     * + lag}, which is what the tick derives the corridor origin from.
     */
    private static final class Translating implements ManagedShip {
        private final Vector3d pose;

        Translating(double x, double y, double z) {
            this.pose = new Vector3d(x, y, z);
        }

        @Override public Vector3d worldToShip(Vector3d w) { return w.sub(pose); }
        @Override public Vector3d shipToWorld(Vector3d p) { return p.add(pose); }

        @Override public long id() { return 1; }
        @Override public UUID subLevelId() { return new UUID(1, 1); }
        @Override public Vector3dc currentWorldPosition() { return pose; }
        @Override public Quaterniondc currentRotation() { return new Quaterniond(); }
        @Override public Vector3dc currentPositionInModel() { return new Vector3d(); }
        @Override public AABBdc worldAABB() { return new AABBd(); }
        @Override public KinematicDriver getKinematicDriver() { return null; }
        @Override public void setKinematicDriver(KinematicDriver driver) {}
        @Override public void setStatic(boolean isStatic) {}
        @Override public void applyTickOutput(KinematicDriver.TickOutput output) {}
        @Override public InertiaSnapshot captureInertia() { return null; }
        @Override public void restoreInertia(InertiaSnapshot snapshot) {}
    }

    /** The frames the tick would build, with the corridor origin read off a box lagging by {@code lag}. */
    private static PortalFrames boxFrames(Translating ship, double lag) {
        // Corridor origin is plot (12, 0, 0): the box would put it at pose + 12 - lag.
        return new PortalFrames(LAYOUT,
            new PortalFrames.Origin(ship.pose.x + 12 - lag, ship.pose.y, ship.pose.z),
            TWIN, PortalCarriageRole.ENTRY);
    }

    @Test
    @DisplayName("A twin-side entity mirrors to the same plot coordinate whatever the box's lag")
    void plotCoordinateIsLagFree() {
        double[] plotXs = new double[3];
        double[] lags = {0.0, 0.28, -0.19};

        for (int i = 0; i < lags.length; i++) {
            Translating ship = new Translating(1000.5, 78, 0);
            PortalFrames aligned = PortalPuppets.poseAligned(boxFrames(ship, lags[i]), ship, 18);

            // A zombie standing 3 blocks into the twin corridor.
            PortalFrames.Move dest = aligned.mirror(TWIN.x() + 3.0, TWIN.y() + 1, TWIN.z() + 3);
            assertEquals(PortalFrames.FRAME_CARRIAGE, dest.toFrame());
            plotXs[i] = ship.worldToShip(new Vector3d(dest.x(), dest.y(), dest.z())).x;
        }

        assertEquals(15.0, plotXs[0], 1e-9);
        assertEquals(plotXs[0], plotXs[1], 1e-9, "a lagging box moved the puppet in plot space");
        assertEquals(plotXs[0], plotXs[2], 1e-9, "a leading box moved the puppet in plot space");
    }

    @Test
    @DisplayName("A carriage-side entity mirrors to the same twin coordinate whatever the box's lag")
    void twinCoordinateIsLagFree() {
        double[] twinXs = new double[2];
        double[] lags = {0.0, 0.31};

        for (int i = 0; i < lags.length; i++) {
            Translating ship = new Translating(1000.5, 78, 0);
            PortalFrames aligned = PortalPuppets.poseAligned(boxFrames(ship, lags[i]), ship, 18);

            // A rider 3 blocks into the corridor: world position follows the pose, as the carry does.
            PortalFrames.Move dest = aligned.mirror(ship.pose.x + 12 + 3.0, ship.pose.y + 1, 3);
            assertEquals(PortalFrames.FRAME_TWIN, dest.toFrame());
            twinXs[i] = dest.x();
        }

        assertEquals(TWIN.x() + 3.0, twinXs[0], 1e-9);
        assertEquals(twinXs[0], twinXs[1], 1e-9, "a lagging box moved the twin puppet");
    }

    @Test
    @DisplayName("The twin origin and the rule are carried across untouched")
    void onlyTheCarriageOriginChanges() {
        Translating ship = new Translating(1000.5, 78, 0);
        PortalFrames aligned = PortalPuppets.poseAligned(boxFrames(ship, 0.2), ship, 18);

        assertEquals(TWIN, aligned.twin());
        assertEquals(PortalCarriageRole.ENTRY, aligned.role());
        assertEquals(LAYOUT, aligned.layout());
        assertEquals(1012.5, aligned.carriage().x(), 1e-9);
    }
}
