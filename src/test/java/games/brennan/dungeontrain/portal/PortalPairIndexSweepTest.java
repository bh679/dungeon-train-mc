package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A published pairing holds its carriage's plot — every chunk of the carriage — so a pairing the tick
 * walk stops republishing has to leave the index, or every portal carriage the train ever passed
 * stays on the heap.
 */
final class PortalPairIndexSweepTest {

    @BeforeEach
    @AfterEach
    void reset() {
        PortalPairIndex.clear();
    }

    private static PortalPairIndex.Entry entry(int carriageIndex) {
        ManagedShip ship = (ManagedShip) Proxy.newProxyInstance(
            ManagedShip.class.getClassLoader(), new Class<?>[] {ManagedShip.class},
            (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        return new PortalPairIndex.Entry(carriageIndex, null, ship, new Vec3(0, 0, 0), BlockPos.ZERO,
            new CarriageDims(9, 7, 7), PortalCorridorKind.values()[0], null);
    }

    @Test
    @DisplayName("an entry republished every generation stays")
    void republishedStays() {
        PortalPairIndex.publish(12, entry(12));
        assertEquals(0, PortalPairIndex.sweep());
        PortalPairIndex.publish(12, entry(12));
        assertEquals(0, PortalPairIndex.sweep());
        assertEquals(1, PortalPairIndex.size());
    }

    @Test
    @DisplayName("an entry the walk stopped republishing is dropped at the next sweep")
    void stalePairIsDropped() {
        PortalPairIndex.publish(12, entry(12));
        PortalPairIndex.publish(48, entry(48));
        PortalPairIndex.sweep();
        PortalPairIndex.publish(48, entry(48)); // the train rolled past pair 12
        assertEquals(1, PortalPairIndex.sweep());
        assertEquals(1, PortalPairIndex.size());
        assertTrue(PortalPairIndex.all().iterator().next().carriageIndex() == 48);
    }

    @Test
    @DisplayName("clear empties the stamps too, so nothing resurrects")
    void clearIsTotal() {
        PortalPairIndex.publish(12, entry(12));
        PortalPairIndex.clear();
        assertEquals(0, PortalPairIndex.sweep());
        assertTrue(PortalPairIndex.isEmpty());
    }
}
