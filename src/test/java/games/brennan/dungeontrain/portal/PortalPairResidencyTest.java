package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.ship.ManagedShip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Remembering which carriage group a portal pair rides on, and why the handle has to outlive the
 * group being culled.
 *
 * <p>The lookup this replaces is {@code Trains.knownGroups}, which is keyed by train id — and
 * reaching a train id means finding a <b>loaded</b> carriage of that train. A pair whose group has
 * just been culled, with a player standing eight rooms deep in its room, may have no loaded carriage
 * anywhere near it, so that is exactly the moment the lookup stops answering. The handle recorded
 * here survives the cull and still names the sub-level, which is all {@link PortalCarriageRevival}
 * needs to find it in holding.</p>
 *
 * <p>{@code syncTo} is not exercised here: taking a ticket needs a live {@code Shipyard} off a
 * {@code ServerLevel}, so its coverage is the in-game ride-through.</p>
 */
class PortalPairResidencyTest {

    private static final int PAIR = 24;
    private static final int OTHER_PAIR = 48;

    @BeforeEach
    @AfterEach
    void reset() {
        PortalPairResidency.clear();
    }

    @Test
    @DisplayName("a pair no walk has reached has no group")
    void unknownPairHasNoGroup() {
        assertNull(PortalPairResidency.groupFor(PAIR));
        assertFalse(PortalPairResidency.holds(PAIR));
        assertEquals(0, PortalPairResidency.held());
    }

    @Test
    @DisplayName("the handle is still there after the group is culled — the whole point")
    void handleOutlivesResidency() {
        AtomicBoolean resident = new AtomicBoolean(true);
        ManagedShip ship = stubShip(resident);
        PortalPairResidency.note(PAIR, ship);

        resident.set(false);
        assertSame(ship, PortalPairResidency.groupFor(PAIR),
            "a culled group must still be findable, or there is nothing to revive");
    }

    @Test
    @DisplayName("both corridors of a pair write the same handle")
    void noteIsIdempotentAcrossTheGroup() {
        ManagedShip ship = stubShip(new AtomicBoolean(true));
        PortalPairResidency.note(PAIR, ship);
        PortalPairResidency.note(PAIR, ship);
        assertSame(ship, PortalPairResidency.groupFor(PAIR));
    }

    @Test
    @DisplayName("a fresh group at the same pair key replaces the old handle")
    void respawnedGroupReplacesTheHandle() {
        // A pair key is a fixed place along the track, so it outlives the carriage occupying it.
        ManagedShip first = stubShip(new AtomicBoolean(true));
        ManagedShip second = stubShip(new AtomicBoolean(true));
        PortalPairResidency.note(PAIR, first);
        PortalPairResidency.note(PAIR, second);
        assertSame(second, PortalPairResidency.groupFor(PAIR));
    }

    @Test
    @DisplayName("a null handle is ignored rather than blanking a good one")
    void nullHandleIsIgnored() {
        ManagedShip ship = stubShip(new AtomicBoolean(true));
        PortalPairResidency.note(PAIR, ship);
        PortalPairResidency.note(PAIR, null);
        assertSame(ship, PortalPairResidency.groupFor(PAIR));
    }

    @Test
    @DisplayName("pairs do not share a handle")
    void pairsAreSeparate() {
        PortalPairResidency.note(PAIR, stubShip(new AtomicBoolean(true)));
        assertNull(PortalPairResidency.groupFor(OTHER_PAIR));
    }

    @Test
    @DisplayName("clearing forgets everything — a pair key means a different train next world")
    void clearForgets() {
        PortalPairResidency.note(PAIR, stubShip(new AtomicBoolean(true)));
        PortalPairResidency.clear();
        assertNull(PortalPairResidency.groupFor(PAIR));
    }

    /**
     * A {@link ManagedShip} that answers only the two questions this class asks of one.
     *
     * <p>A proxy rather than a written-out stub because {@code ManagedShip} is the seam onto the
     * physics mod: implementing it in full would drag JOML's ship-transform types into the test
     * classpath and would have to be edited every time Sable's side of that seam grows a method,
     * which is a maintenance cost for a class that only ever puts the handle in a map. Anything not
     * named here throws, so a test that starts leaning on some other method fails loudly rather than
     * asserting against a plausible-looking zero.</p>
     */
    private static ManagedShip stubShip(AtomicBoolean resident) {
        UUID subLevelId = UUID.randomUUID();
        return (ManagedShip) Proxy.newProxyInstance(
            ManagedShip.class.getClassLoader(),
            new Class<?>[] {ManagedShip.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "subLevelId" -> subLevelId;
                case "isResident" -> resident.get();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "stub-ship[" + subLevelId + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
