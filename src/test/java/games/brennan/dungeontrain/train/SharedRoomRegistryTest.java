package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The drifting-room registry: found by world position, edited in room-local offsets, moved by relocation. */
class SharedRoomRegistryTest {

    private static final Vec3i SIZE = new Vec3i(16, 10, 12);

    @AfterEach
    void tidy() {
        SharedRoomRegistry.clear();
    }

    private static SharedRoomRegistry.Instance fresh(int pairKey, BlockPos origin) {
        return SharedRoomRegistry.register(null, pairKey, "abandonedroom", origin, SIZE, false, false, "",
                null, null, 0, "stone", Credits.EMPTY, Deaths.EMPTY);
    }

    @Test
    @DisplayName("A room is found by any world position inside its box and by nothing outside it")
    void resolvesByWorldPosition() {
        BlockPos origin = new BlockPos(100, -50, 40);
        SharedRoomRegistry.Instance inst = fresh(30, origin);
        assertSame(inst, SharedRoomRegistry.byWorldPos(null, origin));
        assertSame(inst, SharedRoomRegistry.byWorldPos(null, origin.offset(15, 9, 11)));
        assertNull(SharedRoomRegistry.byWorldPos(null, origin.offset(16, 0, 0)), "one past the length");
        assertNull(SharedRoomRegistry.byWorldPos(null, origin.offset(0, -1, 0)), "below the floor");
        assertSame(inst, SharedRoomRegistry.byPair(30));
        assertNull(SharedRoomRegistry.byPair(31));
    }

    @Test
    @DisplayName("Queued edits are room-local offsets, so a relocation keeps them and only moves the box")
    void relocationKeepsQueuedOffsets() {
        BlockPos origin = new BlockPos(100, -50, 40);
        SharedRoomRegistry.Instance inst = fresh(30, origin);
        BlockPos edited = origin.offset(3, 1, 2);
        inst.enqueue(inst.offsetOf(edited));
        assertTrue(inst.hasPending());

        BlockPos moved = new BlockPos(400, -50, 40);
        SharedRoomRegistry.relocate(30, moved);
        assertEquals(moved, inst.roomOrigin());
        assertNull(SharedRoomRegistry.byWorldPos(null, edited), "the old site no longer resolves");
        assertSame(inst, SharedRoomRegistry.byWorldPos(null, moved.offset(3, 1, 2)));
        Set<BlockPos> drained = inst.drainPending();
        assertEquals(Set.of(new BlockPos(3, 1, 2)), drained, "the offset is unchanged by the move");
        assertFalse(inst.hasPending());
    }

    @Test
    @DisplayName("Registering a pair again replaces its record; removing hands the old one back")
    void registerReplacesAndRemoveReturns() {
        SharedRoomRegistry.Instance first = fresh(30, new BlockPos(0, 0, 0));
        SharedRoomRegistry.Instance second = fresh(30, new BlockPos(50, 0, 0));
        assertSame(second, SharedRoomRegistry.byPair(30));
        assertEquals(1, SharedRoomRegistry.all().size());
        assertSame(second, SharedRoomRegistry.remove(30));
        assertNull(SharedRoomRegistry.remove(30));
        assertTrue(SharedRoomRegistry.isEmpty());
        assertNotNull(first); // the replaced record is simply dropped, never resurrected
    }

    @Test
    @DisplayName("The relay bookkeeping behaves as a carriage's: seq seeded, lease set and cleared, culled stops enqueue")
    void relayStateMirrorsACarriage() {
        SharedRoomRegistry.Instance inst = SharedRoomRegistry.register(null, 7, "beam", new BlockPos(0, 0, 0),
                SIZE, true, true, "abc", 42, "tok", 5, "stone", Credits.EMPTY, Deaths.EMPTY);
        assertTrue(inst.isOnRelay());
        assertEquals(5, inst.currentSeq());
        assertEquals(6, inst.nextSeq());
        inst.clearRelayLease();
        assertFalse(inst.isOnRelay());
        inst.onRelayLease(43, "tok2");
        assertEquals(43, inst.relayId());
        inst.markCulled();
        inst.enqueue(new BlockPos(1, 1, 1));
        assertFalse(inst.hasPending(), "a culled room queues nothing");
        assertEquals("pair=7 room=beam", inst.describe());
        assertFalse(inst.isAuthoredBy(java.util.UUID.fromString("00000000-0000-0000-0000-000000000abc")),
                "a three-character author key never matches a real uuid");
        SharedRoomRegistry.Instance byAlice = SharedRoomRegistry.register(null, 8, "beam", new BlockPos(0, 0, 0),
                SIZE, true, true, "00000000000000000000000000000abc", 1, "t", 0, "stone", Credits.EMPTY, Deaths.EMPTY);
        assertTrue(byAlice.isAuthoredBy(java.util.UUID.fromString("00000000-0000-0000-0000-000000000abc")),
                "dashless comparison against the relay's author key");
    }
}
