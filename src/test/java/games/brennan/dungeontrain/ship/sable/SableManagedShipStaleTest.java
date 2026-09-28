package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.ship.KinematicDriver;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A wrapper whose sub-level Sable has dropped and the collector has taken — the state every
 * registry handle for a carriage the train left behind ends up in. It must keep answering the
 * questions a stale handle is asked (id, last pose, residency) and refuse to touch a body that is
 * no longer there.
 */
final class SableManagedShipStaleTest {

    private static final double EPS = 1e-9;

    @BeforeEach
    @AfterEach
    void reset() {
        SableManagedShip.clearDrivers();
    }

    private static SableManagedShip collected(UUID uuid) {
        return new SableManagedShip(new WeakReference<>(null), uuid);
    }

    @Test
    @DisplayName("ids survive; residency does not")
    void idsSurvive() {
        UUID uuid = UUID.randomUUID();
        SableManagedShip ship = collected(uuid);
        assertEquals(uuid, ship.subLevelId());
        assertEquals(uuid.getMostSignificantBits(), ship.id());
        assertNull(ship.subLevel());
        assertFalse(ship.isResident());
    }

    @Test
    @DisplayName("the last-known box and pose are what a stale handle reports")
    void lastKnownFallback() {
        SableManagedShip ship = collected(UUID.randomUUID());
        ship.seedLastKnown(new Vector3d(1000, 80, 3.5), new Quaterniond(), new Vector3d(20, 5, 5),
            new AABBd(990, 78, -1, 1010, 88, 8));
        AABBdc box = ship.worldAABB();
        assertEquals(990, box.minX(), EPS);
        assertEquals(1010, box.maxX(), EPS);
        assertEquals(1000, ship.currentWorldPosition().x(), EPS);
        assertEquals(20, ship.currentPositionInModel().x(), EPS);
        Vector3d world = ship.shipToWorld(new Vector3d(25, 6, 5));
        assertEquals(1005, world.x(), EPS);
    }

    @Test
    @DisplayName("mutations are no-ops and inertia is unreadable")
    void mutationsAreNoOps() {
        SableManagedShip ship = collected(UUID.randomUUID());
        ship.applyTickOutput(new KinematicDriver.TickOutput(
            new Vector3d(1, 2, 3), new Quaterniond(), new Vector3d(), new Vector3d(), new Vector3d()));
        ship.setStatic(true);
        ship.restoreInertia(null);
        assertNull(ship.captureInertia());
    }

    @Test
    @DisplayName("the driver is pinned by id, so it outlives the sub-level until forgotten")
    void driverByUuid() {
        UUID uuid = UUID.randomUUID();
        KinematicDriver driver = (KinematicDriver) Proxy.newProxyInstance(
            KinematicDriver.class.getClassLoader(), new Class<?>[] {KinematicDriver.class},
            (proxy, method, args) -> {
                if (method.getName().equals("toString")) return "driver";
                throw new UnsupportedOperationException(method.getName());
            });
        collected(uuid).setKinematicDriver(driver);
        assertSame(driver, collected(uuid).getKinematicDriver(), "any wrapper for the id finds it");
        assertEquals(1, SableManagedShip.driverCount());

        SableManagedShip.forgetDriver(uuid);
        assertNull(collected(uuid).getKinematicDriver());
        assertEquals(0, SableManagedShip.driverCount());

        collected(uuid).setKinematicDriver(driver);
        SableManagedShip.clearDrivers();
        assertEquals(0, SableManagedShip.driverCount());
    }
}
