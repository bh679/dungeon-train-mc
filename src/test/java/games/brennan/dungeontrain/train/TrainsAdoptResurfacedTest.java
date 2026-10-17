package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Trains#adoptResurfaced}: a group Sable restored on its own (autosave, then the holding chunk
 * loads again) must replace the registry's removed handle, or every resident-gated reader skips it.
 */
final class TrainsAdoptResurfacedTest {

    private static final UUID TRAIN = new UUID(7, 7);

    @AfterEach
    void clear() {
        Trains.clearRegistry();
    }

    @Test
    @DisplayName("A stale handle is re-pointed at the live instance of the same sub-level")
    void staleHandleAdoptsLiveInstance() {
        UUID id = new UUID(1, 1911);
        Fake stale = new Fake(id, false);
        Fake live = new Fake(id, true);
        Trains.registerSpawned(TRAIN, 1911, stale);

        assertEquals(List.of(1911), Trains.adoptResurfaced(TRAIN, List.of(live)));
        assertSame(live, Trains.knownGroups(TRAIN).get(1911));
    }

    @Test
    @DisplayName("A resident handle is never swapped")
    void residentHandleLeftAlone() {
        UUID id = new UUID(1, 5);
        Fake registered = new Fake(id, true);
        Trains.registerSpawned(TRAIN, 5, registered);

        assertTrue(Trains.adoptResurfaced(TRAIN, List.of(new Fake(id, true))).isEmpty());
        assertSame(registered, Trains.knownGroups(TRAIN).get(5));
    }

    @Test
    @DisplayName("A live ship with a different sub-level id is not adopted")
    void differentIdLeftAlone() {
        Fake stale = new Fake(new UUID(1, 1), false);
        Trains.registerSpawned(TRAIN, 1, stale);

        assertTrue(Trains.adoptResurfaced(TRAIN, List.of(new Fake(new UUID(1, 2), true))).isEmpty());
        assertSame(stale, Trains.knownGroups(TRAIN).get(1));
    }

    @Test
    @DisplayName("A visible ship with no registry entry is never added")
    void unknownAnchorNeverAdded() {
        Trains.registerSpawned(TRAIN, 1, new Fake(new UUID(1, 1), true));

        assertTrue(Trains.adoptResurfaced(TRAIN, List.of(new Fake(new UUID(9, 9), true))).isEmpty());
        assertEquals(1, Trains.knownGroups(TRAIN).size());
        assertFalse(Trains.knownGroups(new UUID(0, 0)).containsKey(1));
    }

    @Test
    @DisplayName("A non-resident visible ship does not replace a non-resident handle")
    void nonResidentCandidateIgnored() {
        UUID id = new UUID(1, 3);
        Fake stale = new Fake(id, false);
        Trains.registerSpawned(TRAIN, 3, stale);

        assertTrue(Trains.adoptResurfaced(TRAIN, List.of(new Fake(id, false))).isEmpty());
        assertSame(stale, Trains.knownGroups(TRAIN).get(3));
    }

    private static final class Fake implements ManagedShip {
        private final UUID id;
        private final boolean resident;

        Fake(UUID id, boolean resident) {
            this.id = id;
            this.resident = resident;
        }

        @Override public boolean isResident() { return resident; }
        @Override public Vector3d worldToShip(Vector3d w) { return w; }
        @Override public Vector3d shipToWorld(Vector3d p) { return p; }
        @Override public long id() { return id.getLeastSignificantBits(); }
        @Override public UUID subLevelId() { return id; }
        @Override public Vector3dc currentWorldPosition() { return new Vector3d(); }
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
}
