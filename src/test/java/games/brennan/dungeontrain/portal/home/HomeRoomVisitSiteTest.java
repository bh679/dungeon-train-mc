package games.brennan.dungeontrain.portal.home;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HomeRoomVisitSiteTest {

    @Test
    void lowestFreeSlotWins() {
        assertEquals(0, HomeRoomVisitSite.freeSlot(Set.of()));
        assertEquals(1, HomeRoomVisitSite.freeSlot(Set.of(0)));
        assertEquals(1, HomeRoomVisitSite.freeSlot(Set.of(0, 2)));
        assertEquals(3, HomeRoomVisitSite.freeSlot(Set.of(0, 1, 2)));
    }

    @Test
    void slotsNeverShareAStructureSpan() {
        BlockPos a = HomeRoomVisitSite.originFor(0, -60, 0);
        BlockPos b = HomeRoomVisitSite.originFor(1, -60, 0);
        assertEquals(HomeRoomVisitSite.SLOT_STRIDE, b.getX() - a.getX());
        assertEquals(HomeRoomVisitSite.Z_BAND, a.getZ());
        assertEquals(a.getZ(), b.getZ());
    }

    @Test
    void bandIsOffTheTestRigs() {
        // PortalTestCommand stands tests at +512; the visit band is the other way.
        assertTrue(HomeRoomVisitSite.Z_BAND < 0);
        assertNotEquals(512, Math.abs(HomeRoomVisitSite.Z_BAND) == 512 ? -1 : 512);
    }

    @Test
    void originSpendsTheDoorHeightOffsetOnTheLane() {
        assertEquals(-60 + 3, HomeRoomVisitSite.originFor(0, -60, 3).getY());
    }
}
