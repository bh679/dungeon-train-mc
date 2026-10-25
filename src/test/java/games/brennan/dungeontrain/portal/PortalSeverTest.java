package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides whether a break kills a portal's way in.
 *
 * <p>Only the geometry is tested here — {@link PortalSever#isSeveringBreak} is deliberately split
 * from the level plumbing so it can be, since the geometry is where the subtlety lives. The one-way
 * gating itself is a single {@code FRAME_TWIN} condition at two call sites and is checked in game.</p>
 */
final class PortalSeverTest {

    /** The default carriage: 9 long, 7 wide, 7 high — so a 13-long corridor, midpoint at 6.5. */
    private static final PortalCarriageLayout ENTRY_LAYOUT = new PortalCarriageLayout(13, 7, 7);

    private static boolean severs(int dx, int dy, int dz) {
        return PortalSever.isSeveringBreak(ENTRY_LAYOUT, new int[] {dx, dy, dz}, true);
    }

    @Test
    @DisplayName("a hole in either side wall severs, in either half of the corridor")
    void sideWallAnywhereSevers() {
        int nearHalf = (int) ENTRY_LAYOUT.midX() - 2;
        int farHalf = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        for (int dx : new int[] {nearHalf, farHalf}) {
            assertTrue(severs(dx, 3, 0), "wall at z=0, x=" + dx);
            assertTrue(severs(dx, 3, ENTRY_LAYOUT.width() - 1), "far wall, x=" + dx);
        }
    }

    @Test
    @DisplayName("floor, ceiling and end planes do not sever — only the side walls do")
    void onlySideWallsSever() {
        int farHalf = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        assertFalse(severs(farHalf, ENTRY_LAYOUT.ceilingY(), 3), "ceiling");
        int beyondCrossing = ENTRY_LAYOUT.farBaffleX() + 1;
        assertFalse(severs(beyondCrossing, ENTRY_LAYOUT.floorY(), 1), "floor");
        assertFalse(severs(ENTRY_LAYOUT.farDoorX(), ENTRY_LAYOUT.floorY() + 1, ENTRY_LAYOUT.doorZ() + 1),
            "end plane beside the doorway");
    }

    @Test
    @DisplayName("placing a block never severs — only a break does")
    void placingIsNotABreak() {
        assertFalse(PortalSever.isSeveringBreak(
            ENTRY_LAYOUT, new int[] {3, 3, 0}, /*becameAir*/ false));
    }

    @Test
    @DisplayName("interior fittings are not the side wall: baffles, lanterns and doorways are harmless")
    void interiorFittingsAreExempt() {
        assertFalse(severs(ENTRY_LAYOUT.farBaffleX(), 3, ENTRY_LAYOUT.baffleZ()), "baffle");
        int crossing = (int) Math.ceil(ENTRY_LAYOUT.midX());
        assertFalse(severs(crossing, ENTRY_LAYOUT.floorY(), ENTRY_LAYOUT.doorZ()), "crossing lantern");
        assertFalse(severs(ENTRY_LAYOUT.farDoorX(), ENTRY_LAYOUT.floorY() + 1, ENTRY_LAYOUT.doorZ()),
            "doorway");
    }

    @Test
    @DisplayName("one hole closes both ends: the pair resolves the same from either corridor")
    void severingIsPairWide() {
        int groupSize = 3;
        int entry = 24;                                        // a group anchor, as in a live run
        int exit = entry + PortalCarriageSelection.SLOT_EXIT;

        // The pair a break resolves to, whichever corridor was mined. Both directions must name the
        // same two carriages, or a hole in the exit corridor would name a different pair from a hole
        // in the entry one.
        assertEquals(exit, PortalCarriageRole.partnerIndex(entry, groupSize));
        assertEquals(entry, PortalCarriageRole.partnerIndex(exit, groupSize));

        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(entry, groupSize));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(exit, groupSize));
    }

    @Test
    @DisplayName("interior air is not the shell")
    void interiorAirIsExempt() {
        int pastMid = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        assertFalse(severs(pastMid, 3, ENTRY_LAYOUT.doorZ()));
    }

    /**
     * The property everything downstream leans on.
     *
     * <p>{@code PortalSever.onCarriageBlockChanged} records one entry, against the group's anchor,
     * whichever corridor was mined; {@code PortalCarriageBuilder.stampMiddle} and the swap both ask
     * that one question — "is this group's anchor severed?" — to decide whether the wall between the
     * pair's doors stands open and whether either end takes anyone in.</p>
     *
     * <p>It is a correct pair-level answer only because the anchor is reachable from both ends: the
     * entry corridor <i>is</i> the anchor, and the exit's partner is. Were that to stop holding, a
     * pair broken from its exit end would be filed under an index nothing asks about — the wall would
     * stamp shut again on the next pass of the rolling window, closing the way out from under a
     * player walking through it, and the pair would go on swallowing people at the end that still
     * worked.</p>
     */
    @Test
    @DisplayName("severing either end always marks the group's anchor")
    void severingEitherEndMarksTheAnchor() {
        for (int groupSize : new int[] {3, 4, 7}) {
            for (int anchorOrdinal = -3; anchorOrdinal <= 3; anchorOrdinal++) {
                int anchor = anchorOrdinal * groupSize;
                int entry = anchor + PortalCarriageSelection.SLOT_ENTRY;
                int exit = anchor + PortalCarriageSelection.SLOT_EXIT;

                assertEquals(anchor, PortalCarriageRole.entryIndexOf(entry, groupSize));
                assertEquals(anchor, PortalCarriageRole.entryIndexOf(exit, groupSize));

                // Broken from the entry: the anchor is the broken index itself.
                assertEquals(anchor, entry, "the entry corridor IS the group anchor");
                // Broken from the exit: the anchor is the partner that goes with it.
                assertEquals(anchor, PortalCarriageRole.partnerIndex(exit, groupSize),
                    "an exit's partner must be the anchor, at group size " + groupSize);
            }
        }
    }

    @Test
    @DisplayName("the shell test agrees with the geometry the corridor is actually stamped from")
    void shellMatchesStampedGeometry() {
        // Every cell the layout calls shell must be a cell the builder fills, and no cell it calls
        // shell may be one the builder leaves as interior air. This is the invariant that lets
        // stateAt delegate here rather than keep its own copy of the test.
        for (int dx = 0; dx < ENTRY_LAYOUT.length(); dx++) {
            for (int dy = 0; dy < ENTRY_LAYOUT.height(); dy++) {
                for (int dz = 0; dz < ENTRY_LAYOUT.width(); dz++) {
                    if (!ENTRY_LAYOUT.isShellCell(dx, dy, dz)) continue;
                    boolean onBoundary = dy == ENTRY_LAYOUT.floorY() || dy == ENTRY_LAYOUT.ceilingY()
                        || dz < ENTRY_LAYOUT.interiorMinZ() || dz > ENTRY_LAYOUT.interiorMaxZ()
                        || dx == ENTRY_LAYOUT.nearDoorX() || dx == ENTRY_LAYOUT.farDoorX();
                    assertTrue(onBoundary,
                        "shell cell (" + dx + "," + dy + "," + dz + ") is not on the corridor boundary");
                }
            }
        }
    }
}
