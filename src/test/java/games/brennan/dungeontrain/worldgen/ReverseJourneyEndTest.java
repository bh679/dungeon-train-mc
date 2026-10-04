package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.advancement.BandAdvancements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "tfarcenim" fires past the far (-X) edge of the first reversed run's Nether: on the shipped layout that
 * edge must be exactly where the Nether slot ends and the run's plain-overworld lead slot begins.
 */
final class ReverseJourneyEndTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();

    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    @Test
    @DisplayName("the reverse journey ends at the first reversed Nether's -X edge, with plain overworld beyond")
    void edgeIsTheNetherSlotEdge() {
        int nether = LAYOUT.indexOfOccurrence(CycleLayout.Type.NETHER, 0);
        int edge = (int) BandAdvancements.reverseJourneyEndX(C);
        assertTrue(C.isMirroredAt(edge));
        assertEquals(nether, C.slotIndexAt(edge), "edge is inside the Nether slot");
        assertEquals(nether - 1, C.slotIndexAt(edge - 1), "just past it is the slot before the Nether");
        assertEquals(CycleLayout.Type.OVERWORLD, LAYOUT.slot(nether - 1).type());
        int beyond = edge - BandAdvancements.ENTRY_DEPTH_BLOCKS - 1;
        assertEquals(CycleLayout.Type.OVERWORLD, LAYOUT.slot(C.slotIndexAt(beyond)).type(),
                "the trigger point is still plain overworld");
    }
}
