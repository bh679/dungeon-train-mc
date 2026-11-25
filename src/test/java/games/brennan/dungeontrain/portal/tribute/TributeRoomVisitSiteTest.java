package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomVisitSiteTest {

    @Test
    void lowestFreeSlotWins() {
        assertEquals(0, TributeRoomVisitSite.freeSlot(Set.of()));
        assertEquals(1, TributeRoomVisitSite.freeSlot(Set.of(0)));
        assertEquals(1, TributeRoomVisitSite.freeSlot(Set.of(0, 2)));
        assertEquals(3, TributeRoomVisitSite.freeSlot(Set.of(0, 1, 2)));
    }

    @Test
    void slotsNeverShareAStructureSpan() {
        BlockPos a = TributeRoomVisitSite.originFor(0, -60, 0);
        BlockPos b = TributeRoomVisitSite.originFor(1, -60, 0);
        assertEquals(TributeRoomVisitSite.SLOT_STRIDE, b.getX() - a.getX());
        assertEquals(TributeRoomVisitSite.Z_BAND, a.getZ());
        assertEquals(a.getZ(), b.getZ());
    }

    @Test
    void bandIsOffTheTestRigs() {
        // PortalTestCommand stands tests at +512; the visit band is the other way.
        assertTrue(TributeRoomVisitSite.Z_BAND < 0);
        assertNotEquals(512, Math.abs(TributeRoomVisitSite.Z_BAND) == 512 ? -1 : 512);
    }

    @Test
    void originSpendsTheDoorHeightOffsetOnTheLane() {
        assertEquals(-60 + 3, TributeRoomVisitSite.originFor(0, -60, 3).getY());
    }

    @Test
    void keptSlotsCoverEveryKeptSitesWindow() {
        for (int slot = 0; slot < TributeRoomVisitSite.KEPT_SLOTS; slot++) {
            BlockPos o = TributeRoomVisitSite.originFor(slot, -60, 0);
            // The structure reaches well past its origin along X; its window stays inside the slot stride.
            for (int x = o.getX() - 16; x < o.getX() + TributeRoomVisitSite.SLOT_STRIDE; x += 8) {
                for (int z = o.getZ() - 32; z <= o.getZ() + 32; z += 8) {
                    assertTrue(TributeRoomVisitSite.isSiteChunk(x >> 4, z >> 4), "slot " + slot + " at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void siteChunksStayOffTheTrackAndTheTestRig() {
        assertFalse(TributeRoomVisitSite.isSiteChunk(0, 0), "track band");
        assertFalse(TributeRoomVisitSite.isSiteChunk(0, 512 >> 4), "test rig band");
        assertFalse(TributeRoomVisitSite.isSiteChunk(-100, TributeRoomVisitSite.Z_BAND >> 4), "behind slot 0");
        int pastKept = (TributeRoomVisitSite.KEPT_SLOTS + 2) * TributeRoomVisitSite.SLOT_STRIDE;
        assertFalse(TributeRoomVisitSite.isSiteChunk(pastKept >> 4, TributeRoomVisitSite.Z_BAND >> 4), "past the kept slots");
    }
}
