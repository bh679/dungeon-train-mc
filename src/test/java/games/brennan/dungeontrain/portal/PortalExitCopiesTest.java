package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which extra corridors are standing, and — the rule this class exists for — when one may be cleared.
 *
 * <p>A corridor is longer than a room, so a copy's blocks reach into the tiles either side of the one
 * it opens into, and those tiles are stamped <b>around</b> it. Clearing a copy while any of them is
 * still standing leaves a corridor-shaped pit with no floor in a room the player can walk straight
 * back into. {@link PortalExitCopies#nextToRemove} is what prevents that, and it is the thing worth
 * pinning here: the retire distance has to be the window's radius <i>plus</i> the reach, never just
 * the radius.</p>
 */
class PortalExitCopiesTest {

    private static final int RADIUS = PortalRoomTiling.MAX_RADIUS;
    private static final int REACH = 2;

    private static Site exitAt(int x, int z) {
        return new Site(new Tile(x, z), PortalCarriageRole.EXIT);
    }

    private static Site entryAt(int x, int z) {
        return new Site(new Tile(x, z), PortalCarriageRole.ENTRY);
    }

    @Test
    @DisplayName("An empty set starts and ends every structure")
    void noneIsEmpty() {
        assertTrue(PortalExitCopies.NONE.isEmpty());
        assertEquals(0, PortalExitCopies.NONE.size());
        assertNull(PortalExitCopies.NONE.farthestFrom(Tile.BASE));
        assertNull(PortalExitCopies.NONE.nextToRemove(Tile.BASE, RADIUS, REACH));
    }

    @Test
    @DisplayName("Adding and removing are values, not mutations, and adding twice changes nothing")
    void addAndRemoveAreImmutable() {
        PortalExitCopies one = PortalExitCopies.NONE.with(exitAt(8, 0));
        assertTrue(PortalExitCopies.NONE.isEmpty());
        assertTrue(one.has(exitAt(8, 0)));
        assertEquals(1, one.size());

        assertSame(one, one.with(exitAt(8, 0)));
        assertSame(one, one.without(exitAt(3, 3)));
        assertTrue(one.without(exitAt(8, 0)).isEmpty());
    }

    @Test
    @DisplayName("The two roles at one tile are two different copies — they stand at opposite ends of it")
    void rolesAreDistinctSites() {
        PortalExitCopies both = PortalExitCopies.NONE.with(entryAt(8, 8)).with(exitAt(8, 8));
        assertEquals(2, both.size());
        assertTrue(both.has(entryAt(8, 8)));
        assertTrue(both.without(entryAt(8, 8)).has(exitAt(8, 8)));
    }

    @Test
    @DisplayName("A copy is held while any tile it reaches into is still inside the window")
    void copiesOutliveTheirTiles() {
        // Anchored exactly at the window's edge: the tiles this copy's corridor reaches into are
        // still standing, so clearing it now would take the floor out from under one of them.
        PortalExitCopies atEdge = PortalExitCopies.NONE.with(exitAt(RADIUS, 0));
        assertNull(atEdge.nextToRemove(Tile.BASE, RADIUS, REACH));

        // Just past the edge — a room copy at this tile has already retired, but the corridor's far
        // end is still inside tiles that have not.
        for (int beyond = 1; beyond <= REACH; beyond++) {
            PortalExitCopies s = PortalExitCopies.NONE.with(exitAt(RADIUS + beyond, 0));
            assertNull(s.nextToRemove(Tile.BASE, RADIUS, REACH), "beyond=" + beyond);
        }

        // Past radius + reach: every tile it touches has left the window, so the erase lands in
        // space that is already gone.
        PortalExitCopies clear = PortalExitCopies.NONE.with(exitAt(RADIUS + REACH + 1, 0));
        assertEquals(exitAt(RADIUS + REACH + 1, 0), clear.nextToRemove(Tile.BASE, RADIUS, REACH));
    }

    @Test
    @DisplayName("The window slides: a copy behind the player retires, one beside them does not")
    void theWindowFollowsThePlayer() {
        PortalExitCopies standing = PortalExitCopies.NONE
            .with(exitAt(0, 0))
            .with(exitAt(16, 0));

        // Player at the base: the far copy is well past radius + reach, the near one is under them.
        assertEquals(exitAt(16, 0), standing.nextToRemove(Tile.BASE, RADIUS, REACH));

        // Player walked out to the far copy: now it is the one they are standing in, and the copy at
        // the base is the one that has fallen behind.
        assertEquals(exitAt(0, 0), standing.nextToRemove(new Tile(16, 0), RADIUS, REACH));
    }

    @Test
    @DisplayName("Farthest first, so a walking player sheds what is behind rather than what is beside")
    void retiresFarthestFirst() {
        PortalExitCopies standing = PortalExitCopies.NONE
            .with(exitAt(9, 0))
            .with(exitAt(30, 0))
            .with(exitAt(-14, 0));
        assertEquals(exitAt(30, 0), standing.nextToRemove(Tile.BASE, RADIUS, REACH));
        assertEquals(exitAt(30, 0), standing.farthestFrom(Tile.BASE));
    }

    @Test
    @DisplayName("Draining ignores the window and sheds everything from the outside in")
    void drainingShedsEverything() {
        PortalExitCopies standing = PortalExitCopies.NONE
            .with(exitAt(1, 0))
            .with(entryAt(2, 2));
        // Both are well inside the window, so nextToRemove spares them — farthestFrom does not,
        // which is what lets a structure nobody is in drain back to nothing.
        assertNull(standing.nextToRemove(Tile.BASE, RADIUS, REACH));
        assertNotNull(standing.farthestFrom(Tile.BASE));
        assertEquals(entryAt(2, 2), standing.farthestFrom(Tile.BASE));
    }

    @Test
    @DisplayName("The distance is Chebyshev on both axes, the same one the tiling window uses")
    void distanceIsChebyshevOnBothAxes() {
        PortalExitCopies acrossZ = PortalExitCopies.NONE.with(exitAt(0, RADIUS + REACH + 1));
        assertEquals(exitAt(0, RADIUS + REACH + 1), acrossZ.nextToRemove(Tile.BASE, RADIUS, REACH));
        assertNull(PortalExitCopies.NONE.with(exitAt(0, RADIUS)).nextToRemove(Tile.BASE, RADIUS, REACH));
    }

    @Test
    @DisplayName("The standing set knows its own extent, for the footprint the erase sweeps")
    void boundsCoverEverySite() {
        PortalExitCopies standing = PortalExitCopies.NONE
            .with(exitAt(-4, 7))
            .with(entryAt(12, -2));
        assertEquals(-4, standing.minTileX());
        assertEquals(12, standing.maxTileX());
        assertEquals(-2, standing.minTileZ());
        assertEquals(7, standing.maxTileZ());
        assertFalse(standing.isEmpty());
    }
}
