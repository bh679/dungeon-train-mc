package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRoomSaveHoldTest {

    @AfterEach
    void reset() {
        PortalRoomSaveHold.clear();
    }

    @Test
    void holdsEveryChunkTheBoxReaches() {
        PortalRoomSaveHold.hold(234, new AABB(9544, -62, -8, 9578, -40, 17));
        // x 9544..9578 → chunks 596..598; z -8..17 → chunks -1..1.
        assertEquals(9, PortalRoomSaveHold.heldChunks());
        assertTrue(PortalRoomSaveHold.isHeld(ChunkPos.asLong(596, -1)));
        assertTrue(PortalRoomSaveHold.isHeld(ChunkPos.asLong(598, 1)));
        assertFalse(PortalRoomSaveHold.isHeld(ChunkPos.asLong(599, 0)));
    }

    @Test
    void relocatingMovesTheHoldOffTheOldSite() {
        PortalRoomSaveHold.hold(234, new AABB(9545, -62, 0, 9555, -50, 10));
        PortalRoomSaveHold.hold(234, new AABB(10324, -62, 0, 10334, -50, 10));
        assertFalse(PortalRoomSaveHold.isHeld(ChunkPos.asLong(9545 >> 4, 0)));
        assertTrue(PortalRoomSaveHold.isHeld(ChunkPos.asLong(10324 >> 4, 0)));
    }

    @Test
    void aSharedChunkStaysHeldUntilBothPairsLetGo() {
        PortalRoomSaveHold.hold(1, new AABB(0, -62, 0, 10, -50, 10));
        PortalRoomSaveHold.hold(2, new AABB(5, -50, 0, 12, -38, 10));
        long shared = ChunkPos.asLong(0, 0);
        PortalRoomSaveHold.release(1);
        assertTrue(PortalRoomSaveHold.isHeld(shared));
        PortalRoomSaveHold.release(2);
        assertFalse(PortalRoomSaveHold.isHeld(shared));
        assertEquals(0, PortalRoomSaveHold.heldChunks());
    }
}
