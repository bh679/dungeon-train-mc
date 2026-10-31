package games.brennan.dungeontrain.worldgen.feature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core pass keeps decoration off the slab's roof, and only while it is running. */
final class NetherCoreDecorationTest {

    @AfterEach
    void closeAnyPass() {
        while (NetherCoreDecoration.isActive()) NetherCoreDecoration.end();
    }

    @Test
    @DisplayName("outside the pass nothing is above the roof")
    void inactiveNeverAboveRoof() {
        assertFalse(NetherCoreDecoration.isActive());
        assertFalse(NetherCoreDecoration.isAboveRoof(10_000));
    }

    @Test
    @DisplayName("inside the pass: the roof row and below are kept, above it is dropped")
    void roofBoundary() {
        NetherCoreDecoration.begin(156);
        assertTrue(NetherCoreDecoration.isActive());
        assertFalse(NetherCoreDecoration.isAboveRoof(156));
        assertFalse(NetherCoreDecoration.isAboveRoof(40));
        assertTrue(NetherCoreDecoration.isAboveRoof(157));
        NetherCoreDecoration.end();
        assertFalse(NetherCoreDecoration.isAboveRoof(157));
    }

    @Test
    @DisplayName("a nested pass uses its own roof and restores the parent's on end")
    void nestedPassRestoresParentRoof() {
        NetherCoreDecoration.begin(156);
        NetherCoreDecoration.begin(100);
        assertTrue(NetherCoreDecoration.isAboveRoof(120));
        NetherCoreDecoration.end();
        assertTrue(NetherCoreDecoration.isActive());
        assertFalse(NetherCoreDecoration.isAboveRoof(120));
        assertTrue(NetherCoreDecoration.isAboveRoof(157));
    }
}
