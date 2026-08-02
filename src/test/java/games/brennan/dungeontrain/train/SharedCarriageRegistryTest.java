package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
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
        SharedCarriageRegistry.register(null, sub, train, 0, new BlockPos(0, 64, 0), DIMS, "shared", false, null, null, 0, "stone");
        SharedCarriageRegistry.register(null, sub, train, 1, new BlockPos(9, 64, 0), DIMS, "shared", false, null, null, 0, "stone");

        assertTrue(SharedCarriageRegistry.hasSubLevel(sub));
        assertEquals(2, SharedCarriageRegistry.bySubLevel(sub).size());
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
            null, sub, UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null, 0, "stone");
        assertTrue(SharedCarriageRegistry.hasSubLevel(sub));
        SharedCarriageRegistry.remove(inst);
        assertFalse(SharedCarriageRegistry.hasSubLevel(sub));
    }

    @Test
    void outboxEnqueueDrainAndReenqueue() {
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null, 0, "stone");
        assertFalse(inst.hasPending());
        inst.enqueue(new BlockPos(1, 2, 3));
        inst.enqueue(new BlockPos(1, 2, 3)); // deduped by pos
        inst.enqueue(new BlockPos(4, 5, 6));
        assertTrue(inst.hasPending());

        Set<BlockPos> drained = inst.drainPending();
        assertEquals(2, drained.size());
        assertTrue(drained.contains(new BlockPos(1, 2, 3)));
        assertFalse(inst.hasPending()); // drained → empty

        inst.reenqueue(drained); // failed upload → re-queue for retry
        assertTrue(inst.hasPending());
        assertEquals(2, inst.drainPending().size());
    }

    @Test
    void enqueueIsANoOpOnceCulled() {
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null, 0, "stone");
        inst.markCulled();
        assertTrue(inst.isCulled());
        inst.enqueue(new BlockPos(1, 1, 1));
        assertFalse(inst.hasPending()); // ignored — the flusher is done with a culling carriage
    }

    @Test
    void seqIsMonotonicAndSeededFromRelayIdentity() {
        // Fresh local build: seqSeed 0 → first delta seq is 1.
        SharedCarriageRegistry.Instance fresh = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null, 0, "stone");
        assertEquals(0, fresh.currentSeq());
        assertEquals(1, fresh.nextSeq());
        assertEquals(2, fresh.nextSeq());
        assertEquals(2, fresh.currentSeq());

        // Pooled lease seeded from max(baseSeq, delta seqs) = 7 → next delta clears the relay watermark.
        SharedCarriageRegistry.Instance pooled = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", true, 42, "tok", 7, "stone");
        assertEquals(7, pooled.currentSeq());
        assertEquals(8, pooled.nextSeq());
    }

    @Test
    void leaseAndRebaselineStateTransitions() {
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(
            null, UUID.randomUUID(), UUID.randomUUID(), 0, new BlockPos(0, 0, 0), DIMS, "shared", false, null, null, 0, "stone");
        assertFalse(inst.isOnRelay());
        inst.onRelayLease(5, "tok");
        assertTrue(inst.isOnRelay());
        assertEquals(5, inst.relayId());
        assertEquals("tok", inst.leaseToken());
        inst.clearRelayLease();
        assertFalse(inst.isOnRelay());

        assertFalse(inst.needsRebaseline());
        inst.markRebaseline();
        assertTrue(inst.needsRebaseline());
        inst.clearRebaseline();
        assertFalse(inst.needsRebaseline());
    }
}
