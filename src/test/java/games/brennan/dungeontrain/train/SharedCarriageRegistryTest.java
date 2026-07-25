package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedCarriageRegistryTest {

    // CarriageDims(length, width, height) — so this carriage spans x:[0,9) y:[0,7) z:[0,7).
    private static final CarriageDims DIMS = new CarriageDims(9, 7, 7);

    @AfterEach
    void tidy() {
        SharedCarriageRegistry.clear();
    }

    @Test
    void resolvesTheRightCarriageByFootprintInAGroupedSubLevel() {
        UUID sub = UUID.randomUUID();
        UUID train = UUID.randomUUID();
        // Two carriages packed into one sub-level (grouped train): x-origins 0 and 9.
        SharedCarriageRegistry.register(null, sub, train, 0, new BlockPos(0, 64, 0), DIMS, "shared", false, null, null);
        SharedCarriageRegistry.register(null, sub, train, 1, new BlockPos(9, 64, 0), DIMS, "shared", false, null, null);

        assertTrue(SharedCarriageRegistry.hasSubLevel(sub));
        SharedCarriageRegistry.Instance a = SharedCarriageRegistry.resolve(sub, 3, 65, 3);
        SharedCarriageRegistry.Instance b = SharedCarriageRegistry.resolve(sub, 12, 65, 3);
        assertNotNull(a);
        assertEquals(0, a.pIdx);
        assertNotNull(b);
        assertEquals(1, b.pIdx);
        assertNull(SharedCarriageRegistry.resolve(sub, 100, 65, 3)); // outside every footprint
        assertNull(SharedCarriageRegistry.resolve(UUID.randomUUID(), 3, 65, 3)); // unknown sub-level
    }

    @Test
    void removeDropsTheInstanceAndItsEmptySubLevel() {
        UUID sub = UUID.randomUUID();
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
            null, sub, UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null);
        assertTrue(SharedCarriageRegistry.hasSubLevel(sub));
        SharedCarriageRegistry.remove(inst);
        assertFalse(SharedCarriageRegistry.hasSubLevel(sub));
    }

    @Test
    void dirtyAndLeaseStateTransitions() {
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null);
        assertFalse(inst.isDirty());
        inst.markDirty();
        assertTrue(inst.isDirty());
        inst.clearDirty();
        assertFalse(inst.isDirty());

        assertFalse(inst.isOnRelay());
        inst.onRelayLease(5, "tok");
        assertTrue(inst.isOnRelay());
        assertEquals(5, inst.relayId());
        assertEquals("tok", inst.leaseToken());
    }
}
