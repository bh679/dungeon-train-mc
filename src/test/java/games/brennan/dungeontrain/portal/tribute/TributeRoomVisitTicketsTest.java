package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomVisitTicketsTest {

    /** Every chunk the stamp waits on must be one the room's ticket brings to full, or the wait never ends. */
    private static void assertTicketCoversWait(BoundingBox window) {
        ChunkPos centre = TributeRoomVisitTickets.centre(window);
        int radius = TributeRoomVisitTickets.radius(window);
        for (int cx = TributeRoomVisitTickets.minWaitX(window); cx <= TributeRoomVisitTickets.maxWaitX(window); cx++) {
            for (int cz = TributeRoomVisitTickets.minWaitZ(window); cz <= TributeRoomVisitTickets.maxWaitZ(window); cz++) {
                int d = Math.max(Math.abs(cx - centre.x), Math.abs(cz - centre.z));
                assertTrue(d <= radius, "chunk " + cx + "," + cz + " is " + d + " from " + centre + ", ticket radius " + radius + " for " + window);
            }
        }
    }

    @Test
    void ticketCoversTheVisitWindowAndItsRing() {
        // The window a slot-0 visit stood in a dev world.
        assertTicketCoversWait(new BoundingBox(-4, -63, -516, 40, -51, -502));
    }

    @Test
    void ticketCoversEverySlotAndAnyWindowOffset() {
        for (int slot = 0; slot < TributeRoomVisitSite.KEPT_SLOTS + 4; slot++) {
            for (int shift = 0; shift < 16; shift++) {
                int x = slot * TributeRoomVisitSite.SLOT_STRIDE - 4 + shift;
                int z = TributeRoomVisitSite.Z_BAND - 4 + shift;
                for (int len : new int[] {30, 45, 60}) {
                    assertTicketCoversWait(new BoundingBox(x, -63, z, x + len - 1, -51, z + 14));
                }
            }
        }
    }

    @Test
    void waitCountsTheWindowAndOneRing() {
        BoundingBox window = new BoundingBox(-4, -63, -516, 40, -51, -502);
        // x chunks -1..2 plus a ring = 6, z chunks -33..-32 plus a ring = 4.
        assertEquals(24, TributeRoomVisitTickets.waitedOn(window));
    }
}
