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
}
