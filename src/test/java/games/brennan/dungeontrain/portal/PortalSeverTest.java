package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static boolean severs(PortalCarriageRole role, int dx, int dy, int dz) {
        return PortalSever.isSeveringBreak(ENTRY_LAYOUT, role, new int[] {dx, dy, dz}, true);
    }

    @Test
    @DisplayName("a hole in the side wall past the midpoint severs an entry corridor")
    void sideWallPastMidpointSevers() {
        int pastMid = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        assertTrue(severs(PortalCarriageRole.ENTRY, pastMid, 3, 0), "wall at z=0");
        assertTrue(severs(PortalCarriageRole.ENTRY, pastMid, 3, ENTRY_LAYOUT.width() - 1), "far wall");
        assertTrue(severs(PortalCarriageRole.ENTRY, pastMid, ENTRY_LAYOUT.ceilingY(), 3), "ceiling");

        // Floor, but clear of the crossing zone — the floor line there is lanterns, which are a
        // fitting rather than shell and are covered by interiorFittingsAreExempt.
        int beyondCrossing = ENTRY_LAYOUT.farBaffleX() + 1;
        assertFalse(ENTRY_LAYOUT.isCrossingZone(beyondCrossing), "test picked a plain floor X");
        assertTrue(severs(PortalCarriageRole.ENTRY, beyondCrossing, ENTRY_LAYOUT.floorY(), 1), "floor");
    }

    @Test
    @DisplayName("the same hole before the midpoint does not sever — the illusion still holds there")
    void beforeMidpointIsHarmless() {
        int beforeMid = (int) ENTRY_LAYOUT.midX() - 2;
        assertFalse(severs(PortalCarriageRole.ENTRY, beforeMid, 3, 0));
        assertFalse(severs(PortalCarriageRole.ENTRY, beforeMid, ENTRY_LAYOUT.ceilingY(), 3));
    }

    @Test
    @DisplayName("an exit corridor mirrors the rule: its twin side is the near half")
    void exitRoleIsMirrored() {
        int nearHalf = (int) ENTRY_LAYOUT.midX() - 2;
        int farHalf = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;

        assertTrue(severs(PortalCarriageRole.EXIT, nearHalf, 3, 0),
            "an EXIT corridor's near half is the room side");
        assertFalse(severs(PortalCarriageRole.EXIT, farHalf, 3, 0),
            "an EXIT corridor's far half is the train side");

        // The two roles must disagree everywhere off the midpoint, which is the whole point of the
        // mirror — a single shared rule would sever the wrong half of every exit corridor.
        assertFalse(severs(PortalCarriageRole.ENTRY, nearHalf, 3, 0));
        assertTrue(severs(PortalCarriageRole.ENTRY, farHalf, 3, 0));
    }

    @Test
    @DisplayName("placing a block never severs — only a break does")
    void placingIsNotABreak() {
        int pastMid = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        assertFalse(PortalSever.isSeveringBreak(
            ENTRY_LAYOUT, PortalCarriageRole.ENTRY, new int[] {pastMid, 3, 0}, /*becameAir*/ false));
    }

    @Test
    @DisplayName("interior fittings are not the shell: baffles, lanterns and doorways are all harmless")
    void interiorFittingsAreExempt() {
        // The far baffle is past the midpoint, so only the shell test can be sparing it.
        assertFalse(severs(PortalCarriageRole.ENTRY, ENTRY_LAYOUT.farBaffleX(), 3, ENTRY_LAYOUT.baffleZ()),
            "baffle — a free-standing pillar, breaking it opens nothing");

        // A crossing lantern sits on the floor line but reads as a fitting.
        int crossing = (int) Math.ceil(ENTRY_LAYOUT.midX());
        assertTrue(ENTRY_LAYOUT.isCrossingZone(crossing), "test picked a crossing-zone X");
        assertFalse(severs(PortalCarriageRole.ENTRY, crossing, ENTRY_LAYOUT.floorY(), ENTRY_LAYOUT.doorZ()),
            "crossing lantern");

        // The far door is the dead dummy one; breaking it should behave like breaking any door.
        assertFalse(severs(PortalCarriageRole.ENTRY, ENTRY_LAYOUT.farDoorX(),
            ENTRY_LAYOUT.floorY() + 1, ENTRY_LAYOUT.doorZ()), "doorway lower half");
        assertFalse(severs(PortalCarriageRole.ENTRY, ENTRY_LAYOUT.farDoorX(),
            ENTRY_LAYOUT.floorY() + 2, ENTRY_LAYOUT.doorZ()), "doorway upper half");

        // But the solid part of that same end plane is shell.
        assertTrue(severs(PortalCarriageRole.ENTRY, ENTRY_LAYOUT.farDoorX(),
            ENTRY_LAYOUT.floorY() + 1, ENTRY_LAYOUT.doorZ() + 1), "end plane beside the doorway");
    }

    @Test
    @DisplayName("interior air is not the shell")
    void interiorAirIsExempt() {
        int pastMid = (int) Math.ceil(ENTRY_LAYOUT.midX()) + 2;
        assertFalse(severs(PortalCarriageRole.ENTRY, pastMid, 3, ENTRY_LAYOUT.doorZ()));
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
