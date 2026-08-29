package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalStampRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot arithmetic behind the debug panel's cart-type labels.
 *
 * <p>The panel's own resolver needs a live {@code ServerLevel}, so what is pinned here is the
 * arithmetic it delegates to — which slot of its group an index occupies, and therefore whether it
 * reads as an entry corridor, an exit corridor or the middle cart. These run against the real
 * {@link PortalCarriageSelection} / {@link PortalStampRecord} helpers rather than a reimplementation,
 * so a change to the grouping rule shows up here rather than silently mislabelling the panel.</p>
 */
final class CartTypeSlotTest {

    /** A portal occupies one group: entry | middle | exit. */
    private static final int GROUP = PortalCarriageSelection.PORTAL_GROUP_SPAN;

    @Test
    @DisplayName("the three slots of a portal group are entry, middle, exit in order")
    void slots_mapInOrder() {
        assertEquals(PortalCarriageSelection.SLOT_ENTRY, PortalCarriageSelection.slotOf(0, GROUP));
        assertEquals(PortalCarriageSelection.SLOT_MIDDLE, PortalCarriageSelection.slotOf(1, GROUP));
        assertEquals(PortalCarriageSelection.SLOT_EXIT, PortalCarriageSelection.slotOf(2, GROUP));
    }

    @Test
    @DisplayName("only the outer two slots are corridors — the middle cart is not")
    void corridorSlots_areTheOuterTwo() {
        assertTrue(PortalStampRecord.isCorridorSlot(0, GROUP), "entry");
        assertFalse(PortalStampRecord.isCorridorSlot(1, GROUP), "middle cart is not a corridor");
        assertTrue(PortalStampRecord.isCorridorSlot(2, GROUP), "exit");
    }

    @Test
    @DisplayName("negative carriage indices still land on the right slot")
    void negativeIndices_wrapCorrectly() {
        // floorMod, not %, so -1 is the last slot of the previous group rather than -1.
        assertEquals(PortalCarriageSelection.SLOT_EXIT, PortalCarriageSelection.slotOf(-1, GROUP));
        assertEquals(PortalCarriageSelection.SLOT_MIDDLE, PortalCarriageSelection.slotOf(-2, GROUP));
        assertEquals(PortalCarriageSelection.SLOT_ENTRY, PortalCarriageSelection.slotOf(-3, GROUP));
        assertTrue(PortalStampRecord.isCorridorSlot(-1, GROUP));
        assertFalse(PortalStampRecord.isCorridorSlot(-2, GROUP));
    }

    @Test
    @DisplayName("a slot outside the enclosed run is a pad — that is the pad test the panel makes")
    void padDetection_isSlotOutsideTheRun() {
        int groupSize = 3;
        assertTrue(isPad(-1, groupSize), "back pad sits before slot 0");
        assertTrue(isPad(groupSize, groupSize), "front pad sits after the last enclosed slot");
        for (int slot = 0; slot < groupSize; slot++) {
            assertFalse(isPad(slot, groupSize), "slot " + slot + " is an enclosed carriage");
        }
    }

    @Test
    @DisplayName("a lone-carriage group has no pads, so no slot can be one")
    void padDetection_neverFiresForGroupSizeOne() {
        assertFalse(isPad(-1, 1));
        assertFalse(isPad(1, 1));
    }

    /** Mirrors the guard in {@code TrainCarriageAppender.cartTypeAt}. */
    private static boolean isPad(int slot, int groupSize) {
        return groupSize > 1 && (slot < 0 || slot >= groupSize);
    }
}
