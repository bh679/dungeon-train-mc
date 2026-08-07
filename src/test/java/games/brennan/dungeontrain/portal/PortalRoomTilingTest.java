package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
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
 * which is the point of keeping Minecraft types out of {@link PortalRoomTiling}.
 */
class PortalRoomTilingTest {

    private static final int RADIUS = PortalRoomTiling.MAX_RADIUS;
    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** Fill the window around {@code centre} the way the tick loop does — one tile at a time. */
    private static PortalRoomTiling fillAround(Tile centre, int radius, int budget) {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        for (Tile next; (next = tiling.nextToAdd(centre, radius, budget)) != null; ) {
            tiling = tiling.with(next);
        }
        return tiling;
    }

    // ---- the corridor row ----

    @Test
    @DisplayName("the corridor row holds tile 0 only — a copy there would land on a twin")
    void corridorRowHoldsOnlyTheBase() {
        assertTrue(PortalRoomTiling.isLegal(Tile.BASE));
        assertFalse(PortalRoomTiling.isLegal(new Tile(1, 0)));
        assertFalse(PortalRoomTiling.isLegal(new Tile(-1, 0)));
        assertFalse(PortalRoomTiling.isLegal(new Tile(4, 0)));
        // Every other row is free in X.
        assertTrue(PortalRoomTiling.isLegal(new Tile(4, 1)));
        assertTrue(PortalRoomTiling.isLegal(new Tile(-4, -1)));
    }

    @Test
    @DisplayName("an illegal tile cannot be added, however it is asked for")
    void illegalTilesAreNeverAdded() {
        PortalRoomTiling tiling = PortalRoomTiling.base().with(new Tile(2, 0));
        assertFalse(tiling.has(new Tile(2, 0)));
        assertTrue(tiling.isBaseOnly());
        // Nor through the constructor.
        assertFalse(new PortalRoomTiling(Set.of(Tile.BASE, new Tile(3, 0))).has(new Tile(3, 0)));
    }

