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
    /** Every group holds a portal, so the group arithmetic is what is under test and not the lottery. */
    private static final int EVERY = 1;
    /** One arbitrary world's seed. Which groups it picks is {@link PortalCarriageLotteryTest}'s business. */
    private static final long SEED = 0x5DEADBEEFL;

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
            assertTrue(PortalCarriageSelection.isPortalMiddle(entry + 1, GROUP, EVERY, SEED),
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
                if (PortalCarriageSelection.isPortalCarriage(index, GROUP, EVERY, SEED)) corridors++;
                if (PortalCarriageSelection.isPortalMiddle(index, GROUP, EVERY, SEED)) middles++;
            }
            assertEquals(2, corridors, "corridors in group at " + anchor);
            assertEquals(1, middles, "middle carts in group at " + anchor);
        }
    }

    /**
     * The predicate every system that has to leave a portal alone asks — the placer's skipped passes,
     * and the shared-carriage relay's refusal to serve or pool one. A gap in it does not read as a
     * portal bug: it reads as somebody else's carriage standing where an entrance should be, which is
     * how a leased build came to be stamped over a corridor in the first place.
     */
    @Test
    @DisplayName("every slot of a portal group is a part, and nothing outside one is")
    void portalPartCoversTheWholeGroup() {
        for (int anchor : new int[] {-6, -3, 0, 3, 6}) {
            for (int slot = 0; slot < GROUP; slot++) {
                assertTrue(PortalCarriageSelection.isPortalPart(anchor + slot, GROUP, EVERY, SEED),
                    "slot " + slot + " of the group at " + anchor);
            }
        }
    }

    @Test
    @DisplayName("a part is a corridor or the cart between, and nothing else ever is")
    void portalPartIsExactlyTheUnion() {
        for (int every : new int[] {1, 2, 5}) {
            for (int i = -40; i <= 40; i++) {
                boolean union = PortalCarriageSelection.isPortalCarriage(i, GROUP, every, SEED)
                    || PortalCarriageSelection.isPortalMiddle(i, GROUP, every, SEED);
                assertEquals(union, PortalCarriageSelection.isPortalPart(i, GROUP, every, SEED),
                    "index " + i + " at every=" + every);
            }
        }
    }

    /** A larger group's trailing carriages are reachable and ordinary — they may still be shared. */
    @Test
    @DisplayName("in a larger group only the first three slots are parts")
    void portalPartStopsAtTheGroupsPortal() {
        int groupSize = 5;
        assertTrue(PortalCarriageSelection.isPortalPart(0, groupSize, EVERY, SEED));
        assertTrue(PortalCarriageSelection.isPortalPart(1, groupSize, EVERY, SEED));
        assertTrue(PortalCarriageSelection.isPortalPart(2, groupSize, EVERY, SEED));
        assertFalse(PortalCarriageSelection.isPortalPart(3, groupSize, EVERY, SEED));
        assertFalse(PortalCarriageSelection.isPortalPart(4, groupSize, EVERY, SEED));
    }

    @Test
    @DisplayName("no carriage is ever both a corridor and the cart between them")
    void corridorAndMiddleArePartitioned() {
        for (int every : new int[] {1, 2, 5}) {
            for (int i = -40; i <= 40; i++) {
                boolean corridor = PortalCarriageSelection.isPortalCarriage(i, GROUP, every, SEED);
                boolean middle = PortalCarriageSelection.isPortalMiddle(i, GROUP, every, SEED);
                assertFalse(corridor && middle, "index " + i + " was both at every=" + every);
            }
        }
    }

    /**
     * The lottery draws per group, never per carriage. A group that came up half-selected would put
     * an entry corridor on a train whose exit was never stamped — a one-way door into a sealed room.
     */
    @Test
    @DisplayName("the lottery takes whole groups, never splitting a portal across two of them")
    void lotteryLeavesGroupsWhole() {
        for (int every : new int[] {2, 5, 20}) {
            for (long seed : new long[] {SEED, 0L, -1L}) {
                for (int anchor = -60; anchor <= 60; anchor += GROUP) {
                    boolean chosen = PortalCarriageSelection.isPortalPart(anchor, GROUP, every, seed);
                    for (int slot = 0; slot < GROUP; slot++) {
                        assertEquals(chosen,
                            PortalCarriageSelection.isPortalPart(anchor + slot, GROUP, every, seed),
                            "slot " + slot + " disagreed with its group at anchor " + anchor
                                + ", every=" + every + ", seed=" + seed);
                    }
                }
            }
        }
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
                assertFalse(PortalCarriageSelection.isPortalCarriage(i, groupSize, EVERY, SEED),
                    "index " + i + " at group size " + groupSize);
                assertFalse(PortalCarriageSelection.isPortalMiddle(i, groupSize, EVERY, SEED),
                    "index " + i + " at group size " + groupSize);
            }
        }
    }

    @Test
    @DisplayName("switching portals off selects nothing anywhere")
    void offSelectsNothing() {
        for (int i = -20; i <= 20; i++) {
            assertFalse(PortalCarriageSelection.isPortalCarriage(
                i, GROUP, PortalCarriageSelection.CARRIAGE_EVERY_OFF, SEED));
            assertFalse(PortalCarriageSelection.isPortalMiddle(
                i, GROUP, PortalCarriageSelection.CARRIAGE_EVERY_OFF, SEED));
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
        assertTrue(PortalCarriageSelection.isPortalCarriage(0, groupSize, EVERY, SEED));
        assertTrue(PortalCarriageSelection.isPortalMiddle(1, groupSize, EVERY, SEED));
        assertTrue(PortalCarriageSelection.isPortalCarriage(2, groupSize, EVERY, SEED));
        assertFalse(PortalCarriageSelection.isPortalCarriage(3, groupSize, EVERY, SEED));
        assertFalse(PortalCarriageSelection.isPortalMiddle(3, groupSize, EVERY, SEED));
        assertFalse(PortalCarriageSelection.isPortalCarriage(4, groupSize, EVERY, SEED));
    }
}
