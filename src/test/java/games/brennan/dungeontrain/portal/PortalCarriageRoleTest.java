package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for the portal layout: a portal is one carriage group — entry corridor, the cart
 * between, exit corridor — rather than two corridors picked independently along the train.
 *
 * <p>Group size 3 throughout unless a test says otherwise, which is the default and the size at
 * which a portal fills a group exactly.</p>
 */
final class PortalCarriageRoleTest {

    private static final int GROUP = 3;
    /** Every group holds a portal, so the group arithmetic is what is under test and not the spacing. */
    private static final int EVERY = 1;

    // ---- roles ----------------------------------------------------------------

    @Test
    @DisplayName("the group's first corridor is the entry and its last is the exit")
    void rolesComeFromTheSlot() {
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(0, GROUP));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(2, GROUP));
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(3, GROUP));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(5, GROUP));
    }

    @Test
    @DisplayName("a pair's two corridors point at each other, across one cart")
    void partnersAreMutual() {
        assertEquals(2, PortalCarriageRole.partnerIndex(0, GROUP));   // entry → its exit
        assertEquals(0, PortalCarriageRole.partnerIndex(2, GROUP));   // exit → its entry
        assertEquals(5, PortalCarriageRole.partnerIndex(3, GROUP));
    }

    /** Exactly one carriage between the two corridors — the thing the whole layout rule is for. */
    @Test
    @DisplayName("entry and exit are two apart, so exactly one cart sits between them")
    void oneCartBetween() {
        for (int anchor : new int[] {-9, -3, 0, 3, 12}) {
            int entry = anchor + PortalCarriageSelection.SLOT_ENTRY;
            int exit = PortalCarriageRole.partnerIndex(entry, GROUP);
            assertEquals(2, exit - entry, "gap at anchor " + anchor);
            assertTrue(PortalCarriageSelection.isPortalMiddle(entry + 1, GROUP, EVERY),
                "the carriage between them should be the middle cart, at anchor " + anchor);
        }
    }

    @Test
    @DisplayName("every part of a pair resolves to the same key — its group's anchor")
    void pairKeyIsShared() {
        assertEquals(0, PortalCarriageRole.entryIndexOf(0, GROUP));
        assertEquals(0, PortalCarriageRole.entryIndexOf(1, GROUP));
        assertEquals(0, PortalCarriageRole.entryIndexOf(2, GROUP));
        assertEquals(3, PortalCarriageRole.entryIndexOf(3, GROUP));
        assertEquals(3, PortalCarriageRole.entryIndexOf(5, GROUP));
    }

    /**
     * Carriage indices go negative when the train extends backwards. Integer division and {@code %}
     * both truncate toward zero, which would mirror the slot order either side of the origin — the
     * exit corridor landing where the entry belongs on half the track.
     */
    @Test
    @DisplayName("slots and roles keep their order through negative indices")
    void negativeIndicesKeepTheirOrder() {
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(-3, GROUP));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(-1, GROUP));
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(-6, GROUP));

        assertEquals(-3, PortalCarriageRole.entryIndexOf(-3, GROUP));
        assertEquals(-3, PortalCarriageRole.entryIndexOf(-2, GROUP));
        assertEquals(-3, PortalCarriageRole.entryIndexOf(-1, GROUP));
    }

    // ---- selection ------------------------------------------------------------

    @Test
    @DisplayName("a portal group holds exactly one entry, one cart and one exit")
    void portalGroupIsWholeAndExact() {
        for (int anchor : new int[] {-6, -3, 0, 3, 6}) {
            int corridors = 0;
            int middles = 0;
            for (int slot = 0; slot < GROUP; slot++) {
                int index = anchor + slot;
                if (PortalCarriageSelection.isPortalCarriage(index, GROUP, EVERY)) corridors++;
                if (PortalCarriageSelection.isPortalMiddle(index, GROUP, EVERY)) middles++;
            }
            assertEquals(2, corridors, "corridors in group at " + anchor);
            assertEquals(1, middles, "middle carts in group at " + anchor);
        }
    }

    @Test
    @DisplayName("no carriage is ever both a corridor and the cart between them")
    void corridorAndMiddleArePartitioned() {
        for (int every : new int[] {1, 2, 5}) {
            for (int i = -40; i <= 40; i++) {
                boolean corridor = PortalCarriageSelection.isPortalCarriage(i, GROUP, every);
                boolean middle = PortalCarriageSelection.isPortalMiddle(i, GROUP, every);
                assertFalse(corridor && middle, "index " + i + " was both at every=" + every);
            }
        }
    }

    @Test
    @DisplayName("spacing skips whole groups, never splitting a portal across two of them")
    void spacingLeavesGroupsWhole() {
        int every = 2;
        // Group 0 (indices 0..2) holds a portal; group 1 (3..5) does not; group 2 (6..8) does.
        assertTrue(PortalCarriageSelection.isPortalCarriage(0, GROUP, every));
        assertTrue(PortalCarriageSelection.isPortalCarriage(2, GROUP, every));
        assertFalse(PortalCarriageSelection.isPortalCarriage(3, GROUP, every));
        assertFalse(PortalCarriageSelection.isPortalMiddle(4, GROUP, every));
        assertFalse(PortalCarriageSelection.isPortalCarriage(5, GROUP, every));
        assertTrue(PortalCarriageSelection.isPortalCarriage(6, GROUP, every));
    }

    /**
     * A group shorter than the portal cannot hold entry, cart and exit. Half a portal is worse than
     * none: an entry corridor whose exit landed in the next group would strand whoever walked in.
     */
    @Test
    @DisplayName("a group too short for the whole portal gets none at all")
    void tooSmallAGroupGetsNoPortal() {
        for (int groupSize : new int[] {1, 2}) {
            for (int i = -10; i <= 10; i++) {
                assertFalse(PortalCarriageSelection.isPortalCarriage(i, groupSize, EVERY),
                    "index " + i + " at group size " + groupSize);
                assertFalse(PortalCarriageSelection.isPortalMiddle(i, groupSize, EVERY),
                    "index " + i + " at group size " + groupSize);
            }
        }
    }

    @Test
    @DisplayName("switching portals off selects nothing anywhere")
    void offSelectsNothing() {
        for (int i = -20; i <= 20; i++) {
            assertFalse(PortalCarriageSelection.isPortalCarriage(
                i, GROUP, PortalCarriageSelection.CARRIAGE_EVERY_OFF));
            assertFalse(PortalCarriageSelection.isPortalMiddle(
                i, GROUP, PortalCarriageSelection.CARRIAGE_EVERY_OFF));
        }
    }

    /**
     * A bigger group keeps the portal at its front and leaves the rest ordinary — those carriages are
     * reachable, since a player leaves the exit corridor through its far door.
     */
    @Test
    @DisplayName("in a larger group the portal takes the first three slots and no more")
    void largerGroupsKeepTheRestOrdinary() {
        int groupSize = 5;
        assertTrue(PortalCarriageSelection.isPortalCarriage(0, groupSize, EVERY));
        assertTrue(PortalCarriageSelection.isPortalMiddle(1, groupSize, EVERY));
        assertTrue(PortalCarriageSelection.isPortalCarriage(2, groupSize, EVERY));
        assertFalse(PortalCarriageSelection.isPortalCarriage(3, groupSize, EVERY));
        assertFalse(PortalCarriageSelection.isPortalMiddle(3, groupSize, EVERY));
        assertFalse(PortalCarriageSelection.isPortalCarriage(4, groupSize, EVERY));
    }
}