    @Test
    @DisplayName("filling never places a tile in the corridor row")
    void fillSkipsTheCorridorRow() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        for (int x = -RADIUS; x <= RADIUS; x++) {
            if (x == 0) continue;
            assertFalse(tiling.has(new Tile(x, 0)), "corridor row tile " + x + " was filled");
        }
    }

    // ---- the base tile ----

    @Test
    @DisplayName("the base tile is always present and never retired")
    void baseTileIsPinned() {
        assertTrue(PortalRoomTiling.base().has(Tile.BASE));
        assertTrue(PortalRoomTiling.base().isBaseOnly());
        // Explicitly removing it does nothing.
        assertTrue(PortalRoomTiling.base().without(Tile.BASE).has(Tile.BASE));
        // Nor is it ever offered up for retirement, however far the player walks.
        PortalRoomTiling filled = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertFalse(Tile.BASE.equals(filled.nextToRemove(new Tile(0, 40), RADIUS)));
        assertFalse(Tile.BASE.equals(filled.farthestFrom(new Tile(0, 40))));
        // A tiling stripped back to nothing but the base offers no more to drain.
        assertNull(PortalRoomTiling.base().farthestFrom(Tile.BASE));
    }

    // ---- filling ----

    @Test
    @DisplayName("filling stops when the window is full, and covers every legal tile in it")
    void fillCoversTheWindow() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        int side = 2 * RADIUS + 1;
        // The whole square bar the corridor row's 2*RADIUS illegal tiles.
        assertEquals(side * side - 2 * RADIUS, tiling.size());
        assertNull(tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED));
    }

    @Test
    @DisplayName("filling is nearest-first, so the player is surrounded before anything distant")
    void fillIsNearestFirst() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
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
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, 6);
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
        int biggest = PortalRoomLayout.MAX_LENGTH * PortalRoomLayout.MAX_HEIGHT * PortalRoomLayout.MAX_WIDTH;

        assertEquals(window, PortalRoomTiling.budgetTiles(builtIn));
        assertEquals(PortalRoomTiling.MAX_RADIUS, PortalRoomTiling.budgetTiles(biggest));
        // Never below one, and never above the window, whatever it is handed.
        assertEquals(1, PortalRoomTiling.budgetTiles(PortalRoomTiling.MAX_RESIDENT_BLOCKS * 8));
        assertEquals(window, PortalRoomTiling.budgetTiles(0));
    }

    @Test
    @DisplayName("no resident set can outgrow the block ceiling, whatever size the room is")
    void residentBlocksStayWithinBudget() {
        for (int blocks : new int[]{1, 200, 1001, 5000, 25344}) {
            assertTrue((long) PortalRoomTiling.budgetTiles(blocks) * blocks
                    <= Math.max(PortalRoomTiling.MAX_RESIDENT_BLOCKS, (long) blocks),
                "budget for a " + blocks + "-block room exceeds the ceiling");
        }
        // Including a room so large that a single copy already blows the ceiling — it still gets one,
        // because refusing the base tile would leave a player walking into an unbuilt corridor.
        assertEquals(1, PortalRoomTiling.budgetTiles(PortalRoomTiling.MAX_RESIDENT_BLOCKS + 1));
    }

    // ---- sliding ----

    @Test
    @DisplayName("walking away sheds what is behind: tiles out of range retire, farthest first")
    void windowSlidesAfterThePlayer() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
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
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
        assertNull(tiling.nextToRemove(Tile.BASE, RADIUS));
    }

    @Test
    @DisplayName("slide then refill leaves the window centred on where the player now is")
    void slideThenRefillRecentres() {
        Tile walkedTo = new Tile(2, 8);
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);

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

    // ---- copies that cannot be built, and copies that must not be erased ----

    @Test
    @DisplayName("a candidate that cannot be built is passed over for the next-nearest, not returned")
    void unbuildableCandidatesArePassedOver() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        // Stand in for an unloaded chunk or another pair's structure sitting across the near side.
        Tile blocked = new Tile(0, -1);

        Tile chosen = tiling.nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> !t.equals(blocked));
        assertEquals(new Tile(0, 1), chosen,
            "the blocked near tile should be skipped for the other side, not stall the fill");
    }

    @Test
    @DisplayName("a fill whose every candidate is refused simply builds nothing")
    void everythingRefusedBuildsNothing() {
        assertNull(PortalRoomTiling.base().nextToAdd(Tile.BASE, RADIUS, UNLIMITED, t -> false));
    }

    @Test
    @DisplayName("a copy somebody is standing in is never retired — clearing it takes its floor too")
    void occupiedCopiesAreSpared() {
        PortalRoomTiling tiling = fillAround(Tile.BASE, RADIUS, UNLIMITED);
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
        PortalRoomTiling tiling = PortalRoomTiling.base().with(occupied).with(new Tile(0, 1));

        PortalRoomTiling drained = tiling;
        for (Tile next; (next = drained.farthestFrom(Tile.BASE, t -> !t.equals(occupied))) != null; ) {
            drained = drained.without(next);
        }
        assertTrue(drained.has(occupied));
        assertTrue(drained.has(Tile.BASE));
        assertEquals(2, drained.size());
    }

    // ---- how far you can see (the fog clamp) ----

    @Test
    @DisplayName("runLength counts the unbroken line of copies, itself included")
    void runLengthCountsTheLine() {
        PortalRoomTiling tiling = PortalRoomTiling.base()
            .with(new Tile(0, 1)).with(new Tile(0, 2)).with(new Tile(0, 4));
        assertEquals(3, tiling.runLength(Tile.BASE, 0, 1));   // 0,1,2 — the gap at 3 stops it
        assertEquals(1, tiling.runLength(Tile.BASE, 0, -1));
        assertEquals(0, tiling.runLength(new Tile(9, 9), 0, 1), "a tile that is not standing sees nothing");
    }

    @Test
    @DisplayName("looking along the corridor row is not a fillable gap — the fog leaves the doors alone")
    void corridorRowIsNotAFillableGap() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        assertFalse(tiling.runEndsAtFillableGap(Tile.BASE, 1, 0));
        assertFalse(tiling.runEndsAtFillableGap(Tile.BASE, -1, 0));
        // Across the train it is, so an unbuilt copy there does constrain the fog.
        assertTrue(tiling.runEndsAtFillableGap(Tile.BASE, 0, 1));
    }

    @Test
    @DisplayName("off the corridor row every direction is fillable, so all four constrain the fog")
    void offTheCorridorRowAllDirectionsFill() {
        PortalRoomTiling tiling = PortalRoomTiling.base().with(new Tile(0, 1));
        Tile from = new Tile(0, 1);
        assertTrue(tiling.runEndsAtFillableGap(from, 1, 0));
        assertTrue(tiling.runEndsAtFillableGap(from, -1, 0));
        assertTrue(tiling.runEndsAtFillableGap(from, 0, 1));
    }

    // ---- bounds ----

    @Test
    @DisplayName("a base-only tiling reports the base tile's own bounds — the boxes stay what they were")
    void baseOnlyBoundsAreZero() {
        PortalRoomTiling tiling = PortalRoomTiling.base();
        assertEquals(0, tiling.minTileX());
        assertEquals(0, tiling.maxTileX());
        assertEquals(0, tiling.minTileZ());
        assertEquals(0, tiling.maxTileZ());
    }

    @Test
    @DisplayName("bounds span every standing copy")
    void boundsSpanTheResidentSet() {
        PortalRoomTiling tiling = PortalRoomTiling.base()
            .with(new Tile(-2, 1)).with(new Tile(3, -4));
        assertEquals(-2, tiling.minTileX());
        assertEquals(3, tiling.maxTileX());
        assertEquals(-4, tiling.minTileZ());
        assertEquals(1, tiling.maxTileZ());
    }

    @Test
    @DisplayName("adding a tile that is already standing is a no-op, not a copy")
    void addingAnExistingTileIsANoOp() {
        PortalRoomTiling tiling = PortalRoomTiling.base().with(new Tile(0, 1));
        assertSame(tiling, tiling.with(new Tile(0, 1)));
        assertSame(tiling, tiling.without(new Tile(5, 5)));
    }
}
