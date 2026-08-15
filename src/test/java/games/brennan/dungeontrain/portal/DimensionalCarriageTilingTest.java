package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.DimensionalCarriageTiling.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliding window of room copies. Pure integer geometry, so this needs no NeoForge bootstrap —
 * which is the point of keeping Minecraft types out of {@link DimensionalCarriageTiling}.
 */
class DimensionalCarriageTilingTest {

    private static final int RADIUS = DimensionalCarriageTiling.MAX_RADIUS;
    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** Fill the window around {@code centre} the way the tick loop does — one tile at a time. */
    private static DimensionalCarriageTiling fillAround(Tile centre, int radius, int budget) {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base();
        for (Tile next; (next = tiling.nextToAdd(centre, radius, budget)) != null; ) {
            tiling = tiling.with(next);
        }
        return tiling;
    }

    // ---- the corridor row ----

    @Test
    @DisplayName("the corridor row tiles like any other — the corridors are stamped back on top")
    void corridorRowTilesLikeAnyOther() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        for (int x = -RADIUS; x <= RADIUS; x++) {
            assertTrue(tiling.has(new Tile(x, 0)), "corridor row tile " + x + " should be filled");
        }
    }

    @Test
    @DisplayName("filling covers the whole square, corridor row included")
    void fillCoversEveryCell() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        int side = 2 * RADIUS + 1;
        assertEquals(side * side, tiling.size());
        assertNull(tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED));
    }

    // ---- the base tile ----

    @Test
    @DisplayName("the base tile is always present and never retired")
    void baseTileIsPinned() {
        assertTrue(DimensionalCarriageTiling.base().has(Tile.BASE));
        assertTrue(DimensionalCarriageTiling.base().isBaseOnly());
        // Explicitly removing it does nothing.
        assertTrue(DimensionalCarriageTiling.base().without(Tile.BASE).has(Tile.BASE));
        // Nor is it ever offered up for retirement, however far the player walks.
        DimensionalCarriageTiling filled = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertFalse(Tile.BASE.equals(filled.nextToRemove(new Tile(0, 40), RADIUS)));
        assertFalse(Tile.BASE.equals(filled.farthestFrom(new Tile(0, 40))));
        // A tiling stripped back to nothing but the base offers no more to drain.
        assertNull(DimensionalCarriageTiling.base().farthestFrom(Tile.BASE));
    }

    // ---- filling ----

    @Test
    @DisplayName("filling is nearest-first, so the player is surrounded before anything distant")
    void fillIsNearestFirst() {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base();
        int previousDistance = 0;
        for (Tile next; (next = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED)) != null; ) {
            int distance = next.distanceSqTo(Tile.BASE);
            assertTrue(distance >= previousDistance,
                "tile " + next + " at " + distance + " came after one at " + previousDistance);
            previousDistance = distance;
            tiling = tiling.with(next);
        }
    }

    @Test
    @DisplayName("the budget caps the resident set — a big room gets neighbours, not a hundred copies")
    void budgetCapsTheResidentSet() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, 6);
        assertEquals(6, tiling.size());
        assertNull(tiling.nextToAdd(Tile.BASE, RADIUS, 6));
        // And what it kept is the nearest ring, not an arbitrary six.
        assertTrue(tiling.has(new Tile(0, 1)));
        assertTrue(tiling.has(new Tile(0, -1)));
    }

    @Test
    @DisplayName("budgetTiles spends the block budget: a small room fills the window, a max room does not")
    void budgetTilesScalesWithRoomSize() {
        int window = (2 * RADIUS + 1) * (2 * RADIUS + 1);
        int builtIn = 11 * 7 * 13;
        int biggest = DimensionalCarriageLayout.MAX_LENGTH * DimensionalCarriageLayout.MAX_HEIGHT * DimensionalCarriageLayout.MAX_WIDTH;

        assertEquals(window, DimensionalCarriageTiling.budgetTiles(builtIn));
        assertEquals(DimensionalCarriageTiling.MAX_RADIUS, DimensionalCarriageTiling.budgetTiles(biggest));
        // Never below one, and never above the window, whatever it is handed.
        assertEquals(1, DimensionalCarriageTiling.budgetTiles(DimensionalCarriageTiling.MAX_RESIDENT_BLOCKS * 8));
        assertEquals(window, DimensionalCarriageTiling.budgetTiles(0));
    }

    @Test
    @DisplayName("no resident set can outgrow the block ceiling, whatever size the room is")
    void residentBlocksStayWithinBudget() {
        for (int blocks : new int[]{1, 200, 1001, 5000, 25344}) {
            assertTrue((long) DimensionalCarriageTiling.budgetTiles(blocks) * blocks
                    <= Math.max(DimensionalCarriageTiling.MAX_RESIDENT_BLOCKS, (long) blocks),
                "budget for a " + blocks + "-block room exceeds the ceiling");
        }
        // Including a room so large that a single copy already blows the ceiling — it still gets one,
        // because refusing the base tile would leave a player walking into an unbuilt corridor.
        assertEquals(1, DimensionalCarriageTiling.budgetTiles(DimensionalCarriageTiling.MAX_RESIDENT_BLOCKS + 1));
    }

    // ---- sliding ----

    @Test
    @DisplayName("walking away sheds what is behind: tiles out of range retire, farthest first")
    void windowSlidesAfterThePlayer() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        Tile walkedTo = new Tile(0, 3);

        Tile retiring = tiling.nextToRemove(walkedTo, RADIUS);
        assertNotNull(retiring, "tiles behind the player should fall out of range");
        assertTrue(Math.abs(retiring.z() - walkedTo.z()) > RADIUS
                || Math.abs(retiring.x() - walkedTo.x()) > RADIUS,
            retiring + " is still inside the window around " + walkedTo);
        // Farthest first.
        assertEquals(-RADIUS, retiring.z());
    }

    @Test
    @DisplayName("a window centred on the player has nothing to retire")
    void nothingRetiresWhileCentred() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertNull(tiling.nextToRemove(Tile.BASE, RADIUS));
    }

    @Test
    @DisplayName("slide then refill leaves the window centred on where the player now is")
    void slideThenRefillRecentres() {
        Tile walkedTo = new Tile(2, 8);
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);

        // Retire what fell behind, then fill what came into range — the same two steps the tick loop
        // takes, one tile at a time, threaded through the same tiling rather than starting over.
        for (Tile stale; (stale = tiling.nextToRemove(walkedTo, RADIUS)) != null; ) {
            tiling = tiling.without(stale);
        }
        for (Tile next; (next = tiling.nextToAdd(walkedTo, RADIUS, UNLIMITED)) != null; ) {
            tiling = tiling.with(next);
        }

        assertTrue(tiling.has(walkedTo.offset(0, RADIUS)), "the leading edge should have filled in");
        assertTrue(tiling.has(walkedTo.offset(RADIUS, 0)));
        // Nothing is left behind the window bar the base, which holds the corridors and never retires.
        assertTrue(tiling.has(Tile.BASE));
        assertFalse(tiling.has(new Tile(0, 1)), "a tile far behind the player should have retired");
        assertNull(tiling.nextToRemove(walkedTo, RADIUS));
    }

    @Test
    @DisplayName("the approach radius builds one ring — eight copies, not a hundred and twenty")
    void approachRadiusBuildsOneRing() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, DimensionalCarriageTiling.APPROACH_RADIUS, UNLIMITED);
        // The whole 3×3 around the base, the base included.
        assertEquals(9, tiling.size());
        assertTrue(tiling.has(new Tile(1, 1)));
        assertTrue(tiling.has(new Tile(0, 1)));
        assertTrue(tiling.has(new Tile(1, 0)), "the corridor row tiles too");
        assertFalse(tiling.has(new Tile(0, 2)), "one ring, not two");

        // And it is a small fraction of what standing in the room gets you.
        assertTrue(fillAround(Tile.BASE, RADIUS, UNLIMITED).size() > tiling.size() * 10);
    }

    @Test
    @DisplayName("walking in widens the window rather than rebuilding it — the ring is kept")
    void approachRingSurvivesWideningToTheFullWindow() {
        DimensionalCarriageTiling approached =
            fillAround(Tile.BASE, DimensionalCarriageTiling.APPROACH_RADIUS, UNLIMITED);
        DimensionalCarriageTiling inside = approached;
        for (Tile next; (next = inside.nextToAdd(Tile.BASE, RADIUS, UNLIMITED)) != null; ) {
            inside = inside.with(next);
        }
        for (Tile t : approached.tiles()) {
            assertTrue(inside.has(t), t + " was dropped when the window widened");
        }
        assertNull(inside.nextToRemove(Tile.BASE, RADIUS));
    }

    // ---- copies that cannot be built, and copies that must not be erased ----

    @Test
    @DisplayName("a candidate that cannot be built is passed over for the next-nearest, not returned")
    void unbuildableCandidatesArePassedOver() {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base();

        // Whatever it would have picked first...
        Tile first = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED);
        assertNotNull(first);

        // ...stands in for an unloaded chunk, or another pair's structure sitting in the way.
        Tile chosen = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> !t.equals(first));
        assertNotNull(chosen, "a blocked candidate should be passed over, not stall the fill");
        assertFalse(first.equals(chosen));
        // Still the nearest of what is left, not an arbitrary cell.
        assertEquals(1, chosen.distanceSqTo(Tile.BASE));
    }

    @Test
    @DisplayName("a fill whose every candidate is refused simply builds nothing")
    void everythingRefusedBuildsNothing() {
        assertNull(DimensionalCarriageTiling.base().nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> false));
    }

    @Test
    @DisplayName("a copy somebody is standing in is never retired — clearing it takes its floor too")
    void occupiedCopiesAreSpared() {
        DimensionalCarriageTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        Tile walkedTo = new Tile(0, 3);
        Tile secondPlayer = new Tile(5, -5);   // the far corner, first in line to retire

        assertEquals(secondPlayer, tiling.nextToRemove(walkedTo, RADIUS));
        Tile spared = tiling.nextToRemove(walkedTo, RADIUS, t -> !t.equals(secondPlayer));
        assertNotNull(spared);
        assertFalse(secondPlayer.equals(spared));
    }

    @Test
    @DisplayName("draining spares occupied copies too — a logged-in player is not dropped")
    void drainSparesOccupiedCopies() {
        Tile occupied = new Tile(0, 5);
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base().with(occupied).with(new Tile(0, 1));

        DimensionalCarriageTiling drained = tiling;
        for (Tile next; (next = drained.farthestFrom(Tile.BASE, t -> !t.equals(occupied))) != null; ) {
            drained = drained.without(next);
        }
        assertTrue(drained.has(occupied));
        assertTrue(drained.has(Tile.BASE));
        assertEquals(2, drained.size());
    }

    // ---- bounds ----

    @Test
    @DisplayName("a base-only tiling reports the base tile's own bounds — the boxes stay what they were")
    void baseOnlyBoundsAreZero() {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base();
        assertEquals(0, tiling.minTileX());
        assertEquals(0, tiling.maxTileX());
        assertEquals(0, tiling.minTileZ());
        assertEquals(0, tiling.maxTileZ());
    }

    @Test
    @DisplayName("bounds span every standing copy")
    void boundsSpanTheResidentSet() {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base()
            .with(new Tile(-2, 1)).with(new Tile(3, -4));
        assertEquals(-2, tiling.minTileX());
        assertEquals(3, tiling.maxTileX());
        assertEquals(-4, tiling.minTileZ());
        assertEquals(1, tiling.maxTileZ());
    }

    @Test
    @DisplayName("adding a tile that is already standing is a no-op, not a copy")
    void addingAnExistingTileIsANoOp() {
        DimensionalCarriageTiling tiling = DimensionalCarriageTiling.base().with(new Tile(0, 1));
        assertSame(tiling, tiling.with(new Tile(0, 1)));
        assertSame(tiling, tiling.without(new Tile(5, 5)));
    }
}
